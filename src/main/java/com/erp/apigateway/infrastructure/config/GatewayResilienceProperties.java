package com.erp.apigateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Parametros del interruptor de circuito y del interruptor de tiempo del
 * borde.
 *
 * <p>El interruptor de circuito AISLA los fallos de un destino: cuando un
 * servicio deja de responder, el borde deja de llamarlo en vez de esperar su
 * timeout en cada peticion. Sin el, una caida de un destino degrada el
 * trafico que no tiene nada que ver con ese destino (hallazgo 6.57).
 */
@ConfigurationProperties(prefix = "erp.gateway.resilience")
public class GatewayResilienceProperties {

    private final CircuitBreakerSettings circuitBreaker = new CircuitBreakerSettings();
    private final TimeLimiterSettings timeLimiter = new TimeLimiterSettings();

    public CircuitBreakerSettings getCircuitBreaker() {
        return circuitBreaker;
    }

    public TimeLimiterSettings getTimeLimiter() {
        return timeLimiter;
    }

    public static class CircuitBreakerSettings {

        /**
         * Parametros por defecto, aplicables a todos los destinos.
         */
        private Settings defaults = new Settings();

        /**
         * Parametros por DESTINO (clave: host:puerto del servicio). Un
         * destino que necesita mas margen de error que el resto lo declara
         * aqui.
         */
        private Map<String, Settings> destinations = new HashMap<>();

        public Settings getDefaults() {
            return defaults;
        }

        public void setDefaults(Settings defaults) {
            this.defaults = defaults;
        }

        public Map<String, Settings> getDestinations() {
            return destinations;
        }

        public void setDestinations(Map<String, Settings> destinations) {
            this.destinations = destinations;
        }

        public Settings paraDestino(String destino) {
            Settings especificos = destinations.get(destino);
            return especificos != null ? especificos : defaults;
        }
    }

    public static class Settings {

        /**
         * Porcentaje de llamadas fallidas a partir del cual el circuito se
         * abre.
         */
        private float failureRateThreshold = 50f;

        /**
         * Numero minimo de llamadas antes de evaluar el porcentaje: sin un
         * minimo, un unico fallo abriria el circuito.
         */
        private int minimumNumberOfCalls = 5;

        /**
         * Tamano de la ventana deslizante que se mira para calcular el
         * porcentaje de fallos.
         */
        private int slidingWindowSize = 10;

        /**
         * Tiempo que el circuito permanece abierto antes de dejar pasar una
         * llamada de prueba.
         */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        /**
         * Llamadas de prueba que se dejan pasar con el circuito medio abierto.
         */
        private int permittedNumberOfCallsInHalfOpenState = 2;

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }
    }

    public static class TimeLimiterSettings {

        /**
         * Margen que se anade al plazo de la ruta para el interruptor de
         * tiempo. El plazo de la ruta lo aplica el cliente HTTP; este es el
         * RED que impide que la llamada bloqueada lo exceda.
         */
        private Duration margin = Duration.ofMillis(250);

        public Duration getMargin() {
            return margin;
        }

        public void setMargin(Duration margin) {
            this.margin = margin;
        }
    }
}
