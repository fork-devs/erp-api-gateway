package com.erp.apigateway.domain.exception;

public class TargetServiceUnavailableException extends GatewayException {
    public TargetServiceUnavailableException(String message) {
        super(message, "TARGET_SERVICE_UNAVAILABLE");
    }

    public TargetServiceUnavailableException(String message, Throwable cause) {
        super(message, "TARGET_SERVICE_UNAVAILABLE");
        initCause(cause);
    }
}
