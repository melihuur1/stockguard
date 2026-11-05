package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.TestPostgres;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The experiment behind the design decision. The same workload runs against each locking strategy on a scratch
 * table in a separate database, through a 20 connection pool like the application's. This is deliberately test
 * code: production has one code path, and the alternatives exist only to measure them.
 */
class StrategyComparisonIT {

    private static HikariDataSource pool;

    @BeforeAll
    static void openPool() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(TestPostgres.createDatabase("strategies"));
        config.setUsername(TestPostgres.username());
        config.setPassword(TestPostgres.password());
        config.setMaximumPoolSize(20);
        pool = new HikariDataSource(config);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stock (id INT PRIMARY KEY, available INT NOT NULL, version BIGINT NOT NULL DEFAULT 0)");
        }
    }

    @AfterAll
    static void closePool() {
        pool.close();
    }

    private static void setStock(int available) throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE stock");
            statement.execute("INSERT INTO stock (id, available) VALUES (1, " + available + ")");
        }
    }

    private static int readAvailable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT available FROM stock WHERE id = 1")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** What the application does: one conditional statement. */
    private static Attempt atomicUpdate() throws SQLException {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement()) {
            boolean sold = statement.executeUpdate("UPDATE stock SET available = available - 1 WHERE id = 1 AND available >= 1") == 1;
            return new Attempt(sold, 1);
        }
    }

    private static Attempt pessimisticLock() throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            int available;
            try (ResultSet rows = statement.executeQuery("SELECT available FROM stock WHERE id = 1 FOR UPDATE")) {
                rows.next();
                available = rows.getInt(1);
            }
            boolean sold = available >= 1;
            if (sold) {
                statement.executeUpdate("UPDATE stock SET available = " + (available - 1) + " WHERE id = 1");
            }
            connection.commit();
            return new Attempt(sold, 1);
        }
    }

    private static Attempt optimisticLock(int maxAttempts) throws SQLException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
                int available;
                long version;
                try (ResultSet rows = statement.executeQuery("SELECT available, version FROM stock WHERE id = 1")) {
                    rows.next();
                    available = rows.getInt(1);
                    version = rows.getLong(2);
                }
                if (available < 1) {
                    return new Attempt(false, attempt);
                }
                int updated = statement.executeUpdate("UPDATE stock SET available = " + (available - 1)
                        + ", version = version + 1 WHERE id = 1 AND version = " + version);
                if (updated == 1) {
                    return new Attempt(true, attempt);
                }
            }
        }
        return new Attempt(false, maxAttempts);
    }

    /** The bug everything else exists to prevent: check, then act. */
    private static Attempt naiveReadThenWrite() throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            int available = readAvailable(connection);
            boolean sold = available >= 1;
            if (sold) {
                statement.executeUpdate("UPDATE stock SET available = " + (available - 1) + " WHERE id = 1");
            }
            return new Attempt(sold, 1);
        }
    }

    private Result measure(String name, int stock, int requests, Concurrent.Task<Attempt> strategy) throws SQLException {
        setStock(stock);
        Concurrent.Run<Attempt> run = Concurrent.run(requests, strategy);
        int sold = (int) run.results().stream().filter(Attempt::sold).count();
        int attempts = run.results().stream().mapToInt(Attempt::attempts).sum();
        Result result = new Result(name, stock, requests, sold, attempts, run.durationMillis());
        ConcurrencyReport.add("Strategy " + name + ": stock " + stock + ", " + requests + " requests",
                "sold=" + sold + (sold > stock ? " **OVERSOLD**" : "")
                        + ", transactions=" + attempts + ", " + run.durationMillis() + " ms");
        return result;
    }

    @Test
    @DisplayName("Atomic UPDATE and pessimistic lock sell exactly the stock with no retries")
    void correctStrategies() throws SQLException {
        for (int[] workload : List.of(new int[] {1, 100}, new int[] {50, 200})) {
            int stock = workload[0];
            int requests = workload[1];

            Result atomic = measure("atomic UPDATE", stock, requests, i -> atomicUpdate());
            Result pessimistic = measure("SELECT FOR UPDATE", stock, requests, i -> pessimisticLock());

            assertThat(atomic.sold()).isEqualTo(stock);
            assertThat(atomic.attempts()).isEqualTo(requests);
            assertThat(pessimistic.sold()).isEqualTo(stock);
            assertThat(pessimistic.attempts()).isEqualTo(requests);
        }
    }

    @Test
    @DisplayName("Optimistic locking never oversells, but under contention it refuses sales or burns retries")
    void optimisticLockUnderContention() throws SQLException {
        int stock = 50;
        int requests = 200;

        Result noRetry = measure("optimistic, no retry", stock, requests, i -> optimisticLock(1));
        Result withRetries = measure("optimistic, 3 attempts", stock, requests, i -> optimisticLock(3));
        Result manyRetries = measure("optimistic, 50 attempts", stock, requests, i -> optimisticLock(50));

        assertThat(noRetry.sold()).as("never oversells").isLessThanOrEqualTo(stock);
        assertThat(withRetries.sold()).isLessThanOrEqualTo(stock);
        assertThat(manyRetries.sold()).isLessThanOrEqualTo(stock);
        assertThat(noRetry.sold()).as("without retries it turns customers away while stock is left").isLessThan(stock);
    }

    @Test
    @DisplayName("Control: a naive read-then-write oversells, which proves this harness can detect the bug")
    void naiveStrategyOversells() throws Exception {
        setStock(1);
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);

        // Forces the fatal interleaving deterministically: both read 1, then both write 0.
        List<Attempt> results = Concurrent.run(2, i -> {
            try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
                int available = readAvailable(connection);
                bothHaveRead.await();
                boolean sold = available >= 1;
                if (sold) {
                    statement.executeUpdate("UPDATE stock SET available = " + (available - 1) + " WHERE id = 1");
                }
                return new Attempt(sold, 1);
            }
        }).results();

        assertThat(results.stream().filter(Attempt::sold).count()).as("one unit sold twice").isEqualTo(2);

        Result underLoad = measure("naive read-then-write (control)", 1, 100, i -> naiveReadThenWrite());
        assertThat(underLoad.sold()).as("real load oversells without any forced interleaving").isGreaterThan(1);
    }

    @Test
    @DisplayName("Control: a JVM lock per instance does not help, because two instances hold two different locks")
    void synchronizedDoesNotProtectAcrossInstances() throws Exception {
        setStock(1);
        Object lockOfInstanceA = new Object();
        Object lockOfInstanceB = new Object();
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);

        List<Attempt> results = Concurrent.run(2, i -> {
            // Each "instance" correctly synchronizes on its own monitor, as two separate JVMs would.
            synchronized (i == 0 ? lockOfInstanceA : lockOfInstanceB) {
                try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
                    int available = readAvailable(connection);
                    bothHaveRead.await();
                    boolean sold = available >= 1;
                    if (sold) {
                        try (PreparedStatement update = connection.prepareStatement("UPDATE stock SET available = ? WHERE id = 1")) {
                            update.setInt(1, available - 1);
                            update.executeUpdate();
                        }
                    }
                    return new Attempt(sold, 1);
                }
            }
        }).results();

        long sold = results.stream().filter(Attempt::sold).count();
        assertThat(sold).as("both instances entered their critical section and sold the same unit").isEqualTo(2);
        ConcurrencyReport.add("Control: synchronized on two instances, stock 1", "sold=" + sold + " **OVERSOLD**, so JVM locks are not sufficient");
    }

    private record Attempt(boolean sold, int attempts) {
    }

    private record Result(String name, int stock, int requests, int sold, int attempts, long durationMillis) {
    }
}
