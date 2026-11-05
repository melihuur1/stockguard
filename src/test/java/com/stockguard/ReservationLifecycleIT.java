package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.stockguard.support.Api;
import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ReservationLifecycleIT extends IntegrationTest {

    @Test
    @DisplayName("Scenario 4: cancelling a reservation makes its units available again")
    void cancelRecoversStock() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);
        assertThat(stock(product)).isEqualTo(new Stock(5, 3, 2));

        Api.Response cancelled = cancel(user, reservation);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.text("status")).isEqualTo("CANCELLED");
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
        assertConsistent(product);
        // The recovered units can really be sold again.
        assertThat(reserve(newUser(), product, 5, "after-cancel").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("Scenario 5: confirming moves units out of the stock, once")
    void confirmAccountsCorrectly() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);

        Api.Response confirmed = confirm(user, reservation);

        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.text("status")).isEqualTo("CONFIRMED");
        // Available was already reduced at reservation time and must not drop a second time.
        assertThat(stock(product)).isEqualTo(new Stock(3, 3, 0));
        assertConsistent(product);
    }

    @Test
    @DisplayName("20 concurrent confirmations settle a reservation once and reduce the stock once")
    void concurrentConfirmsSettleOnce() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);

        Concurrent.Run<Api.Response> run = Concurrent.run(20, i -> confirm(user, reservation));

        assertThat(statusCounts(run.results())).containsOnly(entry(200, 1L), entry(409, 19L));
        assertThat(run.results()).filteredOn(r -> r.status() == 409)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("RESERVATION_ALREADY_SETTLED"));
        assertThat(stock(product)).isEqualTo(new Stock(3, 3, 0));
        assertConsistent(product);

        ConcurrencyReport.add("20 concurrent confirms of one reservation",
                "200=1, 409=19, stock=" + stock(product) + " (reduced once)");
    }

    @Test
    @DisplayName("20 concurrent cancellations release the units once")
    void concurrentCancelsReleaseOnce() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);

        Concurrent.Run<Api.Response> run = Concurrent.run(20, i -> cancel(user, reservation));

        assertThat(statusCounts(run.results())).containsOnly(entry(200, 1L), entry(409, 19L));
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
        assertConsistent(product);
    }

    @Test
    @DisplayName("Racing confirm against cancel: one wins each round and the stock always matches the winner")
    void confirmRacingCancel() {
        int rounds = 30;
        UUID product = newProduct(rounds);
        TestUser user = newUser();
        int confirmed = 0;
        int cancelled = 0;

        for (int round = 0; round < rounds; round++) {
            UUID reservation = reserveOk(user, product, 1);
            List<Api.Response> results = Concurrent.run(2, i -> i == 0 ? confirm(user, reservation) : cancel(user, reservation)).results();

            assertThat(statusCounts(results)).containsOnly(entry(200, 1L), entry(409, 1L));
            String status = statusOf(reservation);
            if (results.get(0).status() == 200) {
                assertThat(status).isEqualTo("CONFIRMED");
                confirmed++;
            } else {
                assertThat(status).isEqualTo("CANCELLED");
                cancelled++;
            }
        }

        assertThat(stock(product)).isEqualTo(new Stock(rounds - confirmed, rounds - confirmed, 0));
        assertConsistent(product);
        ConcurrencyReport.add("Confirm racing cancel, " + rounds + " rounds",
                "confirm won " + confirmed + ", cancel won " + cancelled + ", never both, stock=" + stock(product));
    }

    @Test
    @DisplayName("A settled reservation stays settled")
    void terminalStatesAreFinal() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID confirmedOne = reserveOk(user, product, 1);
        UUID cancelledOne = reserveOk(user, product, 1);
        confirm(user, confirmedOne);
        cancel(user, cancelledOne);
        Stock before = stock(product);

        for (Api.Response rejected : List.of(
                cancel(user, confirmedOne), confirm(user, confirmedOne),
                confirm(user, cancelledOne), cancel(user, cancelledOne))) {
            assertThat(rejected.status()).isEqualTo(409);
            assertThat(rejected.code()).isEqualTo("RESERVATION_ALREADY_SETTLED");
        }

        assertThat(stock(product)).isEqualTo(before);
        assertConsistent(product);
    }

    @Test
    @DisplayName("Every stock change leaves a ledger line whose after-values chain together")
    void ledgerRecordsEveryChange() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID toConfirm = reserveOk(user, product, 2);
        UUID toCancel = reserveOk(user, product, 1);
        confirm(user, toConfirm);
        cancel(user, toCancel);

        JsonNode items = api.get("/api/v1/products/" + product + "/stock/movements", admin().token()).json().get("items");

        // Newest first.
        assertThat(items.size()).isEqualTo(5);
        assertMovement(items.get(0), "CANCEL_RELEASED", 3, 3, 0);
        assertMovement(items.get(1), "CONFIRMED", 3, 2, 1);
        assertMovement(items.get(2), "RESERVED", 5, 2, 3);
        assertMovement(items.get(3), "RESERVED", 5, 3, 2);
        assertMovement(items.get(4), "STOCK_IN", 5, 5, 0);
    }

    private static void assertMovement(JsonNode movement, String type, int total, int available, int reserved) {
        assertThat(movement.get("type").asString()).isEqualTo(type);
        assertThat(movement.get("totalAfter").asInt()).isEqualTo(total);
        assertThat(movement.get("availableAfter").asInt()).isEqualTo(available);
        assertThat(movement.get("reservedAfter").asInt()).isEqualTo(reserved);
    }

    @Test
    @DisplayName("Critical operations are audited with who, what and which instance")
    void auditTrailRecordsLifecycle() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);
        confirm(user, reservation);

        JsonNode items = api.get("/api/v1/audit-events?entityId=" + reservation, admin().token()).json().get("items");

        assertThat(items.size()).isEqualTo(2);
        JsonNode confirmed = items.get(0);
        assertThat(confirmed.get("type").asString()).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(confirmed.get("actorId").asString()).isEqualTo(user.id().toString());
        assertThat(confirmed.get("instanceId").asString()).isEqualTo("it-main");
        assertThat(confirmed.get("details").get("quantity").asInt()).isEqualTo(2);
        assertThat(items.get(1).get("type").asString()).isEqualTo("RESERVATION_CREATED");
    }

    @Test
    @DisplayName("A rejected reservation leaves no audit event, because it never happened")
    void rejectedAttemptLeavesNoTrace() {
        UUID product = newProduct(0);
        TestUser user = newUser();
        long before = auditEventsOf(user);

        assertThat(reserve(user, product, 1, "rejected").status()).isEqualTo(409);

        assertThat(auditEventsOf(user)).isEqualTo(before);
    }

    private long auditEventsOf(TestUser user) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE actor_id = :id").param("id", user.id()).query(Long.class).single();
    }

    @Test
    @DisplayName("Users see their own reservations and administrators see all of them")
    void listingIsScopedByRole() {
        UUID product = newProduct(5);
        List<TestUser> users = newUsers(2);
        UUID mine = reserveOk(users.get(0), product, 1);
        reserveOk(users.get(1), product, 1);

        JsonNode own = api.get("/api/v1/reservations", users.get(0).token()).json();

        assertThat(own.get("totalElements").asInt()).isEqualTo(1);
        assertThat(own.get("items").get(0).get("id").asString()).isEqualTo(mine.toString());
        assertThat(api.get("/api/v1/reservations", admin().token()).json().get("totalElements").asInt()).isGreaterThanOrEqualTo(2);
    }
}
