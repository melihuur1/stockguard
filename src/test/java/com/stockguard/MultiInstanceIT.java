package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.stockguard.support.Api;
import com.stockguard.support.AppInstance;
import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.ExternalDatabase;
import com.stockguard.support.ExternalDatabase.User;
import com.stockguard.support.IntegrationTest.Stock;
import com.stockguard.support.TestPostgres;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Scenario 8. Two separate JVM processes share one fresh PostgreSQL database and nothing else. The test client
 * talks to both, alternating request by request, and the instance header proves who answered.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Scenario 8: two application instances in separate JVMs")
class MultiInstanceIT {

    private ExternalDatabase database;
    private AppInstance first;
    private AppInstance second;
    private String adminToken;

    @BeforeAll
    void startBothInstancesAtOnce() {
        String url = TestPostgres.createDatabase("multi");
        database = new ExternalDatabase(url, TestPostgres.username(), TestPostgres.password());
        // Launched together against an empty database: both try to migrate and to seed the admin.
        first = AppInstance.launch("instance-a", url, TestPostgres.username(), TestPostgres.password(), Map.of());
        second = AppInstance.launch("instance-b", url, TestPostgres.username(), TestPostgres.password(), Map.of());
        first.awaitHealthy();
        second.awaitHealthy();
        adminToken = database.adminToken(first.api());
    }

    @AfterAll
    void stopInstances() {
        first.close();
        second.close();
    }

    @Test
    @DisplayName("Both instances survive racing through migrations and admin seeding on an empty database")
    void concurrentStartupIsSafe() throws Exception {
        assertThat(first.isAlive()).isTrue();
        assertThat(second.isAlive()).isTrue();
        assertThat(database.count("SELECT COUNT(*) FROM flyway_schema_history WHERE success = false")).isZero();
        assertThat(database.count("SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL")).isPositive();
        assertThat(database.count("SELECT COUNT(*) FROM app_users WHERE username = 'admin'")).isEqualTo(1);
    }

    @Test
    @DisplayName("A token issued by one instance is accepted by the other")
    void tokensWorkAcrossInstances() {
        assertThat(second.api().get("/api/v1/audit-events", database.adminToken(first.api())).status()).isEqualTo(200);
        assertThat(first.api().get("/api/v1/audit-events", database.adminToken(second.api())).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Stock 1, 100 users across two JVMs: exactly 1 succeeds")
    void oneUnitAcrossInstances() throws Exception {
        assertRace(1, 100);
    }

    @Test
    @DisplayName("Stock 10, 100 users across two JVMs: exactly 10 succeed")
    void tenUnitsAcrossInstances() throws Exception {
        assertRace(10, 100);
    }

    private void assertRace(int stock, int requests) throws Exception {
        UUID product = database.newProduct(first.api(), adminToken, stock);
        List<User> users = database.newUsers(requests);

        Concurrent.Run<Api.Response> run = Concurrent.run(requests, i -> instance(i).api().post(
                "/api/v1/reservations", users.get(i).token(),
                Map.of("productId", product, "quantity", 1), Map.of("Idempotency-Key", "race-" + i)));

        Map<String, Long> perInstance = run.results().stream()
                .collect(Collectors.groupingBy(r -> r.header("X-StockGuard-Instance"), Collectors.counting()));
        Map<Integer, Long> statuses = run.results().stream()
                .collect(Collectors.groupingBy(Api.Response::status, Collectors.counting()));

        assertThat(perInstance).containsOnly(entry("instance-a", requests / 2L), entry("instance-b", requests / 2L));
        assertThat(run.peakInFlight()).isGreaterThanOrEqualTo(requests / 2);
        assertThat(statuses).containsOnly(entry(201, (long) stock), entry(409, (long) requests - stock));
        assertThat(database.stock(product)).isEqualTo(new Stock(stock, 0, stock));
        assertThat(database.count("SELECT COUNT(*) FROM reservations WHERE product_id = ?", product)).isEqualTo(stock);
        assertConsistent(product);

        ConcurrencyReport.add("Scenario 8: stock " + stock + ", " + requests + " users, 2 JVMs",
                "per instance=" + perInstance + ", 201=" + statuses.get(201) + ", 409=" + statuses.get(409)
                        + ", stock=" + database.stock(product) + ", " + run.durationMillis() + " ms");
    }

    @Test
    @DisplayName("One idempotency key sent to both instances at once creates one reservation")
    void idempotencyAcrossInstances() throws Exception {
        UUID product = database.newProduct(first.api(), adminToken, 5);
        User user = database.newUsers(1).getFirst();

        Concurrent.Run<Api.Response> run = Concurrent.run(60, i -> instance(i).api().post(
                "/api/v1/reservations", user.token(),
                Map.of("productId", product, "quantity", 1), Map.of("Idempotency-Key", "shared-across-instances")));

        Map<Integer, Long> statuses = run.results().stream()
                .collect(Collectors.groupingBy(Api.Response::status, Collectors.counting()));
        assertThat(statuses).containsOnly(entry(201, 1L), entry(200, 59L));
        assertThat(run.results()).extracting(r -> r.text("id")).containsOnly(run.results().getFirst().text("id"));
        assertThat(database.stock(product)).isEqualTo(new Stock(5, 4, 1));
        assertConsistent(product);

        ConcurrencyReport.add("Scenario 8: 60 duplicates of one key, 2 JVMs",
                "201=1, replays=59, stock=" + database.stock(product));
    }

    @Test
    @DisplayName("A reservation confirmed on both instances at once is settled once")
    void settlementAcrossInstances() throws Exception {
        UUID product = database.newProduct(first.api(), adminToken, 5);
        User user = database.newUsers(1).getFirst();
        String id = first.api().post("/api/v1/reservations", user.token(),
                Map.of("productId", product, "quantity", 2), Map.of("Idempotency-Key", "settle")).text("id");

        Concurrent.Run<Api.Response> run = Concurrent.run(40, i -> instance(i).api().post("/api/v1/reservations/" + id + "/confirm", user.token(), null));

        Map<Integer, Long> statuses = run.results().stream()
                .collect(Collectors.groupingBy(Api.Response::status, Collectors.counting()));
        assertThat(statuses).containsOnly(entry(200, 1L), entry(409, 39L));
        assertThat(database.stock(product)).isEqualTo(new Stock(3, 3, 0));
        assertConsistent(product);
    }

    @Test
    @DisplayName("Both instances' real schedulers release expired reservations, each exactly once")
    void schedulersOnBothInstancesExpireWithoutDoubleRelease() throws Exception {
        int reservations = 150;
        UUID product = database.newProduct(first.api(), adminToken, reservations);
        List<User> users = database.newUsers(reservations);

        List<Api.Response> created = Concurrent.run(reservations, i -> instance(i).api().post(
                "/api/v1/reservations", users.get(i).token(),
                Map.of("productId", product, "quantity", 1, "ttlSeconds", 1), Map.of("Idempotency-Key", "ttl-" + i))).results();
        assertThat(created).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(database.stock(product).reserved()).isEqualTo(reservations);

        // No test code triggers the sweep: only the scheduled jobs inside the two instances can release these.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && database.stock(product).reserved() > 0) {
            Thread.sleep(100);
        }

        assertThat(database.stock(product)).isEqualTo(new Stock(reservations, reservations, 0));
        assertThat(database.count("SELECT COUNT(*) FROM reservations WHERE product_id = ? AND status = 'EXPIRED'", product)).isEqualTo(reservations);
        assertThat(database.count("SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND type = 'EXPIRY_RELEASED'", product)).isEqualTo(reservations);
        assertConsistent(product);

        ConcurrencyReport.add("Scenario 8: " + reservations + " expiries, schedulers on 2 JVMs",
                "released exactly once each by instance: " + expiriesPerInstance(product) + ", stock=" + database.stock(product));
    }

    private Map<String, Long> expiriesPerInstance(UUID product) throws Exception {
        Map<String, Long> result = new java.util.TreeMap<>();
        for (AppInstance instance : List.of(first, second)) {
            result.put(instance.id(), database.count("""
                    SELECT COUNT(*) FROM audit_events
                     WHERE event_type = 'RESERVATION_EXPIRED' AND instance_id = ? AND details ->> 'productId' = ?
                    """, instance.id(), product.toString()));
        }
        return result;
    }

    @Test
    @DisplayName("Simulation is disabled unless explicitly enabled")
    void simulationIsOffByDefault() {
        Api.Response response = first.api().post("/api/v1/simulations/reservations", adminToken,
                Map.of("initialStock", 1, "concurrentRequests", 1, "quantityPerRequest", 1));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("The application refuses to start with a JWT secret that is too short")
    void weakSecretPreventsStartup() {
        String url = TestPostgres.createDatabase("weak");
        try (AppInstance weak = AppInstance.launch("weak-secret", url, TestPostgres.username(), TestPostgres.password(),
                Map.of("STOCKGUARD_JWT_SECRET", "too-short"))) {
            assertThat(weak.awaitExit(Duration.ofSeconds(60))).isNotZero();
        }
    }

    private AppInstance instance(int index) {
        return index % 2 == 0 ? first : second;
    }

    private void assertConsistent(UUID product) throws Exception {
        List<String> violations = new ArrayList<>();
        var report = first.api().get("/api/v1/admin/consistency?productId=" + product, adminToken).json();
        report.get("violations").forEach(violation -> violations.add(violation.toString()));
        assertThat(violations).isEmpty();
        assertThat(report.get("consistent").asBoolean()).isTrue();
    }
}
