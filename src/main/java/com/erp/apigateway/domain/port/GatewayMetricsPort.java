package com.erp.apigateway.domain.port;

import java.time.Duration;

/**
 * Publicacion de las metricas del borde.
 *
 * <p>Es un puerto de salida: la aplicacion dice QUE ha pasado y la
 * infraestructura decide COMO se mide. Sin el, el ASR QA-2 no es medible
 * (hallazgo 6.58).
 */
public interface GatewayMetricsPort {

    /**
     * Peticion atendida por una ruta.
     */
    void peticionAtendida(String ruta, String servicio, String metodo, int estado, Duration duracion);

    /**
     * Estado del interruptor de circuito de un destino.
     */
    void estadoDelCircuito(String destino, String estado);

    /**
     * Fallo de un destino, con el codigo de error que lo produjo.
     */
    void falloDeDestino(String destino, String errorCode);
}
