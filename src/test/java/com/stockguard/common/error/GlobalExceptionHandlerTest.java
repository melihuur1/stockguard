package com.stockguard.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.TransactionException;
import org.hibernate.exception.GenericJDBCException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<ProblemDetail> handleRollbackFailure(SQLException cause) {
        return handler.handleJpaSystem(new JpaSystemException(
                new TransactionException("Unable to rollback against JDBC Connection", cause)));
    }

    private static void assertRetryableOutage(ResponseEntity<ProblemDetail> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("code", "TEMPORARILY_UNAVAILABLE");
    }

    private static void assertInternalError(ResponseEntity<ProblemDetail> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    @DisplayName("The exact error HikariCP raises for a connection it has evicted is an outage")
    void evictedPoolConnectionIsAnOutage() {
        assertRetryableOutage(handleRollbackFailure(new SQLException("Connection is closed")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "08003", "08001", "57P01", "57P02", "57P03"})
    @DisplayName("SQLSTATE values of a lost connection or a stopping server are an outage")
    void connectionFailureStatesAreAnOutage(String state) {
        assertRetryableOutage(handleRollbackFailure(new SQLException("connection failure", state)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23514", "40001", "42P01", "57014", "P0001", "XX000"})
    @DisplayName("A failed transaction with any other SQLSTATE is not mistaken for an outage")
    void otherStatesStayInternal(String state) {
        assertInternalError(handleRollbackFailure(new SQLException("Connection is closed", state)));
    }

    @Test
    @DisplayName("Other SQL errors without a SQLSTATE stay internal, because only the pool's exact message counts")
    void otherStatelessErrorsStayInternal() {
        assertInternalError(handleRollbackFailure(new SQLException("something else went wrong")));
    }

    @Test
    @DisplayName("A statement failing for a non-connection reason stays internal")
    void genericJdbcFailureStaysInternal() {
        assertInternalError(handler.handleJpaSystem(new JpaSystemException(
                new GenericJDBCException("failed", new SQLException("injected failure", "P0001")))));
    }

    @Test
    @DisplayName("A JPA failure with no SQL cause at all stays internal")
    void failureWithoutSqlCauseStaysInternal() {
        assertInternalError(handler.handleJpaSystem(new JpaSystemException(new IllegalStateException("unrelated"))));
    }
}
