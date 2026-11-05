package com.stockguard.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One real PostgreSQL shared by the whole test run. Testcontainers switches fsync off by default to go faster;
 * that is undone here so commits behave, and cost, what they would in production.
 */
public final class TestPostgres {

    public static final String IMAGE = "postgres:17-alpine";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE)
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        CONTAINER.start();
    }

    private TestPostgres() {
    }

    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }

    /** A fresh, empty database for tests that must also exercise startup and migrations. */
    public static String createDatabase(String prefix) {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not create database " + name, ex);
        }
        return jdbcUrl().replace("/" + CONTAINER.getDatabaseName(), "/" + name);
    }
}
