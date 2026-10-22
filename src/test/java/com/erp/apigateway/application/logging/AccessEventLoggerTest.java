package com.erp.apigateway.application.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El esquema del registro de acceso es el comun de la plataforma: pares
 * clave=valor. Es lo que permite auditar el trafico del borde y diagnosticar
 * un incidente (hallazgo 6.37).
 */
class AccessEventLoggerTest {

    @Test
    @DisplayName("Should format the access record with the common schema")
    void debeFormatearElRegistroConElEsquemaComun() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("ruta", "/api/v1/fleet/vehicles");
        extra.put("estado", "200");
        extra.put("duracion_ms", "42");

        String linea = AccessEventLogger.formatear(
                AccessEventLogger.EVENTO,
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "traza-123",
                "OK",
                extra);

        assertThat(linea).contains("evento=ACCESO_PETICION");
        assertThat(linea).contains("tenant_id=11111111-1111-1111-1111-111111111111");
        assertThat(linea).contains("user_id=22222222-2222-2222-2222-222222222222");
        assertThat(linea).contains("trace_id=traza-123");
        assertThat(linea).contains("resultado=OK");
        assertThat(linea).contains("ruta=/api/v1/fleet/vehicles");
        assertThat(linea).contains("estado=200");
        assertThat(linea).contains("duracion_ms=42");
        assertThat(linea).contains("timestamp=");
    }

    @Test
    @DisplayName("Should mark the missing identifiers with a dash")
    void debeMarcarLosIdentificadoresAusentesConUnGuion() {
        String linea = AccessEventLogger.formatear(AccessEventLogger.EVENTO, null, null, null, "OK", null);

        assertThat(linea).contains("tenant_id=-");
        assertThat(linea).contains("user_id=-");
        assertThat(linea).contains("trace_id=-");
    }
}
