package com.erp.apigateway.domain.model;

import java.io.Serializable;
import java.util.UUID;

public final class TenantQuota implements Serializable {

    /**
     * Limite de trafico por defecto: el del plan MAS BASICO.
     *
     * <p>El gateway NO deduce los limites de trafico del codigo de plan: los
     * posee el tenant-service, que es el dueno del plan (ADR-007). Aqui solo
     * esta el suelo, para que un limite ausente nunca sea mas alto que el
     * minimo.
     */
    public static final int LIMITE_POR_DEFECTO_POR_MINUTO = 100;
    public static final int LIMITE_POR_DEFECTO_CONCURRENTES = 15;

    private final UUID tenantId;
    private final String status;
    private final String planCode;
    private final int maxReqPerMin;
    private final int maxConcurrentSlots;

    public TenantQuota(UUID tenantId, String status, String planCode, int maxReqPerMin, int maxConcurrentSlots) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId no puede ser nulo");
        }
        this.tenantId = tenantId;
        this.status = status != null ? status.toUpperCase() : "ACTIVE";
        this.planCode = planCode != null ? planCode.toUpperCase() : "BASICO";
        this.maxReqPerMin = maxReqPerMin > 0 ? maxReqPerMin : LIMITE_POR_DEFECTO_POR_MINUTO;
        this.maxConcurrentSlots = maxConcurrentSlots > 0 ? maxConcurrentSlots : LIMITE_POR_DEFECTO_CONCURRENTES;
    }

    /**
     * Cuota minima, usada cuando no se ha podido determinar el estado del
     * taller. El plan se conserva por trazabilidad, pero los limites de
     * trafico NO se derivan de el.
     */
    public static TenantQuota createDefault(UUID tenantId, String planCode) {
        return new TenantQuota(
                tenantId,
                "ACTIVE",
                planCode,
                LIMITE_POR_DEFECTO_POR_MINUTO,
                LIMITE_POR_DEFECTO_CONCURRENTES
        );
    }

    public boolean isSuspended() {
        return "SUSPENDED".equalsIgnoreCase(this.status);
    }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(this.status);
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getStatus() {
        return status;
    }

    public String getPlanCode() {
        return planCode;
    }

    public int getMaxReqPerMin() {
        return maxReqPerMin;
    }

    public int getMaxConcurrentSlots() {
        return maxConcurrentSlots;
    }
}
