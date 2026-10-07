package com.erp.apigateway.domain.model;

import java.io.Serializable;

public record RateLimitResult(
        boolean allowed,
        long remainingTokens,
        long limit,
        long resetAfterSeconds
) implements Serializable {
    public static RateLimitResult allowed(long remaining, long limit, long resetAfterSeconds) {
        return new RateLimitResult(true, remaining, limit, resetAfterSeconds);
    }

    public static RateLimitResult denied(long limit, long resetAfterSeconds) {
        return new RateLimitResult(false, 0, limit, resetAfterSeconds);
    }
}
