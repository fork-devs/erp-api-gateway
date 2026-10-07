package com.erp.apigateway.domain.port;

import com.erp.apigateway.domain.model.RateLimitResult;

import java.util.UUID;

public interface RateLimiterPort {
    RateLimitResult checkAndConsume(UUID tenantId, int maxReqPerMin);
}
