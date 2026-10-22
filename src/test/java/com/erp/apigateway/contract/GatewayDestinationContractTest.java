package com.erp.apigateway.contract;

import com.erp.apigateway.support.AbstractGatewayContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRATO DEL BORDE — destinos.
 *
 * <p>Un destino caido no puede arrastrar al resto del borde: por eso hay un
 * interruptor de circuito por destino (hallazgo 6.57).
 */
class GatewayDestinationContractTest extends AbstractGatewayContractTest {

    @Test
    @DisplayName("Un destino que no responde produce 502 con codigo de error estable")
    void unDestinoQueNoRespondeProduce502() {
        // La ruta de facturacion apunta al 8086, donde no hay nada: la
        // conexion se rechaza.
        Respuesta respuesta = get("/api/v1/billing/invoices",
                IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO")));

        assertThat(respuesta.estado()).isEqualTo(502);
        assertThat(respuesta.cuerpo()).contains("TARGET_SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("Un destino lento no agota el plazo de la ruta")
    void unDestinoLentoNoAgotaElPlazo() {
        // El plazo de la ruta de flota es de 500 ms; el destino tarda 2 s.
        DESTINO_ORDENES.retardar(2000);

        long inicio = System.nanoTime();
        Respuesta respuesta = get("/api/v1/work-orders/123",
                IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO")));
        long duracionMs = (System.nanoTime() - inicio) / 1_000_000L;

        assertThat(respuesta.estado()).isEqualTo(502);
        assertThat(duracionMs)
                .as("el borde no puede tardar mas que el plazo de la ruta")
                .isLessThan(1500);
    }

    @Test
    @DisplayName("Tras varios fallos, el circuito abre y el destino deja de recibir peticiones")
    void trasVariosFallosElCircuitoAbre() {
        // Cada escenario usa un destino distinto: el circuito es por destino y
        // su estado lo ve el resto de los escenarios de la clase. Este
        // destino tiene un plazo de 500 ms, de modo que el retraso lo supera.
        DESTINO_INVENTARIO.retardar(2000);
        String token = IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO"));

        // Dos fallos: el circuito se abre (minimo 2 llamadas, umbral 50 %).
        get("/api/v1/inventory/parts", token);
        get("/api/v1/inventory/parts", token);

        int llamadasAlAbrir = DESTINO_INVENTARIO.llamadasRecibidas();

        Respuesta conCircuitoAbierto = get("/api/v1/inventory/parts", token);

        assertThat(conCircuitoAbierto.estado()).isEqualTo(503);
        assertThat(conCircuitoAbierto.cuerpo()).contains("CIRCUIT_OPEN");
        assertThat(conCircuitoAbierto.cabecera("Retry-After")).isNotNull();
        assertThat(DESTINO_INVENTARIO.llamadasRecibidas())
                .as("con el circuito abierto el destino no debe recibir la peticion")
                .isEqualTo(llamadasAlAbrir);
    }

    @Test
    @DisplayName("Una ruta no declarada se rechaza y no se reenvia a ningun destino")
    void unaRutaNoDeclaradaSeRechaza() {
        Respuesta respuesta = get("/api/v1/no-existe/nada",
                IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO")));

        assertThat(respuesta.estado()).isEqualTo(502);
        assertThat(DESTINO_INVENTARIO.llamadasRecibidas())
                .as("una ruta no declarada no se reenvia a ningun destino por defecto")
                .isZero();
    }
}
