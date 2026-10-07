package com.erp.apigateway.infrastructure.client.dto;

import java.util.UUID;

public record TenantLimitsDto(
        UUID tenantId,
        String status,
        String planCode,
        int maxUsers,
        int maxVehicles,
        int maxIotMsgPerDay
) {}
