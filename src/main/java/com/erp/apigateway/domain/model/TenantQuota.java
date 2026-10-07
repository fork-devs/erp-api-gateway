package com.erp.apigateway.domain.model;

import java.io.Serializable;
import java.util.UUID;

public final class TenantQuota implements Serializable {
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
        this.maxReqPerMin = maxReqPerMin > 0 ? maxReqPerMin : calculateDefaultReqPerMin(this.planCode);
        this.maxConcurrentSlots = maxConcurrentSlots > 0 ? maxConcurrentSlots : calculateDefaultConcurrentSlots(this.planCode);
    }

    public static TenantQuota createDefault(UUID tenantId, String planCode) {
        return new TenantQuota(
                tenantId,
                "ACTIVE",
                planCode,
                calculateDefaultReqPerMin(planCode),
                calculateDefaultConcurrentSlots(planCode)
        );
    }

    private static int calculateDefaultReqPerMin(String planCode) {
        if (planCode == null) return 100;
        return switch (planCode.toUpperCase()) {
            case "ENTERPRISE" -> 2000;
            case "PRO" -> 500;
            default -> 100; // BASICO / default
        };
    }

    private static int calculateDefaultConcurrentSlots(String planCode) {
        if (planCode == null) return 15;
        return switch (planCode.toUpperCase()) {
            case "ENTERPRISE" -> 100;
            case "PRO" -> 40;
            default -> 15; // BASICO
        };
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
