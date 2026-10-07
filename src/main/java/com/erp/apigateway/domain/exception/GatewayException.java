package com.erp.apigateway.domain.exception;

public abstract class GatewayException extends RuntimeException {
    private final String errorCode;

    protected GatewayException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
