package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.inventory.StockLedger;
import com.stockguard.reservation.ReservationExpiryService;
import com.stockguard.support.Api;
import com.stockguard.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Failures are injected at the deepest point of the real transaction: a temporary trigger makes PostgreSQL fail
 * the ledger insert, which happens after the stock row has been changed. No mocks are involved.
 */
class TransactionRollbackIT extends IntegrationTest {

    @Autowired
    private ReservationExpiryService expiry;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private AuditTrail audit;

    private void failLedgerInsertsFor(UUID product) {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_inject_failure() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected failure';
                END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_inject_failure ON stock_movements").update();
        jdbc.sql("CREATE TRIGGER test_inject_failure BEFORE INSERT ON stock_movements FOR EACH ROW "
                + "WHEN (NEW.product_id = '" + product + "') EXECUTE FUNCTION test_inject_failure()").update();
    }

    @AfterEach
    void removeInjectedFailure() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_inject_failure ON stock_movements").update();
    }

    @Test
    @DisplayName("Scenario 7: a failure after the stock update rolls everything back, and the key stays usable")
    void failedReservationChangesNothing() {
        UUID product = newProduct(10);
        TestUser user = newUser();
        failLedgerInsertsFor(product);

        Api.Response failed = reserve(user, product, 3, "rollback-key");

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.rawBody()).as("no internals are exposed").doesNotContain("injected", "stock_movements", "SQL");
        assertThat(stock(product)).as("the stock update was undone").isEqualTo(new Stock(10, 10, 0));
        assertThat(reservationRows(product)).as("the reservation insert was undone").isZero();
        assertThat(auditEventsMentioning(product)).as("the audit event was undone").isZero();

        removeInjectedFailure();

        // Had the key survived the rollback, this retry would be a 200 replay of a reservation that does not exist.
        Api.Response retry = reserve(user, product, 3, "rollback-key");
        assertThat(retry.status()).isEqualTo(201);
        assertThat(stock(product)).isEqualTo(new Stock(10, 7, 3));
        assertConsistent(product);
    }

    private long auditEventsMentioning(UUID product) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE details ->> 'productId' = :id")
                .param("id", product.toString())
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("A failed confirmation leaves the reservation PENDING and the stock untouched")
    void failedConfirmChangesNothing() {
        UUID product = newProduct(10);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);
        failLedgerInsertsFor(product);

        assertThat(confirm(user, reservation).status()).isEqualTo(500);

        assertThat(statusOf(reservation)).isEqualTo("PENDING");
        assertThat(stock(product)).isEqualTo(new Stock(10, 8, 2));

        removeInjectedFailure();
        assertThat(confirm(user, reservation).status()).isEqualTo(200);
        assertThat(stock(product)).isEqualTo(new Stock(8, 8, 0));
        assertConsistent(product);
    }

    @Test
    @DisplayName("A failed cancellation leaves the reservation PENDING and the stock untouched")
    void failedCancelChangesNothing() {
        UUID product = newProduct(10);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);
        failLedgerInsertsFor(product);

        assertThat(cancel(user, reservation).status()).isEqualTo(500);

        assertThat(statusOf(reservation)).isEqualTo("PENDING");
        assertThat(stock(product)).isEqualTo(new Stock(10, 8, 2));
        removeInjectedFailure();
        assertConsistent(product);
    }

    @Test
    @DisplayName("A failed expiry batch rolls back whole and is picked up again by the next sweep")
    void failedExpiryRollsBackAndRetries() {
        UUID product = newProduct(10);
        UUID reservation = reserveOk(newUser(), product, 2);
        makeOverdue(reservation);
        failLedgerInsertsFor(product);

        assertThatThrownBy(() -> {
            while (expiry.expireDueBatch(100) > 0) {
                // Keeps sweeping until the batch containing the failing row is reached.
            }
        }).hasMessageContaining("injected failure");

        assertThat(statusOf(reservation)).isEqualTo("PENDING");
        assertThat(stock(product)).isEqualTo(new Stock(10, 8, 2));

        removeInjectedFailure();
        while (expiry.expireDueBatch(100) > 0) {
            // Drains the backlog now that the failure is gone.
        }

        assertThat(statusOf(reservation)).isEqualTo("EXPIRED");
        assertThat(stock(product)).isEqualTo(new Stock(10, 10, 0));
        assertConsistent(product);
    }

    @Test
    @DisplayName("A failed stock receipt adds nothing")
    void failedStockInChangesNothing() {
        UUID product = newProduct(5);
        failLedgerInsertsFor(product);

        Api.Response failed = api.post("/api/v1/products/" + product + "/stock", admin().token(), Map.of("quantity", 100));

        assertThat(failed.status()).isEqualTo(500);
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
    }

    @Test
    @DisplayName("Writes to stock and audit cannot happen outside a business transaction")
    void gatewaysRefuseToRunWithoutATransaction() {
        UUID product = newProduct(5);
        UUID user = newUser().id();

        assertThatThrownBy(() -> ledger.tryReserve(product, 1, UUID.randomUUID(), user))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> audit.record(AuditEventType.STOCK_RECEIVED, user, "PRODUCT", product, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
    }

    @Test
    @DisplayName("The database refuses negative or inconsistent stock even if the application tried to write it")
    void checkConstraintsRejectBadStock() {
        UUID product = newProduct(1);

        assertThatThrownBy(() -> updateStock(product, "available_quantity = -1, total_quantity = -1 + reserved_quantity"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_stock_available_non_negative");
        assertThatThrownBy(() -> updateStock(product, "reserved_quantity = reserved_quantity - 1, total_quantity = total_quantity - 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_stock_reserved_non_negative");
        assertThatThrownBy(() -> updateStock(product, "total_quantity = total_quantity + 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_stock_total_is_sum");
        assertThat(stock(product)).isEqualTo(new Stock(1, 1, 0));
    }

    private void updateStock(UUID product, String assignments) {
        jdbc.sql("UPDATE stock_levels SET " + assignments + " WHERE product_id = :id").param("id", product).update();
    }

    @Test
    @DisplayName("The database refuses to change a settled reservation")
    void triggerProtectsTerminalState() {
        UUID product = newProduct(1);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 1);
        confirm(user, reservation);

        assertThatThrownBy(() -> jdbc.sql("UPDATE reservations SET status = 'CANCELLED' WHERE id = :id")
                .param("id", reservation)
                .update())
                .hasMessageContaining("already CONFIRMED");
        assertThat(statusOf(reservation)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("The database refuses to change a reservation's product, user or quantity")
    void triggerProtectsImmutableFields() {
        UUID product = newProduct(5);
        UUID reservation = reserveOk(newUser(), product, 1);

        assertThatThrownBy(() -> jdbc.sql("UPDATE reservations SET quantity = 2 WHERE id = :id")
                .param("id", reservation)
                .update())
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("The ledger and audit trail are append-only at database level")
    void historyCannotBeRewritten() {
        UUID product = newProduct(1);

        assertThatThrownBy(() -> jdbc.sql("UPDATE stock_movements SET delta_total = 99 WHERE product_id = :id")
                .param("id", product).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM stock_movements WHERE product_id = :id")
                .param("id", product).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events WHERE entity_id = :id")
                .param("id", product).update()).hasMessageContaining("append-only");
    }
}
