package com.erp.apigateway.domain.exception;

/**
 * El circuito del destino esta abierto: no se le llama.
 *
 * <p>Es un caso distinto del fallo durante la llamada. Aqui el destino no ha
 * fallado <em>ahora</em>: ha fallado lo suficiente como para que el borde
 * decida no molestarle durante un rato. La respuesta es 503 con
 * {@code Retry-After}, para que el cliente sepa que reintentar mas tarde tiene
 * sentido (hallazgo 6.57).
 */
public class CircuitOpenException extends GatewayException {

    public CircuitOpenException(String message) {
        super(message, "CIRCUIT_OPEN");
    }
}
