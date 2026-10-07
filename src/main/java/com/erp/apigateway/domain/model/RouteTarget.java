package com.erp.apigateway.domain.model;

import java.io.Serializable;

public record RouteTarget(
        String serviceName,
        String targetBaseUrl,
        int timeoutMs,
        boolean requiresAuth
) implements Serializable {
    public RouteTarget {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("serviceName no puede estar vacío");
        }
        if (targetBaseUrl == null || targetBaseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("targetBaseUrl no puede estar vacío");
        }
        if (timeoutMs <= 0) {
            timeoutMs = 10000; // 10s default
        }
    }
}
