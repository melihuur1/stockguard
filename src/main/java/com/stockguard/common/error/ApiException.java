package com.stockguard.common.error;

/**
 * A business rule rejected the request. Being unchecked, it also rolls back the surrounding transaction,
 * which is exactly what makes a rejected reservation leave no trace.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
