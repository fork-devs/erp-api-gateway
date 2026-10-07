package com.erp.apigateway.application.service;

import com.erp.apigateway.application.usecase.ExecuteRateLimitUseCase;
import com.erp.apigateway.domain.exception.RateLimitExceededException;
import com.erp.apigateway.domain.model.RateLimitResult;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.port.RateLimiterPort;

import java.util.UUID;

public class GatewayRateLimitingService implements ExecuteRateLimitUseCase {

    private final RateLimiterPort rateLimiterPort;

    public GatewayRateLimitingService(RateLimiterPort rateLimiterPort) {
        this.rateLimiterPort = rateLimiterPort;
    }

    @Override
    public RateLimitResult evaluateRateLimit(UUID tenantId, TenantQuota quota) {
        if (tenantId == null) {
            return RateLimitResult.allowed(100, 100, 0);
        }

        int maxReqPerMin = quota != null ? quota.getMaxReqPerMin() : 100;
        RateLimitResult result = rateLimiterPort.checkAndConsume(tenantId, maxReqPerMin);

        if (!result.allowed()) {
            throw new RateLimitExceededException(
                    "Límite de peticiones por minuto excedido para el plan del taller (" + maxReqPerMin + " req/min)",
                    result.resetAfterSeconds()
            );
        }

        return result;
    }
}
