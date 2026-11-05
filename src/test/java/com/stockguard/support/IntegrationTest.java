package com.stockguard.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockguard.inventory.ConsistencyService;
import com.stockguard.security.JwtTokenService;
import com.stockguard.user.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the real application against real PostgreSQL. Fixtures use the public API wherever the behaviour under
 * test depends on it. Verification reads the tables directly, so it does not trust the code being tested.
 *
 * <p>The scheduled sweeper is off here so tests decide when expiry runs. It runs on its own schedule in
 * {@code MultiInstanceIT} and {@code RestartDurabilityIT}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stockguard.security.jwt-secret=" + IntegrationTest.JWT_SECRET,
            "stockguard.instance-id=it-main",
            "stockguard.reservation.expiry.enabled=false",
            "stockguard.simulation.enabled=true",
            "spring.datasource.hikari.maximum-pool-size=20"
        })
public abstract class IntegrationTest {

    public static final String JWT_SECRET = "integration-test-secret-integration-test-secret";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestPostgres::jdbcUrl);
        registry.add("spring.datasource.username", TestPostgres::username);
        registry.add("spring.datasource.password", TestPostgres::password);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected ConsistencyService consistency;

    @Autowired
    private JwtTokenService tokens;

    protected Api api;
    private TestUser fixtureAdmin;

    @BeforeEach
    void setUpApi() {
        api = new Api("http://localhost:" + port);
    }

    protected TestUser newUser() {
        return newUsers(1).getFirst();
    }

    protected TestUser newAdmin() {
        return insertUsers(1, Role.ADMIN).getFirst();
    }

    protected List<TestUser> newUsers(int count) {
        return insertUsers(count, Role.USER);
    }

    /** Password hashing is irrelevant to these tests, and 100 BCrypt rounds would only slow them down. */
    private List<TestUser> insertUsers(int count, Role role) {
        String prefix = "it-" + UUID.randomUUID().toString().substring(0, 13) + "-";
        return jdbc.sql("""
                INSERT INTO app_users (id, username, password_hash, role)
                SELECT gen_random_uuid(), :prefix || n, '!', :role FROM generate_series(1, :count) AS n
                RETURNING id, username
                """)
                .param("prefix", prefix)
                .param("role", role.name())
                .param("count", count)
                .query((row, index) -> {
                    UUID id = row.getObject("id", UUID.class);
                    String username = row.getString("username");
                    return new TestUser(id, username, tokens.issue(id, username, role).value());
                })
                .list();
    }

    protected TestUser admin() {
        if (fixtureAdmin == null) {
            fixtureAdmin = newAdmin();
        }
        return fixtureAdmin;
    }

    protected UUID newProduct(int initialStock) {
        String sku = "IT-" + UUID.randomUUID();
        Api.Response created = api.post("/api/v1/products", admin().token(), Map.of("sku", sku, "name", "Test " + sku));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.text("id"));
        if (initialStock > 0) {
            addStock(id, initialStock);
        }
        return id;
    }

    protected void addStock(UUID product, int quantity) {
        Api.Response added = api.post("/api/v1/products/" + product + "/stock", admin().token(), Map.of("quantity", quantity));
        assertThat(added.status()).as(added.toString()).isEqualTo(200);
    }

    protected Api.Response reserve(TestUser user, UUID product, int quantity, String key) {
        return api.post("/api/v1/reservations", user.token(),
                Map.of("productId", product, "quantity", quantity), Map.of("Idempotency-Key", key));
    }

    protected UUID reserveOk(TestUser user, UUID product, int quantity) {
        Api.Response response = reserve(user, product, quantity, UUID.randomUUID().toString());
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return UUID.fromString(response.text("id"));
    }

    protected Api.Response confirm(TestUser user, UUID reservation) {
        return api.post("/api/v1/reservations/" + reservation + "/confirm", user.token(), null);
    }

    protected Api.Response cancel(TestUser user, UUID reservation) {
        return api.post("/api/v1/reservations/" + reservation + "/cancel", user.token(), null);
    }

    protected Stock stock(UUID product) {
        return jdbc.sql("SELECT total_quantity, available_quantity, reserved_quantity FROM stock_levels WHERE product_id = :id")
                .param("id", product)
                .query((row, index) -> new Stock(row.getInt(1), row.getInt(2), row.getInt(3)))
                .single();
    }

    protected long reservationRows(UUID product) {
        return jdbc.sql("SELECT COUNT(*) FROM reservations WHERE product_id = :id").param("id", product).query(Long.class).single();
    }

    protected String statusOf(UUID reservation) {
        return jdbc.sql("SELECT status FROM reservations WHERE id = :id").param("id", reservation).query(String.class).single();
    }

    protected void makeOverdue(UUID reservation) {
        jdbc.sql("UPDATE reservations SET expires_at = clock_timestamp() - interval '1 second' WHERE id = :id")
                .param("id", reservation)
                .update();
    }

    protected void assertConsistent(UUID product) {
        ConsistencyService.ConsistencyReport report = consistency.check(product);
        assertThat(report.violations()).as("consistency of product %s", product).isEmpty();
        assertThat(report.productsChecked()).isEqualTo(1);
    }

    protected static Map<Integer, Long> statusCounts(List<Api.Response> responses) {
        return responses.stream().collect(Collectors.groupingBy(Api.Response::status, Collectors.counting()));
    }

    protected static <T> Map<T, Long> countBy(List<Api.Response> responses, Function<Api.Response, T> classifier) {
        return responses.stream().collect(Collectors.groupingBy(classifier, Collectors.counting()));
    }

    public record TestUser(UUID id, String username, String token) {
    }

    public record Stock(int total, int available, int reserved) {
    }
}
