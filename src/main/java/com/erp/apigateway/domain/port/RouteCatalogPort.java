package com.erp.apigateway.domain.port;

import com.erp.apigateway.domain.model.RouteDefinition;

import java.util.Optional;

/**
 * Catalogo de rutas del borde.
 *
 * <p>La configuracion de rutas es un detalle de infraestructura: lo que la
 * aplicacion necesita es el catalogo en terminos de dominio, con una sola
 * fuente de verdad.
 */
public interface RouteCatalogPort {

    /**
     * Ruta que corresponde a un path, si la hay.
     *
     * <p>Una peticion a una ruta NO declarada no se reenvia a ningun sitio:
     * no hay destino por defecto al que caerse.
     */
    Optional<RouteDefinition> findByPath(String path);

    /**
     * Si el path es de acceso publico (login, refresco, planes, conjunto de
     * claves). En esas rutas no hace falta token.
     */
    boolean isPublicEndpoint(String path);
}
