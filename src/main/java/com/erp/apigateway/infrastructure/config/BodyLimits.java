package com.erp.apigateway.infrastructure.config;

/**
 * Topes del cuerpo que el borde admite.
 *
 * <p>Sin tope, una peticion grande agota la heap del gateway, y una
 * respuesta grande de un servicio comprometido hace lo mismo por el otro
 * lado (hallazgo 6.33).
 *
 * <p>Los dos lados hacen falta y NO los cubre la misma cosa: la propiedad
 * {@code spring.codec.max-in-memory-size} acota lo que entra, y no dice nada
 * de lo que sale.
 */
public record BodyLimits(int inboundBytes, int outboundBytes) {

    public static final int DEFAULT_INBOUND_BYTES = 1024 * 1024;      // 1 MB
    public static final int DEFAULT_OUTBOUND_BYTES = 2 * 1024 * 1024;  // 2 MB

    public BodyLimits {
        if (inboundBytes <= 0) {
            inboundBytes = DEFAULT_INBOUND_BYTES;
        }
        if (outboundBytes <= 0) {
            outboundBytes = DEFAULT_OUTBOUND_BYTES;
        }
    }

    public static BodyLimits porDefecto() {
        return new BodyLimits(DEFAULT_INBOUND_BYTES, DEFAULT_OUTBOUND_BYTES);
    }
}
