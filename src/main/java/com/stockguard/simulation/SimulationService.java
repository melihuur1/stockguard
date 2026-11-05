package com.stockguard.simulation;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.catalog.Product;
import com.stockguard.catalog.ProductService;
import com.stockguard.common.config.StockGuardProperties;
import com.stockguard.common.web.InstanceHeaderFilter;
import com.stockguard.inventory.ConsistencyService;
import com.stockguard.inventory.ConsistencyService.ConsistencyReport;
import com.stockguard.inventory.InventoryService;
import com.stockguard.inventory.StockLevel;
import com.stockguard.security.JwtTokenService;
import com.stockguard.user.Role;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a real race and reports what happened. Nothing is short-circuited: each simulated customer sends an
 * authenticated HTTP request through the full stack, across all configured instances. Every number in the
 * report is either counted from those responses or read back from the database afterwards.
 */
@Service
@ConditionalOnProperty(prefix = "stockguard.simulation", name = "enabled", havingValue = "true")
public class SimulationService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String SIMULATED_USERS_PREFIX = "__sim-";

    private final ProductService products;
    private final InventoryService inventory;
    private final ConsistencyService consistency;
    private final JwtTokenService tokens;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final List<String> configuredTargets;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    SimulationService(
            ProductService products,
            InventoryService inventory,
            ConsistencyService consistency,
            JwtTokenService tokens,
            AuditTrail audit,
            JdbcClient jdbc,
            JsonMapper json,
            TransactionTemplate transaction,
            Environment environment,
            StockGuardProperties properties) {
        this.products = products;
        this.inventory = inventory;
        this.consistency = consistency;
        this.tokens = tokens;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
        this.environment = environment;
        this.configuredTargets = properties.simulation().targets() == null ? List.of() : properties.simulation().targets();
    }

    public SimulationReport run(int initialStock, int concurrentRequests, int quantityPerRequest, UUID adminId) {
        UUID runId = UUID.randomUUID();
        Product product = products.create(
                "SIM-" + runId.toString().substring(0, 13), "Simulation " + runId, "Created by a concurrency simulation", adminId);
        if (initialStock > 0) {
            inventory.addStock(product.getId(), initialStock, adminId, "Simulation " + runId);
        }

        List<SimulatedUser> users = simulatedUsers(concurrentRequests);
        List<String> targets = targets();
        long startedAt = System.nanoTime();
        List<RequestResult> results = race(concurrentRequests, index -> {
            String target = targets.get(index % targets.size());
            return reserve(target, users.get(index), product.getId(), quantityPerRequest, runId + "-" + index);
        });
        long durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        SimulationReport report = report(runId, product, initialStock, concurrentRequests, quantityPerRequest, results, durationMillis);
        transaction.executeWithoutResult(status -> audit.record(
                AuditEventType.SIMULATION_EXECUTED, adminId, "PRODUCT", product.getId(), Map.of(
                        "runId", runId,
                        "initialStock", initialStock,
                        "concurrentRequests", concurrentRequests,
                        "successfulReservations", report.successfulReservations(),
                        "dataConsistent", report.dataConsistent())));
        return report;
    }

    /** All tasks wait behind one gate and are released together, so the requests genuinely overlap. */
    private static List<RequestResult> race(int count, Function<Integer, RequestResult> task) {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RequestResult>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    gate.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            gate.countDown();
            List<RequestResult> results = new ArrayList<>(count);
            for (Future<RequestResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulation interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Simulation task failed", ex.getCause());
        }
    }

    private RequestResult reserve(String target, SimulatedUser user, UUID productId, int quantity, String key) {
        String body = json.writeValueAsString(Map.of("productId", productId, "quantity", quantity));
        HttpRequest request = HttpRequest.newBuilder(URI.create(target + "/api/v1/reservations"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tokens.issue(user.id(), user.username(), Role.USER).value())
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            String instance = response.headers().firstValue(InstanceHeaderFilter.HEADER).orElse("unknown");
            return new RequestResult(response.statusCode(), errorCode(response.body()), instance, latency);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulation interrupted", ex);
        } catch (IOException ex) {
            long latency = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return new RequestResult(0, ex.getClass().getSimpleName(), "unreachable", latency);
        }
    }

    private String errorCode(String body) {
        try {
            JsonNode code = json.readTree(body).get("code");
            return code == null ? null : code.asString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private SimulationReport report(
            UUID runId, Product product, int initialStock, int concurrentRequests, int quantityPerRequest,
            List<RequestResult> results, long durationMillis) {
        long successful = results.stream().filter(result -> result.status() == 201).count();
        long rejected = results.stream()
                .filter(result -> result.status() == 409 && "INSUFFICIENT_STOCK".equals(result.code()))
                .count();

        StockLevel stock = inventory.get(product.getId());
        long reservationRows = jdbc.sql("SELECT COUNT(*) FROM reservations WHERE product_id = :id")
                .param("id", product.getId())
                .query(Long.class)
                .single();
        long unitsHeld = reservationRows * quantityPerRequest;
        ConsistencyReport check = consistency.check(product.getId());

        long unexpected = results.size() - successful - rejected;
        long oversoldUnits = Math.max(0, unitsHeld - initialStock);
        long negativeCounters = (stock.getAvailable() < 0 ? 1 : 0) + (stock.getReserved() < 0 ? 1 : 0) + (stock.getTotal() < 0 ? 1 : 0);
        // Nothing releases stock during a run, so the only correct outcome is to sell as much as fits. Selling less
        // would mean customers were turned away while stock was left, which is also a failure.
        long expectedSuccesses = Math.min(initialStock / quantityPerRequest, concurrentRequests);
        boolean consistent = check.consistent()
                && oversoldUnits == 0
                && negativeCounters == 0
                && unexpected == 0
                && reservationRows == successful
                && successful == expectedSuccesses;

        return new SimulationReport(
                runId,
                product.getId(),
                product.getSku(),
                initialStock,
                concurrentRequests,
                quantityPerRequest,
                successful,
                rejected,
                unexpected,
                reservationRows,
                stock.getTotal(),
                stock.getAvailable(),
                stock.getReserved(),
                oversoldUnits,
                negativeCounters,
                check,
                consistent,
                results.stream().collect(Collectors.groupingBy(
                        result -> String.valueOf(result.status()), TreeMap::new, Collectors.counting())),
                results.stream().collect(Collectors.groupingBy(
                        RequestResult::instance, TreeMap::new, Collectors.counting())),
                durationMillis,
                latencies(results));
    }

    private static Latency latencies(List<RequestResult> results) {
        long[] sorted = results.stream().mapToLong(RequestResult::latencyMillis).sorted().toArray();
        return new Latency(percentile(sorted, 50), percentile(sorted, 95), sorted[sorted.length - 1]);
    }

    private static long percentile(long[] sorted, int percentile) {
        return sorted[Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1)];
    }

    /**
     * Simulated customers form a fixed pool in a namespace registration cannot use, so repeated runs do not
     * grow the user table. The stored value is not a BCrypt hash, so these accounts can never log in.
     */
    private List<SimulatedUser> simulatedUsers(int count) {
        jdbc.sql("""
                INSERT INTO app_users (id, username, password_hash, role, created_at)
                SELECT CAST(md5(:prefix || n) AS uuid), :prefix || lpad(CAST(n AS text), 4, '0'), '!', 'USER', now()
                  FROM generate_series(1, :count) AS n
                ON CONFLICT (username) DO NOTHING
                """)
                .param("prefix", SIMULATED_USERS_PREFIX)
                .param("count", count)
                .update();
        return jdbc.sql("SELECT id, username FROM app_users WHERE starts_with(username, :prefix) ORDER BY username LIMIT :count")
                .param("prefix", SIMULATED_USERS_PREFIX)
                .param("count", count)
                .query((row, index) -> new SimulatedUser(row.getObject("id", UUID.class), row.getString("username")))
                .list();
    }

    private List<String> targets() {
        if (!configuredTargets.isEmpty()) {
            return configuredTargets;
        }
        return List.of("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private record SimulatedUser(UUID id, String username) {
    }

    private record RequestResult(int status, String code, String instance, long latencyMillis) {
    }

    public record Latency(long p50, long p95, long max) {
    }

    public record SimulationReport(
            UUID runId,
            UUID productId,
            String sku,
            int initialStock,
            int concurrentRequests,
            int quantityPerRequest,
            long successfulReservations,
            long rejectedReservations,
            long unexpectedFailures,
            long reservationRowsInDatabase,
            int totalQuantity,
            int availableQuantity,
            int reservedQuantity,
            long oversoldUnits,
            long negativeStockCounters,
            ConsistencyReport consistency,
            boolean dataConsistent,
            Map<String, Long> statusCodes,
            Map<String, Long> requestsPerInstance,
            long durationMillis,
            Latency latencyMillis) {
    }
}
