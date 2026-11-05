package com.stockguard.common.error;

import java.util.List;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns every failure into an RFC 9457 problem document carrying a stable {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Unique constraints that represent a normal client conflict rather than a bug. */
    private static final Map<String, ErrorCode> KNOWN_CONSTRAINTS = Map.of("uq_products_sku", ErrorCode.SKU_TAKEN);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return respond(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return respond(ErrorCode.STALE_VERSION, "The resource was modified by someone else. Reload it and retry.");
    }

    /**
     * Reaches this point only after the bounded server-side retry gave up. The transaction was rolled back,
     * so telling the client to retry with the same idempotency key is always safe.
     */
    @ExceptionHandler({
        PessimisticLockingFailureException.class,
        CannotCreateTransactionException.class,
        DataAccessResourceFailureException.class
    })
    ResponseEntity<ProblemDetail> handleTransient(Exception ex) {
        log.warn("Transient database failure: {}", ex.getMessage());
        ProblemDetail problem = problem(ErrorCode.TEMPORARILY_UNAVAILABLE, "The operation could not complete right now. Retry shortly.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException violation
                && KNOWN_CONSTRAINTS.containsKey(violation.getConstraintName())) {
            ErrorCode code = KNOWN_CONSTRAINTS.get(violation.getConstraintName());
            return respond(code, "A resource with the same unique value already exists.");
        }
        // Any other constraint means application code tried to break an invariant: a bug, not a client error.
        log.error("Database rejected a write that should have been impossible", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ex.getBody().setProperty("errors", violations);
        ex.getBody().setDetail("One or more fields are invalid.");
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // When one parameter of a handler carries a constraint, Spring validates @Valid bodies on this path too,
        // so a body's errors arrive here and must still be reported against the body's own fields.
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors body
                        ? body.getFieldErrors().stream()
                                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                        : result.getResolvableErrors().stream()
                                .map(error -> new FieldViolation(
                                        result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        ex.getBody().setProperty("errors", violations);
        ex.getBody().setDetail("One or more parameters are invalid.");
        return super.handleHandlerMethodValidationException(ex, headers, status, request);
    }

    /** Spring MVC's own errors (malformed JSON, wrong method, type mismatch...) also get a {@code code}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            HttpStatus resolved = HttpStatus.resolve(status.value());
            String code = status.value() == 400 ? ErrorCode.VALIDATION_FAILED.name()
                    : resolved != null ? resolved.name() : ErrorCode.INTERNAL_ERROR.name();
            problem.setProperty("code", code);
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail) {
        return ResponseEntity.status(code.status()).body(problem(code, detail));
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setProperty("code", code.name());
        return problem;
    }

    record FieldViolation(String field, String message) {
    }
}
