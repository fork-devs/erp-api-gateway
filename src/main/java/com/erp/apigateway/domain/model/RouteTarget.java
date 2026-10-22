package com.erp.apigateway.domain.model;

import java.io.Serializable;

public record RouteTarget(
        String serviceName,
        String targetBaseUrl,
        int timeoutMs,
        boolean requiresAuth,
        /**
         * Rol que el principal tiene que ostestar para usar la ruta. Vacio
         * significa que basta con estar autenticado.
         */
        String requiredRole,
        /**
         * Ruta de ARRANQUE: login, refresco y restablecimiento de contrasena.
         *
         * <p>En esas rutas el cliente declara el taller como FILTRO, porque la
         * consulta por hash ocurre antes de conocer la fila. Ese valor viaja
         * al destino como una pista, nunca como una autoridad: el destino lo
         * contrasta con el token. En cualquier otra ruta, la cabecera de
         * identidad del cliente se descarta.
         */
        boolean tenantFilterAllowed
) implements Serializable {
    public RouteTarget(String serviceName, String targetBaseUrl, int timeoutMs, boolean requiresAuth) {
        this(serviceName, targetBaseUrl, timeoutMs, requiresAuth, null, false);
    }

    public RouteTarget(String serviceName, String targetBaseUrl, int timeoutMs, boolean requiresAuth, String requiredRole) {
        this(serviceName, targetBaseUrl, timeoutMs, requiresAuth, requiredRole, false);
    }

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
