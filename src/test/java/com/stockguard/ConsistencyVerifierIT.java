package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockguard.inventory.ConsistencyService.ConsistencyReport;
import com.stockguard.inventory.ConsistencyService.Violation;
import com.stockguard.support.Api;
import com.stockguard.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Almost every other test relies on the verifier reporting "no violations", which proves nothing unless it can
 * also report some. Here the data is corrupted on purpose, the way a bug or a manual fix would corrupt it, in
 * ways that get past the CHECK constraints, and the verifier has to notice.
 */
class ConsistencyVerifierIT extends IntegrationTest {

    @Test
    void healthyProductHasNoViolations() {
        UUID product = newProduct(5);
        reserveOk(newUser(), product, 2);

        ConsistencyReport report = consistency.check(product);

        assertThat(report.consistent()).isTrue();
        assertThat(report.violations()).isEmpty();
        assertThat(report.rulesEvaluated()).hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("Detects counters changed without a ledger entry")
    void detectsCountersThatDoNotMatchLedger() {
        UUID product = newProduct(5);

        jdbc.sql("UPDATE stock_levels SET total_quantity = total_quantity + 1, available_quantity = available_quantity + 1 WHERE product_id = :id")
                .param("id", product)
                .update();

        assertThat(rulesViolatedBy(product)).contains("COUNTERS_DO_NOT_MATCH_LEDGER");
    }

    @Test
    @DisplayName("Detects reserved units that no pending reservation accounts for")
    void detectsReservedMismatch() {
        UUID product = newProduct(5);

        jdbc.sql("UPDATE stock_levels SET available_quantity = available_quantity - 1, reserved_quantity = reserved_quantity + 1 WHERE product_id = :id")
                .param("id", product)
                .update();

        assertThat(rulesViolatedBy(product)).contains("RESERVED_DOES_NOT_MATCH_PENDING_RESERVATIONS");
    }

    @Test
    @DisplayName("Detects overselling")
    void detectsOverselling() {
        UUID product = newProduct(1);
        TestUser user = newUser();
        jdbc.sql("""
                INSERT INTO reservations (id, product_id, user_id, quantity, status, idempotency_key, request_hash, expires_at, created_at)
                VALUES (gen_random_uuid(), :product, :user, 5, 'PENDING', :key, 'x', now() + interval '1 day', now())
                """)
                .param("product", product)
                .param("user", user.id())
                .param("key", UUID.randomUUID().toString())
                .update();

        assertThat(rulesViolatedBy(product)).contains("OVERSOLD", "RESERVATION_NOT_SETTLED_EXACTLY_ONCE");
    }

    @Test
    @DisplayName("Detects a reservation settled twice")
    void detectsDoubleSettlement() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 1);
        confirm(user, reservation);

        // A second confirmation line, as a double-decrement bug would leave behind.
        jdbc.sql("""
                INSERT INTO stock_movements (product_id, reservation_id, type, delta_total, delta_available, delta_reserved,
                                             total_after, available_after, reserved_after)
                VALUES (:product, :reservation, 'CONFIRMED', -1, 0, -1, 3, 4, -1)
                """)
                .param("product", product)
                .param("reservation", reservation)
                .update();

        assertThat(rulesViolatedBy(product)).contains("RESERVATION_NOT_SETTLED_EXACTLY_ONCE", "COUNTERS_DO_NOT_MATCH_LEDGER");
    }

    @Test
    @DisplayName("Detects a gap in the ledger chain, even when the totals still add up")
    void detectsBrokenChain() {
        UUID product = newProduct(5);

        jdbc.sql("""
                INSERT INTO stock_movements (product_id, type, delta_total, delta_available, delta_reserved,
                                             total_after, available_after, reserved_after)
                VALUES (:product, 'STOCK_IN', 0, 0, 0, 7, 7, 0)
                """)
                .param("product", product)
                .update();

        assertThat(rulesViolatedBy(product)).containsExactly("LEDGER_CHAIN_BROKEN");
    }

    @Test
    @DisplayName("A report is scoped to the requested product and available to administrators over the API")
    void reportIsScopedAndExposed() {
        UUID corrupted = newProduct(5);
        UUID healthy = newProduct(5);
        jdbc.sql("UPDATE stock_levels SET total_quantity = total_quantity + 1, available_quantity = available_quantity + 1 WHERE product_id = :id")
                .param("id", corrupted)
                .update();

        Api.Response forCorrupted = api.get("/api/v1/admin/consistency?productId=" + corrupted, admin().token());
        Api.Response forHealthy = api.get("/api/v1/admin/consistency?productId=" + healthy, admin().token());

        assertThat(forCorrupted.json().get("consistent").asBoolean()).isFalse();
        assertThat(forCorrupted.json().get("violations").get(0).get("productId").asString()).isEqualTo(corrupted.toString());
        assertThat(forHealthy.json().get("consistent").asBoolean()).isTrue();
    }

    private java.util.List<String> rulesViolatedBy(UUID product) {
        ConsistencyReport report = consistency.check(product);
        assertThat(report.consistent()).isFalse();
        assertThat(report.violations()).allSatisfy(violation -> assertThat(violation.productId()).isEqualTo(product));
        return report.violations().stream().map(Violation::rule).toList();
    }
}
