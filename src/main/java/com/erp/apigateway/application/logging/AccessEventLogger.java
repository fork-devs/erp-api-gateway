package com.erp.apigateway.application.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registro de acceso del borde.
 *
 * <p>El gateway no emitia NI UN log de acceso: era imposible auditar el
 * trafico, detectar un abuso o diagnosticar un incidente desde el borde
 * (hallazgo 6.37).
 *
 * <p>El esquema es el comun de la plataforma: pares clave=valor con
 * {@code evento}, {@code tenant_id}, {@code user_id}, {@code trace_id},
 * {@code resultado} y {@code timestamp}, y los atributos extra a continuacion.
 * El cuerpo de la peticion y el de la respuesta NO se registran.
 */
public final class AccessEventLogger {

    private static final Logger log = LoggerFactory.getLogger("ACCESS");

    public static final String EVENTO = "ACCESO_PETICION";

    private AccessEventLogger() {
    }

    public static void registrar(String ruta,
                                  String destino,
                                  String metodo,
                                  int estado,
                                  long duracionMs,
                                  String tenantId,
                                  String userId,
                                  String traceId) {
        Map<String, String> atributos = new LinkedHashMap<>();
        atributos.put("ruta", ruta);
        atributos.put("destino", destino);
        atributos.put("metodo", metodo);
        atributos.put("estado", String.valueOf(estado));
        atributos.put("duracion_ms", String.valueOf(duracionMs));

        log.info(formatear(EVENTO, tenantId, userId, traceId, estado >= 500 ? "ERROR" : "OK", atributos));
    }

    static String formatear(String evento,
                            String tenantId,
                            String userId,
                            String traceId,
                            String resultado,
                            Map<String, String> extra) {
        Map<String, String> atributos = new LinkedHashMap<>();
        atributos.put("evento", evento);
        atributos.put("tenant_id", tenantId != null && !tenantId.isBlank() ? tenantId : "-");
        atributos.put("user_id", userId != null && !userId.isBlank() ? userId : "-");
        atributos.put("trace_id", traceId != null && !traceId.isBlank() ? traceId : "-");
        atributos.put("resultado", resultado);
        atributos.put("timestamp", Instant.now().toString());

        if (extra != null) {
            extra.forEach((clave, valor) -> {
                if (clave != null && !clave.isBlank() && valor != null) {
                    atributos.put(clave, valor);
                }
            });
        }

        return atributos.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}
