package com.erp.apigateway.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metricas del borde, por ruta y por destino.
 *
 * <p>Sin ellas, el ASR QA-2 (100 usuarios concurrentes por taller, p95 menor
 * de 300 ms) no es MEDIBLE: no hay forma de distinguir un p99 causado por el
 * inventario de uno causado por el pool de conexiones (hallazgo 6.58).
 *
 * <p>Las etiquetas son un conjunto acotado (ruta, servicio, metodo, estado):
 * nunca un identificador de peticion ni de taller, que harian exploding el
 * numero de series.
 */
public class GatewayMetrics {

    private final MeterRegistry registry;
    private final Map<String, Timer> temporizadores = new ConcurrentHashMap<>();
    private final Map<String, Double> estadoDeCircuito = new ConcurrentHashMap<>();

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Registra una peticion atendida por una ruta.
     */
    public void peticionAtendida(String ruta, String servicio, String metodo, int estado, Duration duracion) {
        Timer temporizador = temporizadores.computeIfAbsent(
                ruta + "|" + servicio + "|" + metodo + "|" + estado,
                clave -> Timer.builder("gateway.route")
                        .description("Latencia de las peticiones atendidas por el borde, por ruta")
                        .tags(Tags.of(
                                "ruta", ruta,
                                "servicio", servicio,
                                "metodo", metodo,
                                "estado", String.valueOf(estado)))
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(registry));

        temporizador.record(duracion);

        if (estado >= 500) {
            registry.counter("gateway.route.errors", Tags.of(
                    "ruta", ruta,
                    "servicio", servicio,
                    "estado", String.valueOf(estado))).increment();
        }
    }

    /**
     * Registra el estado del interruptor de circuito de un destino.
     *
     * <p>El estado se publica como un numero: 0 cerrado, 1 abierto, 2 medio
     * abierto. Publicarlo permite ver, en un panel, cuantos destinos estan
     * aislando fallos.
     */
    public void estadoDelCircuito(String destino, String estado) {
        double valor = switch (estado) {
            case "OPEN" -> 1d;
            case "HALF_OPEN" -> 2d;
            case "FORCED_OPEN" -> 3d;
            case "DISABLED" -> 4d;
            default -> 0d;
        };
        estadoDeCircuito.put(destino, valor);
        registry.gauge("gateway.circuit.state", Tags.of("destino", destino), valor);
    }

    /**
     * Registra el fallo de un destino, con el error que lo produjo.
     */
    public void falloDeDestino(String destino, String errorCode) {
        registry.counter("gateway.destination.errors", Tags.of(
                "destino", destino,
                "error_code", errorCode)).increment();
    }
}
