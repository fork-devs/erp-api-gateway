package com.erp.apigateway.infrastructure.ratelimit;

import com.erp.apigateway.domain.model.RateLimitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow requests within rate limit capacity")
    void shouldAllowRequestsWithinLimit() {
        TokenBucketRateLimiterAdapter rateLimiter = new TokenBucketRateLimiterAdapter();
        UUID tenantId = UUID.randomUUID();

        // Limite de 5 req/min
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = rateLimiter.checkAndConsume(tenantId, 5);
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }

        // 6to request debe ser denegado
        RateLimitResult deniedResult = rateLimiter.checkAndConsume(tenantId, 5);
        assertFalse(deniedResult.allowed());
        assertEquals(0, deniedResult.remainingTokens());
        assertTrue(deniedResult.resetAfterSeconds() > 0);
    }

    @Test
    @DisplayName("Should isolate rate limits between different tenants")
    void shouldIsolateDifferentTenants() {
        TokenBucketRateLimiterAdapter rateLimiter = new TokenBucketRateLimiterAdapter();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Agotar cuota de Tenant A (2 req/min)
        assertTrue(rateLimiter.checkAndConsume(tenantA, 2).allowed());
        assertTrue(rateLimiter.checkAndConsume(tenantA, 2).allowed());
        assertFalse(rateLimiter.checkAndConsume(tenantA, 2).allowed());

        // Tenant B no debe verse afectado
        assertTrue(rateLimiter.checkAndConsume(tenantB, 2).allowed());
        assertTrue(rateLimiter.checkAndConsume(tenantB, 2).allowed());
        assertFalse(rateLimiter.checkAndConsume(tenantB, 2).allowed());
    }

    @Test
    @DisplayName("Should apply the new limit when the plan is upgraded")
    void shouldApplyTheNewLimitWhenThePlanIsUpgraded() {
        TokenBucketRateLimiterAdapter rateLimiter = new TokenBucketRateLimiterAdapter();
        UUID tenantId = UUID.randomUUID();

        // Taller en BASICO: 2 req/min
        assertTrue(rateLimiter.checkAndConsume(tenantId, 2).allowed());
        assertTrue(rateLimiter.checkAndConsume(tenantId, 2).allowed());
        assertFalse(rateLimiter.checkAndConsume(tenantId, 2).allowed());

        // Sube a ENTERPRISE: 2000 req/min. El cubo debe reconocer el limite
        // nuevo de inmediato, no esperar a que expire la entrada de cache.
        for (int i = 0; i < 100; i++) {
            assertTrue(rateLimiter.checkAndConsume(tenantId, 2000).allowed(),
                    "la peticion " + (i + 1) + " debe admitirse con el limite nuevo");
        }
    }

    @Test
    @DisplayName("Should clamp the tokens when the plan is downgraded")
    void shouldClampTheTokensWhenThePlanIsDowngraded() {
        TokenBucketRateLimiterAdapter rateLimiter = new TokenBucketRateLimiterAdapter();
        UUID tenantId = UUID.randomUUID();

        // Taller en ENTERPRISE: 2000 req/min, se consumen 100
        for (int i = 0; i < 100; i++) {
            assertTrue(rateLimiter.checkAndConsume(tenantId, 2000).allowed());
        }

        // Baja a BASICO: 2 req/min. Los tokens sobrantes se recortan: el
        // limite nuevo es el que manda.
        assertTrue(rateLimiter.checkAndConsume(tenantId, 2).allowed());
        assertTrue(rateLimiter.checkAndConsume(tenantId, 2).allowed());
        assertFalse(rateLimiter.checkAndConsume(tenantId, 2).allowed(),
                "con el limite del plan nuevo no debe admitirse una tercera peticion");
    }
}
