package com.erp.apigateway.application.usecase;

import com.erp.apigateway.domain.model.TenantQuota;

import java.util.UUID;

/**
 * Cuota y estado del taller.
 */
public interface GetTenantQuotaUseCase {
    TenantQuota getQuota(UUID tenantId, String callerToken);
}
