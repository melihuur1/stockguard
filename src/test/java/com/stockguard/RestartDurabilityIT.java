package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.stockguard.support.Api;
import com.stockguard.support.AppInstance;
import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.ExternalDatabase;
import com.stockguard.support.ExternalDatabase.User;
import com.stockguard.support.IntegrationTest.Stock;
import com.stockguard.support.TestPostgres;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("Scenario 10: data survives server restarts and crashes")
class RestartDurabilityIT {

    private record Environment(String url, ExternalDatabase database) {
    }

    private static Environment freshDatabase(String prefix) {
        String url = TestPostgres.createDatabase(prefix);
        return new Environment(url, new ExternalDatabase(url, TestPostgres.username(), TestPostgres.password()));
    }

    private static AppInstance launch(String id, String url) {
        return AppInstance.launch(id, url, TestPostgres.username(), TestPostgres.password(), Map.of());
    }

    @Test
    @DisplayName("After kill -9 and restart, committed reservations are intact and can still be settled")
    void committedDataSurvivesApplicationCrash() throws Exception {
        Environment env = freshDatabase("restart");
        ExternalDatabase database = env.database();
        List<User> users;
        UUID product;
        List<String> reservationIds = new ArrayList<>();
        Stock beforeCrash;

        try (AppInstance instance = launch("before-crash", env.url()).awaitHealthy()) {
            Api api = instance.api();
            String admin = database.adminToken(api);
            product = database.newProduct(api, admin, 10);
            users = database.newUsers(3);
            for (int i = 0; i < 3; i++) {
                Api.Response created = api.post("/api/v1/reservations", users.get(i).token(),
                        Map.of("productId", product, "quantity", 2), Map.of("Idempotency-Key", "before-crash-" + i));
                assertThat(created.status()).isEqualTo(201);
                reservationIds.add(created.text("id"));
            }
            assertThat(api.post("/api/v1/reservations/" + reservationIds.get(0) + "/confirm", users.get(0).token(), null).status()).isEqualTo(200);
            beforeCrash = database.stock(product);
            assertThat(beforeCrash).isEqualTo(new Stock(8, 4, 4));

            instance.kill();
        }

        try (AppInstance restarted = launch("after-crash", env.url()).awaitHealthy()) {
            Api api = restarted.api();
            String admin = database.adminToken(api);

            assertThat(database.stock(product)).isEqualTo(beforeCrash);
            assertThat(api.get("/api/v1/reservations/" + reservationIds.get(0), users.get(0).token()).text("status")).isEqualTo("CONFIRMED");
            assertThat(api.get("/api/v1/reservations/" + reservationIds.get(1), users.get(1).token()).text("status")).isEqualTo("PENDING");

            // Work begun before the crash can be finished after it.
            assertThat(api.post("/api/v1/reservations/" + reservationIds.get(1) + "/confirm", users.get(1).token(), null).status()).isEqualTo(200);
            assertThat(api.post("/api/v1/reservations/" + reservationIds.get(2) + "/cancel", users.get(2).token(), null).status()).isEqualTo(200);
            assertThat(database.stock(product)).isEqualTo(new Stock(6, 6, 0));

            // An idempotency key remembered across the crash still prevents a duplicate.
            Api.Response replay = api.post("/api/v1/reservations", users.get(0).token(),
                    Map.of("productId", product, "quantity", 2), Map.of("Idempotency-Key", "before-crash-0"));
            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.text("id")).isEqualTo(reservationIds.get(0));

            assertConsistent(api, admin, product);
        }

        ConcurrencyReport.add("Scenario 10: kill -9, then restart", "all committed data intact, reservations settled after restart, key still deduplicates");
    }

    @Test
    @DisplayName("Killing an instance mid-traffic leaves no half-finished work, and retrying on the survivor sells each unit once")
    void crashDuringTrafficKeepsEveryGuarantee() throws Exception {
        Environment env = freshDatabase("crash");
        ExternalDatabase database = env.database();
        int stock = 200;
        int requests = 400;
        int acknowledgementsBeforeKill = 30;

        try (AppInstance doomed = launch("doomed", env.url());
                AppInstance survivor = launch("survivor", env.url())) {
            doomed.awaitHealthy();
            survivor.awaitHealthy();
            String admin = database.adminToken(survivor.api());
            warmUp(doomed, database, admin);
            warmUp(survivor, database, admin);
            UUID product = database.newProduct(survivor.api(), admin, stock);
            List<User> users = database.newUsers(requests);

            // Half the traffic goes to the instance about to die. The kill is triggered by the responses seen, not
            // by a timer: it fires once the doomed instance has demonstrably been serving this traffic, while the
            // rest of the requests, spread over a longer window, are in flight or still to come.
            AtomicInteger answeredByDoomed = new AtomicInteger();
            List<Attempt> attempts = Concurrent.run(requests + 1, i -> {
                if (i == requests) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (answeredByDoomed.get() < acknowledgementsBeforeKill && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    doomed.kill();
                    return null;
                }
                Thread.sleep(ThreadLocalRandom.current().nextInt(1500));
                AppInstance target = i % 2 == 0 ? doomed : survivor;
                Attempt attempt = send(target, users.get(i), product, i);
                if (target == doomed && attempt.response() != null) {
                    answeredByDoomed.incrementAndGet();
                }
                return attempt;
            }).results().stream().filter(java.util.Objects::nonNull).toList();

            long lost = attempts.stream().filter(attempt -> attempt.response() == null).count();
            assertThat(answeredByDoomed.get()).as("the doomed instance was serving traffic when killed").isGreaterThanOrEqualTo(acknowledgementsBeforeKill);
            assertThat(lost).as("the kill must really interrupt traffic for this test to mean anything").isPositive();

            // Atomicity: whatever was in flight at the kill either happened completely or not at all.
            assertConsistent(survivor.api(), admin, product);
            Stock afterCrash = database.stock(product);
            assertThat(afterCrash.total()).isEqualTo(stock);
            assertThat(afterCrash.available() + afterCrash.reserved()).isEqualTo(stock);

            // Durability: every reservation the client saw acknowledged is really stored.
            Set<String> acknowledged = new HashSet<>();
            for (Attempt attempt : attempts) {
                if (attempt.response() != null && attempt.response().status() == 201) {
                    acknowledged.add(attempt.response().text("id"));
                }
            }
            for (String id : acknowledged) {
                assertThat(database.count("SELECT COUNT(*) FROM reservations WHERE id = CAST(? AS uuid)", id)).isEqualTo(1);
            }

            // Exactly-once: clients that got no answer retry on the survivor with the same key. A request that did
            // commit just before the crash comes back as a replay and takes no second unit.
            Concurrent.Run<Attempt> retries = Concurrent.run((int) lost, i -> {
                Attempt attempt = attempts.stream().filter(a -> a.response() == null).toList().get(i);
                return send(survivor, attempt.user(), product, attempt.index());
            });
            assertThat(retries.results()).allSatisfy(attempt -> assertThat(attempt.response().status()).isIn(200, 201, 409));

            long replays = retries.results().stream().filter(a -> a.response().status() == 200).count();
            long distinct = database.count("SELECT COUNT(*) FROM reservations WHERE product_id = ?", product);

            // Every request has now had an answer and there were more requests than stock, so all of it is sold.
            assertThat(distinct).as("every unit sold, and none twice").isEqualTo(stock);
            assertThat(database.stock(product)).isEqualTo(new Stock(stock, 0, stock));
            assertConsistent(survivor.api(), admin, product);

            ConcurrencyReport.add("Scenario 10: kill -9 during traffic (" + requests + " requests, stock " + stock + ")",
                    "answered by doomed instance before kill=" + answeredByDoomed.get() + ", requests cut off=" + lost
                            + ", committed but unanswered, recovered by replay=" + replays
                            + ", final reservations=" + distinct + ", ledger consistent");
        }
    }

    /** A cold JVM needs hundreds of milliseconds for its first requests, which would hide it from the race. */
    private static void warmUp(AppInstance instance, ExternalDatabase database, String admin) throws Exception {
        UUID product = database.newProduct(instance.api(), admin, 50);
        List<User> users = database.newUsers(50);
        Concurrent.run(50, i -> send(instance, users.get(i), product, -1 - i));
    }

    private static Attempt send(AppInstance instance, User user, UUID product, int index) {
        try {
            Api.Response response = instance.api().post("/api/v1/reservations", user.token(),
                    Map.of("productId", product, "quantity", 1), Map.of("Idempotency-Key", "crash-" + index));
            return new Attempt(index, user, response);
        } catch (UncheckedIOException ex) {
            return new Attempt(index, user, null);
        }
    }

    private record Attempt(int index, User user, Api.Response response) {
    }

    @Test
    @DisplayName("A reservation that expires while the application is down is released after restart")
    void expiryCatchesUpAfterDowntime() throws Exception {
        Environment env = freshDatabase("downtime");
        ExternalDatabase database = env.database();
        UUID product;

        try (AppInstance instance = launch("before-downtime", env.url()).awaitHealthy()) {
            Api api = instance.api();
            product = database.newProduct(api, database.adminToken(api), 4);
            User user = database.newUsers(1).getFirst();
            Api.Response created = api.post("/api/v1/reservations", user.token(),
                    Map.of("productId", product, "quantity", 3, "ttlSeconds", 30), Map.of("Idempotency-Key", "downtime"));
            assertThat(created.status()).isEqualTo(201);
            instance.kill();
            // The deadline passes while nothing is running.
            database.execute("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE product_id = ?", product);
        }
        assertThat(database.stock(product)).as("held while the application is down").isEqualTo(new Stock(4, 1, 3));

        try (AppInstance restarted = launch("after-downtime", env.url()).awaitHealthy()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && database.stock(product).reserved() > 0) {
                Thread.sleep(100);
            }

            assertThat(database.stock(product)).isEqualTo(new Stock(4, 4, 0));
            assertThat(database.count("SELECT COUNT(*) FROM reservations WHERE product_id = ? AND status = 'EXPIRED'", product)).isEqualTo(1);
            assertConsistent(restarted.api(), database.adminToken(restarted.api()), product);
        }
    }

    @Test
    @DisplayName("PostgreSQL killed and restarted: committed data survives and the application recovers by itself")
    void databaseCrashRecovery() throws Exception {
        int hostPort = AppInstance.freePort();
        // A dedicated server with a fixed host port, so the application's JDBC URL stays valid across the restart.
        // fsync is on (the default here), so survival really is thanks to PostgreSQL's write-ahead log.
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgres.IMAGE)
                .withCommand("postgres")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(5432))))) {
            postgres.start();
            ExternalDatabase database = new ExternalDatabase(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

            try (AppInstance instance = AppInstance.launch("db-crash", postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), Map.of())
                    .awaitHealthy()) {
                Api api = instance.api();
                String admin = database.adminToken(api);
                UUID product = database.newProduct(api, admin, 10);
                User user = database.newUsers(1).getFirst();
                Api.Response before = api.post("/api/v1/reservations", user.token(),
                        Map.of("productId", product, "quantity", 4), Map.of("Idempotency-Key", "before-db-crash"));
                assertThat(before.status()).isEqualTo(201);

                postgres.getDockerClient().killContainerCmd(postgres.getContainerId()).exec();

                // While the database is down every request gets an honest, retryable failure, whether its connection
                // dies in the middle of the transaction or the pool can no longer provide one.
                List<Api.Response> duringOutage = Concurrent.run(10, i -> api.post("/api/v1/reservations", user.token(),
                        Map.of("productId", product, "quantity", 1), Map.of("Idempotency-Key", "during-db-crash-" + i))).results();
                assertThat(duringOutage).allSatisfy(response -> {
                    assertThat(response.status()).as(response.toString()).isEqualTo(503);
                    assertThat(response.code()).isEqualTo("TEMPORARILY_UNAVAILABLE");
                    assertThat(response.header("Retry-After")).isEqualTo("1");
                });

                postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec();
                awaitDatabase(database);

                assertThat(database.stock(product)).isEqualTo(new Stock(10, 6, 4));

                // The same application process recovers without a restart, and the failed request can be retried.
                Api.Response retried = awaitNotUnavailable(() -> api.post("/api/v1/reservations", user.token(),
                        Map.of("productId", product, "quantity", 1), Map.of("Idempotency-Key", "during-db-crash-0")));
                assertThat(retried.status()).as(retried.toString()).isEqualTo(201);
                assertThat(database.stock(product)).isEqualTo(new Stock(10, 5, 5));
                assertConsistent(api, admin, product);
            }
        }

        ConcurrencyReport.add("Scenario 10: PostgreSQL kill -9 and restart",
                "committed reservation intact after WAL recovery, API returned 503 during outage, recovered without restarting the app");
    }

    private static void awaitDatabase(ExternalDatabase database) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            try (var connection = database.connect()) {
                return;
            } catch (java.sql.SQLException ex) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("PostgreSQL did not come back");
    }

    private static Api.Response awaitNotUnavailable(java.util.function.Supplier<Api.Response> request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Api.Response response = request.get();
        while (response.status() == 503 && System.nanoTime() < deadline) {
            Thread.sleep(500);
            response = request.get();
        }
        return response;
    }

    private static void assertConsistent(Api api, String admin, UUID product) {
        var report = api.get("/api/v1/admin/consistency?productId=" + product, admin).json();
        assertThat(report.get("consistent").asBoolean()).as(report.toString()).isTrue();
    }
}
