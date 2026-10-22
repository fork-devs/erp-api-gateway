package com.erp.apigateway.application.service;

import com.erp.apigateway.application.usecase.ResolveRouteUseCase;
import com.erp.apigateway.domain.model.RouteDefinition;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.port.RouteCatalogPort;

/**
 * Resuelve la ruta de una peticion contra el catalogo del borde.
 *
 * <p>La resolucion vive en la aplicacion, no en el controlador: es la segunda
 * pieza mas importante del gateway, y desde ahi se puede probar sin levantar
 * un servlet (hallazgo 6.38).
 */
public class GatewayRouteResolver implements ResolveRouteUseCase {

    private final RouteCatalogPort routeCatalogPort;

    public GatewayRouteResolver(RouteCatalogPort routeCatalogPort) {
        this.routeCatalogPort = routeCatalogPort;
    }

    @Override
    public RouteTarget resolve(String path) {
        return routeCatalogPort.findByPath(path)
                .map(this::aDestino)
                .orElse(null);
    }

    private RouteTarget aDestino(RouteDefinition definicion) {
        return new RouteTarget(
                definicion.serviceName(),
                definicion.targetUrl(),
                definicion.timeoutMs(),
                definicion.requiresAuth(),
                definicion.requiredRole(),
                definicion.tenantFilterAllowed());
    }
}
