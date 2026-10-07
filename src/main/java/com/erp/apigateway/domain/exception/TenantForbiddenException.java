package com.erp.apigateway.domain.exception;

public class TenantForbiddenException extends GatewayException {
    public TenantForbiddenException(String message) {
        super(message, "TENANT_ACCESS_FORBIDDEN");
    }
}
