package com.erp.apigateway.application.usecase;

import com.erp.apigateway.domain.model.RouteTarget;

/**
 * Resolucion de la ruta de una peticion.
 *
 * <p>Devuelve null cuando la ruta no esta declarada: una peticion a una ruta
 * desconocida no se reenvia a ningun destino.
 */
public interface ResolveRouteUseCase {
    RouteTarget resolve(String path);
}
