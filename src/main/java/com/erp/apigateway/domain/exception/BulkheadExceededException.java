package com.erp.apigateway.domain.exception;

public class BulkheadExceededException extends GatewayException {
    public BulkheadExceededException(String message) {
        super(message, "BULKHEAD_CAPACITY_EXCEEDED");
    }
}
