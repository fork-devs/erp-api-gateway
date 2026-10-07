package com.erp.apigateway.domain.exception;

public class RateLimitExceededException extends GatewayException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message, "RATE_LIMIT_EXCEEDED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
