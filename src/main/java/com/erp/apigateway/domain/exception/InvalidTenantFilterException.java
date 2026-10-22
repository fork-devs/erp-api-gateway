package com.erp.apigateway.domain.exception;

/**
 * El filtro de taller declarado por el cliente no tiene forma de
 * identificador, y la ruta es de arranque.
 *
 * <p>En las rutas de arranque el cliente declara el taller como filtro porque
 * la consulta por hash ocurre antes de conocer la fila. Ese valor se
 * descarta si no es un identificador: un filtro mal formado no se reenvia,
 * porque el destino lo usaria como criterio de busqueda.
 */
public class InvalidTenantFilterException extends GatewayException {

    public InvalidTenantFilterException(String message) {
        super(message, "INVALID_TENANT_FILTER");
    }
}
