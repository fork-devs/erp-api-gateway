package com.erp.apigateway.domain.model;

import java.io.Serializable;

/**
 * Definicion de una ruta del borde: a que servicio se reenvia, con que plazo
 * y con que exigencia de identidad.
 *
 * <p>Es el modelo de dominio de la configuracion de rutas. La configuracion
 * vive en infraestructura, pero lo que la aplicacion necesita es esto: un
 * modelo sin framework, que se puede probar sin levantar nada.
 */
public record RouteDefinition(
        String serviceName,
        String pathPrefix,
        String targetUrl,
        int timeoutMs,
        boolean requiresAuth,
        String requiredRole,
        boolean tenantFilterAllowed
) implements Serializable {

    public RouteDefinition {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("serviceName no puede estar vacío");
        }
        if (pathPrefix == null || pathPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("pathPrefix no puede estar vacío");
        }
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("targetUrl no puede estar vacío");
        }
        if (timeoutMs <= 0) {
            timeoutMs = 10000;
        }
    }
}
