package com.stockguard.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixtures and verification for tests whose application runs in another process: there are no beans to inject,
 * only the database and the HTTP API. Tokens are signed here with the secret the instances were configured with.
 */
public final class ExternalDatabase {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public ExternalDatabase(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    public List<User> newUsers(int count) throws Exception {
        String prefix = "ext-" + UUID.randomUUID().toString().substring(0, 13) + "-";
        List<User> users = new ArrayList<>(count);
        try (Connection connection = connect();
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO app_users (id, username, password_hash, role)
                        SELECT gen_random_uuid(), ? || n, '!', 'USER' FROM generate_series(1, ?) AS n
                        RETURNING id, username
                        """)) {
            insert.setString(1, prefix);
            insert.setInt(2, count);
            try (ResultSet rows = insert.executeQuery()) {
                while (rows.next()) {
                    UUID id = rows.getObject(1, UUID.class);
                    users.add(new User(id, token(id, rows.getString(2), "USER")));
                }
            }
        }
        return users;
    }

    public String adminToken(Api api) {
        Api.Response login = api.post("/api/v1/auth/login", null, Map.of("username", "admin", "password", "integration-admin-password"));
        assertThat(login.status()).as(login.toString()).isEqualTo(200);
        return login.text("accessToken");
    }

    public UUID newProduct(Api api, String adminToken, int stock) {
        Api.Response created = api.post("/api/v1/products", adminToken, Map.of("sku", "EXT-" + UUID.randomUUID(), "name", "External test product"));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID id = UUID.fromString(created.text("id"));
        if (stock > 0) {
            assertThat(api.post("/api/v1/products/" + id + "/stock", adminToken, Map.of("quantity", stock)).status()).isEqualTo(200);
        }
        return id;
    }

    public IntegrationTest.Stock stock(UUID product) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT total_quantity, available_quantity, reserved_quantity FROM stock_levels WHERE product_id = ?")) {
            query.setObject(1, product);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return new IntegrationTest.Stock(rows.getInt(1), rows.getInt(2), rows.getInt(3));
            }
        }
    }

    public long count(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                query.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    public void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        }
    }

    public static String token(UUID userId, String username, String role) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer("stockguard")
                .claim("username", username)
                .claim("role", role)
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(AppInstance.JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    public record User(UUID id, String token) {
    }
}
