package com.erp.apigateway.domain.port;

import com.erp.apigateway.domain.model.TenantQuota;

import java.util.UUID;

public interface TenantQuotaPort {
    /**
     * @param callerToken token del llamante, que el servicio de destino
     *                  necesita para derivar el taller de la peticion. Puede ser
     *                  null cuando la consulta no parte de un principal autenticado.
     */
    TenantQuota getQuota(UUID tenantId, String callerToken);
}
