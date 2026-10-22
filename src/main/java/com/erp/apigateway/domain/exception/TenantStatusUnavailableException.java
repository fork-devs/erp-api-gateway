package com.erp.apigateway.domain.exception;

/**
 * No se ha podido determinar el estado del taller.
 *
 * <p>El borde falla CERRADO: sin el estado del taller no se sabe si el taller
 * esta suspendido, y tratar un taller suspendido como activo lo deja operando
 * (hallazgo 6.31). La disponibilidad se sacrifica a cambio de no reactivar un
 * taller que la plataforma tiene suspendido.
 */
public class TenantStatusUnavailableException extends GatewayException {

    public TenantStatusUnavailableException(String message) {
        super(message, "TENANT_STATUS_UNAVAILABLE");
    }

    public TenantStatusUnavailableException(String message, Throwable cause) {
        super(message, "TENANT_STATUS_UNAVAILABLE");
        initCause(cause);
    }
}
