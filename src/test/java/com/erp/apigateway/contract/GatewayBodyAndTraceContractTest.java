package com.erp.apigateway.contract;

import com.erp.apigateway.support.AbstractGatewayContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRATO DEL BORDE — cuerpos y trazabilidad.
 */
class GatewayBodyAndTraceContractTest extends AbstractGatewayContractTest {

    @Test
    @DisplayName("Un cuerpo entrante por encima del tope recibe 413 y no llega al destino")
    void unCuerpoEntrantePorEncimaDelTopeRecibe413() {
        String token = IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO"));
        // El tope del perfil de test es de 1024 bytes.
        String cuerpo = "x".repeat(2048);

        Respuesta respuesta = post("/api/v1/telemetry/lecturas", cuerpo, token);

        assertThat(respuesta.estado()).isEqualTo(413);
        assertThat(respuesta.cuerpo()).contains("PAYLOAD_TOO_LARGE");
        assertThat(DESTINO_TELEMETRIA.llamadasRecibidas())
                .as("un cuerpo que excede el tope no debe tocar el destino")
                .isZero();
    }

    @Test
    @DisplayName("Una respuesta del destino por encima del tope se reporta como fallo del destino")
    void unaRespuestaPorEncimaDelTopeSeReportaComoFallo() {
        DESTINO_TELEMETRIA.responderCon(200, "y".repeat(8192));

        Respuesta respuesta = get("/api/v1/telemetry/lecturas",
                IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO")));

        assertThat(respuesta.estado()).isEqualTo(502);
        assertThat(respuesta.cuerpo()).contains("TARGET_SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("El contexto de traza del cliente se continua, no se inventa")
    void elContextoDeTrazaDelClienteSeContinua() {
        // Ruta de flota: el destino de telemetria falla a proposito en el
        // otro escenario y su circuito quedaria abierto.
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        Respuesta respuesta = exchange("GET", "/api/v1/fleet/vehicles", null,
                IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO")),
                java.util.Map.of("traceparent", traceparent));

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(DESTINO.llamadasRecibidas()).isEqualTo(1);

        String propagado = DESTINO.ultimaPeticion().cabecera("traceparent");
        assertThat(propagado).isNotNull();
        assertThat(propagado).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");
        assertThat(propagado)
                .as("el span es nuevo: el del cliente no se reutiliza")
                .doesNotContain("00f067aa0ba902b7");
        assertThat(respuesta.cabecera("X-Trace-ID")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("Sin contexto del cliente, el borde genera uno propio")
    void sinContextoDelClienteElBordeGeneraUnoPropio() {
        Respuesta respuesta = get("/api/v1/fleet/vehicles",
                IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO")));

        assertThat(respuesta.estado()).isEqualTo(200);
        String propagado = DESTINO.ultimaPeticion().cabecera("traceparent");
        assertThat(propagado).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }
}
