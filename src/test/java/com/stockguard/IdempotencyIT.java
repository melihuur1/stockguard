package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.stockguard.support.Api;
import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class IdempotencyIT extends IntegrationTest {

    @RepeatedTest(3)
    @DisplayName("Scenario 3: 50 concurrent requests with one key create one reservation and all get the same answer")
    void concurrentDuplicatesCreateOneReservation() {
        UUID product = newProduct(5);
        TestUser user = newUser();

        Concurrent.Run<Api.Response> run = Concurrent.run(50, i -> reserve(user, product, 1, "same-key"));

        assertThat(run.peakInFlight()).isGreaterThanOrEqualTo(25);
        assertThat(statusCounts(run.results())).containsOnly(entry(201, 1L), entry(200, 49L));
        assertThat(run.results()).extracting(r -> r.text("id")).containsOnly(run.results().getFirst().text("id"));
        assertThat(run.results()).filteredOn(r -> r.status() == 200)
                .allSatisfy(r -> assertThat(r.header("Idempotency-Replayed")).isEqualTo("true"));
        assertThat(reservationRows(product)).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(new Stock(5, 4, 1));
        assertConsistent(product);

        ConcurrencyReport.add("Scenario 3: 50 duplicates of one key, stock 5",
                "201=1, replays=49, rows=" + reservationRows(product) + ", stock=" + stock(product) + ", peak in-flight=" + run.peakInFlight());
    }

    @Test
    @DisplayName("Duplicates of the only unit in stock are replays, never 'insufficient stock'")
    void duplicatesOfLastUnitAreNotRejected() {
        UUID product = newProduct(1);
        TestUser user = newUser();

        Concurrent.Run<Api.Response> run = Concurrent.run(30, i -> reserve(user, product, 1, "last-unit"));

        assertThat(statusCounts(run.results())).containsOnly(entry(201, 1L), entry(200, 29L));
        assertThat(stock(product)).isEqualTo(new Stock(1, 0, 1));
    }

    @Test
    @DisplayName("Reusing a key with a different request returns 422 and changes nothing")
    void differentPayloadIsRejected() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        assertThat(reserve(user, product, 1, "reused").status()).isEqualTo(201);

        Api.Response differentQuantity = reserve(user, product, 2, "reused");
        Api.Response differentProduct = reserve(user, newProduct(5), 1, "reused");

        assertThat(differentQuantity.status()).isEqualTo(422);
        assertThat(differentQuantity.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(differentProduct.status()).isEqualTo(422);
        assertThat(stock(product)).isEqualTo(new Stock(5, 4, 1));
        assertThat(reservationRows(product)).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent requests that share a key but differ in content still produce one reservation")
    void concurrentMixedPayloads() {
        UUID product = newProduct(20);
        TestUser user = newUser();

        // Half ask for one unit and half for two. Whichever commits first defines what the key means.
        Concurrent.Run<Api.Response> run = Concurrent.run(40, i -> reserve(user, product, 1 + i % 2, "contested"));

        Map<Integer, Long> statuses = statusCounts(run.results());
        assertThat(statuses.get(201)).isEqualTo(1);
        assertThat(statuses.get(200)).isEqualTo(19);
        assertThat(statuses.get(422)).isEqualTo(20);
        assertThat(reservationRows(product)).isEqualTo(1);
        assertConsistent(product);
    }

    @Test
    @DisplayName("Keys belong to a user: two users using the same key get separate reservations")
    void keysAreScopedToUser() {
        UUID product = newProduct(5);
        List<TestUser> users = newUsers(2);

        Api.Response first = reserve(users.get(0), product, 1, "shared-key");
        Api.Response second = reserve(users.get(1), product, 1, "shared-key");

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(first.text("id")).isNotEqualTo(second.text("id"));
        assertThat(stock(product).reserved()).isEqualTo(2);
    }

    @Test
    @DisplayName("A rejected attempt does not use up the key: the same key works once stock arrives")
    void failedAttemptDoesNotBurnKey() {
        UUID product = newProduct(0);
        TestUser user = newUser();

        Api.Response rejected = reserve(user, product, 1, "retry-me");
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(reservationRows(product)).isZero();

        addStock(product, 1);

        assertThat(reserve(user, product, 1, "retry-me").status()).isEqualTo(201);
        assertThat(stock(product)).isEqualTo(new Stock(1, 0, 1));
        assertConsistent(product);
    }

    @Test
    @DisplayName("A replay after cancellation returns the cancelled reservation and holds no new stock")
    void replayAfterCancelDoesNotHoldStockAgain() {
        UUID product = newProduct(3);
        TestUser user = newUser();
        Api.Response created = reserve(user, product, 1, "cancel-then-replay");
        assertThat(cancel(user, UUID.fromString(created.text("id"))).status()).isEqualTo(200);

        Api.Response replay = reserve(user, product, 1, "cancel-then-replay");

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.text("id")).isEqualTo(created.text("id"));
        assertThat(replay.text("status")).isEqualTo("CANCELLED");
        assertThat(stock(product)).isEqualTo(new Stock(3, 3, 0));
        assertConsistent(product);
    }

    @Test
    @DisplayName("A missing or malformed key is a client error")
    void keyIsRequiredAndValidated() {
        UUID product = newProduct(1);
        TestUser user = newUser();
        Map<String, Object> body = Map.of("productId", product, "quantity", 1);

        Api.Response missing = api.post("/api/v1/reservations", user.token(), body);
        Api.Response invalid = reserve(user, product, 1, "spaces are not allowed");
        Api.Response tooLong = reserve(user, product, 1, "k".repeat(129));

        assertThat(missing.status()).isEqualTo(400);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(stock(product)).isEqualTo(new Stock(1, 1, 0));
    }

    @Test
    @DisplayName("The guarantee holds in the database itself, independently of application code")
    void uniqueConstraintEnforcedByDatabase() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        assertThat(reserve(user, product, 1, "db-level").status()).isEqualTo(201);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO reservations (id, product_id, user_id, quantity, status, idempotency_key, request_hash, expires_at, created_at)
                VALUES (gen_random_uuid(), :product, :user, 1, 'PENDING', 'db-level', 'x', now(), now())
                """)
                .param("product", product)
                .param("user", user.id())
                .update())
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_reservations_user_idempotency_key");
    }
}
