package com.erp.apigateway.infrastructure.ratelimit;

import com.erp.apigateway.domain.model.RateLimitResult;
import com.erp.apigateway.domain.port.RateLimiterPort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.UUID;

public class TokenBucketRateLimiterAdapter implements RateLimiterPort {

    private final Cache<UUID, TokenBucket> buckets;

    public TokenBucketRateLimiterAdapter() {
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .maximumSize(10000)
                .build();
    }

    @Override
    public RateLimitResult checkAndConsume(UUID tenantId, int maxReqPerMin) {
        if (tenantId == null || maxReqPerMin <= 0) {
            return RateLimitResult.allowed(100, 100, 0);
        }

        TokenBucket bucket = buckets.get(tenantId, id -> new TokenBucket(maxReqPerMin));
        return bucket.tryConsume(maxReqPerMin);
    }

    private static class TokenBucket {
        private double availableTokens;
        private long lastRefillNanos;

        public TokenBucket(int maxTokens) {
            this.availableTokens = maxTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        public synchronized RateLimitResult tryConsume(int maxTokens) {
            long now = System.nanoTime();
            double refillRatePerNano = (double) maxTokens / 60_000_000_000.0; // maxTokens per minute
            double elapsedNanos = now - lastRefillNanos;

            // Recargar tokens
            availableTokens = Math.min(maxTokens, availableTokens + (elapsedNanos * refillRatePerNano));
            lastRefillNanos = now;

            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                long remaining = (long) availableTokens;
                return RateLimitResult.allowed(remaining, maxTokens, 0);
            } else {
                double missingTokens = 1.0 - availableTokens;
                long resetAfterSeconds = Math.max(1, (long) Math.ceil(missingTokens / (refillRatePerNano * 1_000_000_000.0)));
                return RateLimitResult.denied(maxTokens, resetAfterSeconds);
            }
        }
    }
}
