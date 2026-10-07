package com.erp.apigateway.domain.port;

import com.erp.apigateway.domain.model.TenantQuota;

import java.util.UUID;

public interface TenantQuotaPort {
    TenantQuota getQuota(UUID tenantId);
}
