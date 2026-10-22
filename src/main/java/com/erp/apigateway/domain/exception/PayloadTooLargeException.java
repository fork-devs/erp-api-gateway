package com.erp.apigateway.domain.exception;

/**
 * El cuerpo de la peticion excede el tope que admite el borde.
 *
 * <p>Se rechaza con 413 antes de reenviar: un cuerpo grande no debe llegar
 * al servicio de destino (hallazgo 6.33).
 */
public class PayloadTooLargeException extends GatewayException {

    public PayloadTooLargeException(String message) {
        super(message, "PAYLOAD_TOO_LARGE");
    }
}
