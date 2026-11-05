package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockguard.support.Api;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeadlockAndContentionIT extends IntegrationTest {

    @Test
    @DisplayName("A real deadlock: PostgreSQL aborts the API transaction and the server-side retry still succeeds")
    void deadlockVictimIsRetried() throws Exception {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 2);
        long deadlocksBefore = deadlockCount();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                Connection opposing = dataSource.getConnection()) {
            opposing.setAutoCommit(false);

            // The opposing transaction takes the locks in the reverse order: stock row first.
            execute(opposing, "UPDATE stock_levels SET updated_at = now() WHERE product_id = ?", product);

            // The API call locks the reservation row, then blocks on the stock row held above.
            Future<Api.Response> confirm = executor.submit(() -> confirm(user, reservation));
            waitUntilABackendBlocksOnALock();

            // Now the opposing transaction reaches for the reservation row: the cycle is closed. The API backend
            // has been waiting longest, so its deadlock_timeout fires first and PostgreSQL aborts it as the victim.
            boolean opposingSurvived = true;
            try {
                execute(opposing, "SELECT 1 FROM reservations WHERE id = ? FOR UPDATE", reservation);
                opposing.commit();
            } catch (SQLException ex) {
                assertThat(ex.getSQLState()).as("deadlock_detected").isEqualTo("40P01");
                opposing.rollback();
                opposingSurvived = false;
            }

            Api.Response result = confirm.get(30, TimeUnit.SECONDS);

            assertThat(opposingSurvived)
                    .as("the opposing transaction survived, so the API transaction was the victim and was retried")
                    .isTrue();
            assertThat(result.status()).as(result.toString()).isEqualTo(200);
            assertThat(result.text("status")).isEqualTo("CONFIRMED");
        }

        assertThat(stock(product)).as("no partial effects from the aborted attempt").isEqualTo(new Stock(3, 3, 0));
        assertConsistent(product);
        assertDeadlockCounted(deadlocksBefore);

        ConcurrencyReport.add("Forced deadlock against a confirm",
                "PostgreSQL detected it (40P01), victim retried by the server, client saw 200, stock=" + stock(product));
    }

    @Test
    @DisplayName("A row locked for too long ends in 503 with Retry-After, and the same key then succeeds")
    void lockTimeoutBecomesRetryableError() throws Exception {
        UUID product = newProduct(5);
        TestUser user = newUser();

        Api.Response blocked;
        long elapsedMillis;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            execute(blocker, "UPDATE stock_levels SET updated_at = now() WHERE product_id = ?", product);

            long startedAt = System.nanoTime();
            blocked = reserve(user, product, 1, "blocked-key");
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            blocker.rollback();
        }

        assertThat(blocked.status()).as(blocked.toString()).isEqualTo(503);
        assertThat(blocked.code()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        assertThat(blocked.header("Retry-After")).isEqualTo("1");
        assertThat(elapsedMillis).as("3 attempts at a 3 s lock_timeout").isGreaterThanOrEqualTo(9_000);
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
        assertThat(reservationRows(product)).isZero();

        Api.Response retry = reserve(user, product, 1, "blocked-key");
        assertThat(retry.status()).isEqualTo(201);
        assertConsistent(product);

        ConcurrencyReport.add("Stock row locked beyond lock_timeout",
                "503 + Retry-After after " + elapsedMillis + " ms (3 attempts), nothing written, retry with same key: 201");
    }

    private static void execute(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.execute();
        }
    }

    private void waitUntilABackendBlocksOnALock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long blocked = jdbc.sql("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")
                    .query(Long.class)
                    .single();
            if (blocked > 0) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("The API call never blocked on the stock row");
    }

    private long deadlockCount() {
        return jdbc.sql("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()").query(Long.class).single();
    }

    /** Statistics are published asynchronously, so this waits for the counter to catch up. */
    private void assertDeadlockCounted(long before) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (deadlockCount() > before) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("PostgreSQL did not record a deadlock, so the test did not exercise one");
    }
}
