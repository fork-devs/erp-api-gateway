package com.erp.apigateway.infrastructure.config;

import com.erp.apigateway.domain.model.RouteDefinition;
import com.erp.apigateway.domain.port.RouteCatalogPort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adaptador que expone la configuracion de rutas como catalogo de dominio.
 *
 * <p>La configuracion es de infraestructura; el catalogo que consume la
 * aplicacion es de dominio. El adaptador es el puente, y deja de existir el
 * bloque de URLs fijas que, si faltaba una ruta en el YAML, hacia caer al
 * gateway en un destino local en produccion (hallazgo 6.34).
 */
@Component
public class RouteCatalogAdapter implements RouteCatalogPort {

    /**
     * Rutas que se sirven sin autenticacion.
     *
     * <p>Cada una tiene que existir en el servicio de destino. Una ruta
     * declarada como publica que no existe no es un problema de seguridad
     * (no hay nada que proteger), pero es un fallo de configuracion que no
     * se ve: la peticion simplemente no encuentra nada.
     */
    private static final Set<String> PUBLIC_PATHS = new LinkedHashSet<>(Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/.well-known/jwks.json",
            "/api/v1/plans"
    ));

    private final GatewayProperties gatewayProperties;

    public RouteCatalogAdapter(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    @Override
    public Optional<RouteDefinition> findByPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, GatewayProperties.ServiceRoute> entry : gatewayProperties.getRoutes().entrySet()) {
            GatewayProperties.ServiceRoute ruta = entry.getValue();
            if (ruta.getPathPrefix() != null && path.startsWith(ruta.getPathPrefix())) {
                boolean requiereAutenticacion = ruta.isRequiresAuth() && !isPublicEndpoint(path);
                return Optional.of(new RouteDefinition(
                        entry.getKey(),
                        ruta.getPathPrefix(),
                        ruta.getTargetUrl(),
                        ruta.getTimeoutMs(),
                        requiereAutenticacion,
                        ruta.getRequiredRole(),
                        ruta.isTenantFilterAllowed()));
            }
        }
        // Una ruta NO declarada no se reenvia a ningun destino por defecto.
        return Optional.empty();
    }

    @Override
    public boolean isPublicEndpoint(String path) {
        if (path == null) {
            return false;
        }
        for (String publica : PUBLIC_PATHS) {
            if (path.equalsIgnoreCase(publica) || path.startsWith(publica + "/")) {
                return true;
            }
        }
        return false;
    }
}
