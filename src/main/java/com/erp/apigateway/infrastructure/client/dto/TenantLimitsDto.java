package com.erp.apigateway.infrastructure.client.dto;

import java.util.UUID;

public record TenantLimitsDto(
        UUID tenantId,
        String status,
        String planCode,
        int maxUsers,
        int maxVehicles,
        int maxIotMsgPerDay,
        /**
         * Limites de trafico del plan (ADR-007). Son {@code Integer} y no
         * {@code int} a proposito: null significa que el tenant-service no los
         * ha enviado todavia, y el gateway debe caer al limite MAS BAJO en vez
         * de a cero.
         */
        Integer maxRequestsPerMinute,
        Integer maxConcurrentSlots
) {}
