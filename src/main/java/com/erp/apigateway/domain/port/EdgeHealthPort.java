package com.erp.apigateway.domain.port;

import java.util.Map;

/**
 * Estado de las dependencias del borde.
 *
 * <p>La salud del gateway no es un stub: sin ella, un orquestador declara
 * sano un gateway que no puede validar ni un token (hallazgo 6.37).
 */
public interface EdgeHealthPort {

    /**
     * Si el borde puede atender peticiones: sus dependencias responden.
     */
    boolean isAvailable();

    /**
     * Detalle de cada dependencia comprobada.
     */
    Map<String, Object> details();
}
