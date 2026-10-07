package com.erp.apigateway.application.usecase;

import com.erp.apigateway.domain.model.RateLimitResult;
import com.erp.apigateway.domain.model.TenantQuota;

import java.util.UUID;

public interface ExecuteRateLimitUseCase {
    RateLimitResult evaluateRateLimit(UUID tenantId, TenantQuota quota);
}
