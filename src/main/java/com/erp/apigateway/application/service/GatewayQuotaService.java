package com.erp.apigateway.application.service;

import com.erp.apigateway.application.usecase.GetTenantQuotaUseCase;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.port.TenantQuotaPort;

import java.util.UUID;

/**
 * Caso de uso que resuelve la cuota y el estado del taller.
 *
 * <p>El controlador ya no consume el puerto directamente: la interfaz depende
 * de casos de uso, no de infraestructura (hallazgo 6.38).
 */
public class GatewayQuotaService implements GetTenantQuotaUseCase {

    private final TenantQuotaPort tenantQuotaPort;

    public GatewayQuotaService(TenantQuotaPort tenantQuotaPort) {
        this.tenantQuotaPort = tenantQuotaPort;
    }

    @Override
    public TenantQuota getQuota(UUID tenantId, String callerToken) {
        return tenantQuotaPort.getQuota(tenantId, callerToken);
    }
}
