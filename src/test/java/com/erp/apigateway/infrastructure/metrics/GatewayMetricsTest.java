package com.erp.apigateway.infrastructure.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las metricas por ruta son la condicion previa para poder MEDIR el ASR
 * QA-2: sin ellas no hay forma de distinguir un p99 causado por el inventario
 * de uno causado por el pool de conexiones (hallazgo 6.58).
 */
class GatewayMetricsTest {

    private SimpleMeterRegistry registry;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new GatewayMetrics(registry);
    }

    @Test
    @DisplayName("Should publish a timer with percentiles per route")
    void debePublicarUnTemporizadorConPercentilesPorRuta() {
        metrics.peticionAtendida("fleet-service", "fleet-service", "GET", 200, Duration.ofMillis(120));

        io.micrometer.core.instrument.Timer temporizador = registry.find("gateway.route").timer();
        assertThat(temporizador).isNotNull();
        assertThat(temporizador.getId().getTag("ruta")).isEqualTo("fleet-service");
        assertThat(temporizador.getId().getTag("servicio")).isEqualTo("fleet-service");
        assertThat(temporizador.getId().getTag("metodo")).isEqualTo("GET");
        assertThat(temporizador.getId().getTag("estado")).isEqualTo("200");

        // El percentil es lo que hace falta para poder leer un p95 o un p99.
        assertThat(temporizador.takeSnapshot().percentileValues())
                .as("sin percentiles, el ASR QA-2 no se puede evaluar")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Should count the errors of a route")
    void debeContarLosErroresDeUnaRuta() {
        metrics.peticionAtendida("inventory-service", "inventory-service", "GET", 503, Duration.ofMillis(5));

        assertThat(registry.find("gateway.route.errors").counter()).isNotNull();
        assertThat(registry.find("gateway.route.errors").counter().count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("Should publish the state of the circuit per destination")
    void debePublicarElEstadoDelCircuitoPorDestino() {
        metrics.estadoDelCircuito("localhost:8084", "OPEN");
        metrics.estadoDelCircuito("localhost:8083", "CLOSED");

        assertThat(registry.find("gateway.circuit.state").tag("destino", "localhost:8084").gauge())
                .isNotNull();
        assertThat(registry.find("gateway.circuit.state").tag("destino", "localhost:8084").gauge().value())
                .isEqualTo(1d);
        assertThat(registry.find("gateway.circuit.state").tag("destino", "localhost:8083").gauge().value())
                .isZero();
    }

    @Test
    @DisplayName("Should count the errors of a destination")
    void debeContarLosErroresDeUnDestino() {
        metrics.falloDeDestino("localhost:8084", "TARGET_SERVICE_UNAVAILABLE");

        assertThat(registry.find("gateway.destination.errors").counter().count()).isEqualTo(1d);
    }
}
