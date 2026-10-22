package com.erp.apigateway.infrastructure.proxy;

import com.erp.apigateway.application.dto.OutboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.domain.exception.CircuitOpenException;
import com.erp.apigateway.domain.exception.TargetServiceUnavailableException;
import com.erp.apigateway.infrastructure.config.BodyLimits;
import com.erp.apigateway.infrastructure.config.GatewayResilienceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Aislamiento de fallos por destino (hallazgo 6.57).
 *
 * <p>Los destinos son REALES: un socket que acepta y cierra de inmediato
 * (falla de conexion) y un servidor HTTP que responde bien. Asi se comprueba
 * que el circuito evita de verdad la llamada, y no solo que el codigo dice
 * que la evita.
 */
class HttpReverseProxyAdapterTest {

    private HttpReverseProxyAdapter adapter;
    private ServerSocket destinoCaido;
    private HttpServer destinoSano;
    private final AtomicInteger llamadasAlCaido = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        GatewayResilienceProperties propiedades = new GatewayResilienceProperties();
        GatewayResilienceProperties.Settings ajustes = new GatewayResilienceProperties.Settings();
        ajustes.setFailureRateThreshold(50f);
        ajustes.setMinimumNumberOfCalls(2);
        ajustes.setSlidingWindowSize(2);
        ajustes.setWaitDurationInOpenState(Duration.ofMillis(500));
        ajustes.setPermittedNumberOfCallsInHalfOpenState(1);
        propiedades.getCircuitBreaker().setDefaults(ajustes);
        propiedades.getTimeLimiter().setMargin(Duration.ofMillis(100));

        adapter = new HttpReverseProxyAdapter(propiedades);

        // Destino que falla: acepta la conexion y la cierra de inmediato.
        destinoCaido = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!destinoCaido.isClosed()) {
                try (Socket socket = destinoCaido.accept()) {
                    llamadasAlCaido.incrementAndGet();
                } catch (IOException ex) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        // Destino sano.
        destinoSano = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destinoSano.createContext("/", exchange -> {
            byte[] cuerpo = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, cuerpo.length);
            try (OutputStream salida = exchange.getResponseBody()) {
                salida.write(cuerpo);
            }
        });
        destinoSano.start();
    }

    @AfterEach
    void tearDown() {
        try {
            destinoCaido.close();
        } catch (IOException ignored) {
            // al cerrar el socket del destino que falla
        }
        destinoSano.stop(0);
    }

    @Test
    @DisplayName("Should stop calling a destination once its circuit is open")
    void debeDejarDeLlamarAlDestinoCuandoSuCircuitoSeAbre() {
        String url = "http://127.0.0.1:" + destinoCaido.getLocalPort() + "/api/v1/fleet";

        // Dos fallos: el circuito se abre (umbral 50 %, minimo 2 llamadas).
        assertThrows(TargetServiceUnavailableException.class, () -> adapter.forward(peticion(url, 500)));
        assertThrows(TargetServiceUnavailableException.class, () -> adapter.forward(peticion(url, 500)));

        int llamadasAlAbrir = llamadasAlCaido.get();
        assertThat(llamadasAlAbrir).isGreaterThanOrEqualTo(2);

        // La siguiente peticion NO debe llegar al destino: el circuito esta
        // abierto.
        CircuitOpenException excepcion = assertThrows(CircuitOpenException.class,
                () -> adapter.forward(peticion(url, 500)));

        assertThat(excepcion.getErrorCode()).isEqualTo("CIRCUIT_OPEN");
        assertThat(llamadasAlCaido.get())
                .as("con el circuito abierto el destino no debe recibir la peticion")
                .isEqualTo(llamadasAlAbrir);
    }

    @Test
    @DisplayName("Should not degrade the traffic of another destination")
    void noDebeDegradarElTraficoDeOtroDestino() {
        String urlCaida = "http://127.0.0.1:" + destinoCaido.getLocalPort() + "/api/v1/fleet";
        String urlSana = "http://127.0.0.1:" + destinoSano.getAddress().getPort() + "/api/v1/inventory";

        assertThrows(TargetServiceUnavailableException.class, () -> adapter.forward(peticion(urlCaida, 500)));
        assertThrows(TargetServiceUnavailableException.class, () -> adapter.forward(peticion(urlCaida, 500)));
        assertThrows(CircuitOpenException.class, () -> adapter.forward(peticion(urlCaida, 500)));

        // El otro destino, al que no le afecta el circuito del primero, sigue
        // funcionando con normalidad.
        ProxyResponse respuesta = adapter.forward(peticion(urlSana, 500));
        assertThat(respuesta.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should close the circuit again after the wait")
    void debeCerrarElCircuitoTrasLaEspera() throws InterruptedException {
        String url = "http://127.0.0.1:" + destinoCaido.getLocalPort() + "/api/v1/fleet";

        assertThrows(TargetServiceUnavailableException.class, () -> adapter.forward(peticion(url, 500)));
        assertThrows(TargetServiceUnavailableException.class, () -> adapter.forward(peticion(url, 500)));
        assertThrows(CircuitOpenException.class, () -> adapter.forward(peticion(url, 500)));

        // Tras la espera de apertura, el circuito deja pasar una llamada de
        // prueba: si el destino sigue failing, vuelve a abrirse, y el fallo
        // se sigue propagando como fallo de destino y no como circuito
        // abierto.
        Thread.sleep(700);

        assertThrows(TargetServiceUnavailableException.class, () -> adapter.forward(peticion(url, 500)));
    }

    @Test
    @DisplayName("Should fail when the destination returns a body over the limit")
    void debeFallarCuandoElDestinoDevuelveUnCuerpoQueExcedeElTope() throws IOException {
        // Destino que devuelve un cuerpo mayor que el tope admitido.
        HttpServer enorme = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] cuerpoGrande = new byte[4096];
        enorme.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, cuerpoGrande.length);
            try (OutputStream salida = exchange.getResponseBody()) {
                salida.write(cuerpoGrande);
            }
        });
        enorme.start();

        try {
            GatewayResilienceProperties propiedades = new GatewayResilienceProperties();
            HttpReverseProxyAdapter limitado = new HttpReverseProxyAdapter(
                    propiedades, new BodyLimits(1024, 1024));

            String url = "http://127.0.0.1:" + enorme.getAddress().getPort() + "/api/v1/inventory";

            // El borde no acumula el cuerpo entero: falla y lo reporta como
            // fallo del destino, no como una respuesta truncada.
            TargetServiceUnavailableException excepcion = assertThrows(
                    TargetServiceUnavailableException.class, () -> limitado.forward(peticion(url, 500)));

            assertThat(excepcion.getErrorCode()).isEqualTo("TARGET_SERVICE_UNAVAILABLE");
        } finally {
            enorme.stop(0);
        }
    }

    private OutboundRequest peticion(String url, int timeoutMs) {
        return new OutboundRequest(url, "GET", Map.of("X-Trace-ID", List.of("traza")), new byte[0], timeoutMs);
    }
}
