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
        // El cubo se REAJUSTA al limite vigente del plan: un taller que sube
        // de plan debe notar el cambio en la cuota, y uno que baja no debe
        // conservar los tokens del plan anterior (hallazgo 6.36).
        bucket.resize(maxReqPerMin);
        return bucket.tryConsume(maxReqPerMin);
    }

    private static class TokenBucket {
        private double availableTokens;
        private long lastRefillNanos;
        private int maxTokens;

        public TokenBucket(int maxTokens) {
            this.maxTokens = maxTokens;
            this.availableTokens = maxTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        /**
         * Ajusta la capacidad del cubo al limite vigente. Al subir, la
         * capacidad sube de inmediato y se conceden los tokens de mas: el
         * taller acaba de pagar por ese plan, y esperar a que el cubo se
         * rellene solo significaria denegar trafico que ya ha comprado. Al
         * bajar, los tokens que sobran se recortan, porque el limite nuevo es
         * el que manda.
         */
        public synchronized void resize(int newMaxTokens) {
            if (newMaxTokens <= 0 || newMaxTokens == this.maxTokens) {
                return;
            }
            if (newMaxTokens > this.maxTokens) {
                this.availableTokens = Math.min(newMaxTokens, this.availableTokens + (newMaxTokens - this.maxTokens));
            } else {
                this.availableTokens = Math.min(this.availableTokens, newMaxTokens);
            }
            this.maxTokens = newMaxTokens;
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
