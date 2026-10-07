package com.erp.apigateway.domain.exception;

public class InvalidTokenException extends GatewayException {
    public InvalidTokenException(String message) {
        super(message, "INVALID_OR_EXPIRED_JWT");
    }
}
