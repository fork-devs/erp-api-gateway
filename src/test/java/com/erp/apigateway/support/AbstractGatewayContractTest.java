package com.erp.apigateway.support;

import com.erp.apigateway.domain.model.TenantQuota;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import java.util.List;
import java.util.UUID;

/**
 * Base de los tests de CONTRATO del borde.
 *
 * <p>La diferencia con los tests unitarios es que aqui entra una peticion
 * HTTP de verdad, atraviesa el borde completo y sale hacia un destino real.
 * Eso permite fijar el COMPORTAMIENTO del borde en un sitio: que un rol
 * insuficiente no llega al destino, que un taller suspendido no opera, que el
 * circuito deja de llamar a un destino caido.
 *
 * <p>Esta suite es la especificacion ejecutable del borde: es lo que permite
 * rehacerlo (en otro lenguaje, con otro framework o con otra arquitectura)
 * sin volver a deducir que tiene que hacer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "dev"})
public abstract class AbstractGatewayContractTest {

    /**
     * Puertos que la configuracion de produccion ya declara para cada
     * servicio. Los stubs escuchan en ellos, de modo que los tests no
     * sobreescriben la tabla de rutas: la configuracion que se prueba es la
     * de verdad, con una sola fuente de verdad.
     */
    protected static final int PUERTO_AUTH = 8081;
    protected static final int PUERTO_TALLERES = 8082;
    protected static final int PUERTO_FLEET = 8083;
    protected static final int PUERTO_INVENTARIO = 8084;
    protected static final int PUERTO_TELEMETRIA = 8087;
    protected static final int PUERTO_ORDENES = 8088;

    /** Emisor de identidades: sirve el conjunto de claves en el puerto del auth-service. */
    protected static final TestIdentityServer IDENTIDAD;
    /** Servicio de flota, destino de las rutas /api/v1/fleet. */
    protected static final StubService DESTINO;
    /** Servicio de inventario, destino de las rutas /api/v1/inventory. */
    protected static final StubService DESTINO_INVENTARIO;
    /** Servicio de talleres: del que el borde obtiene la cuota y el estado. */
    protected static final StubService SERVICIO_DE_TALLERES;
    /** Destino de las rutas de telemetría. */
    protected static final StubService DESTINO_TELEMETRIA;
    /** Destino de las rutas de ordenes de trabajo. */
    protected static final StubService DESTINO_ORDENES;

    static {
        try {
            IDENTIDAD = TestIdentityServer.arrancar(PUERTO_AUTH);
            DESTINO = StubService.arrancar(PUERTO_FLEET);
            DESTINO_INVENTARIO = StubService.arrancar(PUERTO_INVENTARIO);
            SERVICIO_DE_TALLERES = StubService.arrancar(PUERTO_TALLERES);
            DESTINO_TELEMETRIA = StubService.arrancar(PUERTO_TELEMETRIA);
            DESTINO_ORDENES = StubService.arrancar(PUERTO_ORDENES);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudieron arrancar los servicios de test", ex);
        }

        // Los stubs se detienen al terminar la JVM, NO al terminar una clase
        // de test: son compartidos, y pararlos en el primer @AfterAll dejaba
        // al resto de clases sin destino.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            IDENTIDAD.detener();
            DESTINO.detener();
            DESTINO_INVENTARIO.detener();
            DESTINO_TELEMETRIA.detener();
            DESTINO_ORDENES.detener();
            SERVICIO_DE_TALLERES.detener();
        }));
    }

    @DynamicPropertySource
    static void propiedadesDeTest(DynamicPropertyRegistry registro) {
        // El emisor de identidades se sirve en el puerto del auth-service, que
        // es donde la configuracion de produccion espera el conjunto de
        // claves: no hay que sobreescribir la tabla de rutas.
        registro.add("JWKS_URL", IDENTIDAD::jwksUrl);
        // Un circuito que se abre rapido, para poder comprobarlo sin esperar.
        registro.add("erp.gateway.resilience.circuit-breaker.defaults.minimum-number-of-calls", () -> 2);
        registro.add("erp.gateway.resilience.circuit-breaker.defaults.sliding-window-size", () -> 2);
        registro.add("erp.gateway.resilience.circuit-breaker.defaults.failure-rate-threshold", () -> 50f);
        registro.add("erp.gateway.resilience.circuit-breaker.defaults.wait-duration-in-open-state", () -> "1s");
        // Un pool de hilos de plataforma DELIBERADAMENTE pequeno: con 20
        // hilos, una rafaga de 100 peticiones concurrentes demuestra que el
        // techo del borde ya no son los hilos de plataforma (hallazgo 6.56).
        registro.add("server.tomcat.threads.max", () -> 20);
        // Topes del cuerpo para los tests: los marcadores de posicion son los
        // que declara la configuracion de produccion.
        registro.add("MAX_INBOUND_BODY_BYTES", () -> 1024);
        registro.add("MAX_OUTBOUND_BODY_BYTES", () -> 2048);
    }

    @LocalServerPort
    protected int puerto;

    /**
     * Cliente HTTP de verdad: la peticion entra al borde por el socket, como
     * entraria en produccion.
     */
    protected final HttpClient cliente = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void limpiarDestinos() {
        DESTINO.limpiar();
        DESTINO_INVENTARIO.limpiar();
        DESTINO_TELEMETRIA.limpiar();
        DESTINO_ORDENES.limpiar();
        SERVICIO_DE_TALLERES.limpiar();
        DESTINO.responderCon(200, "{\"ok\":true}");
        DESTINO.retardar(0);
        DESTINO_INVENTARIO.responderCon(200, "{\"ok\":true}");
        DESTINO_INVENTARIO.retardar(0);
        DESTINO_TELEMETRIA.responderCon(200, "{\"ok\":true}");
        DESTINO_TELEMETRIA.retardar(0);
        DESTINO_ORDENES.responderCon(200, "{\"ok\":true}");
        DESTINO_ORDENES.retardar(0);
        SERVICIO_DE_TALLERES.responderCon(200, cuerpoDeLimites("ACTIVE", "PRO", 500, 40));
    }


    /**
     * Cuerpo de la respuesta de limites del taller.
     */
    protected static String cuerpoDeLimites(String estado, String plan, int peticionesPorMinuto, int conexiones) {
        return """
                {
                  "tenantId": "%s",
                  "status": "%s",
                  "planCode": "%s",
                  "maxUsers": 20,
                  "maxVehicles": 5000,
                  "maxIotMsgPerDay": 100000,
                  "maxRequestsPerMinute": %d,
                  "maxConcurrentSlots": %d
                }
                """.formatted(UUID.randomUUID(), estado, plan, peticionesPorMinuto, conexiones);
    }

    protected Respuesta get(String ruta, String token) {
        return exchange("GET", ruta, null, token);
    }

    protected Respuesta post(String ruta, String cuerpo, String token) {
        return exchange("POST", ruta, cuerpo, token);
    }

    protected Respuesta exchange(String metodo, String ruta, String cuerpo, String token) {
        return exchange(metodo, ruta, cuerpo, token, java.util.Map.of());
    }

    protected Respuesta exchange(String metodo, String ruta, String cuerpo, String token,
                                 java.util.Map<String, String> cabecerasExtra) {
        HttpRequest.Builder peticion = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + puerto + ruta))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) {
            peticion.header("Authorization", "Bearer " + token);
        }
        cabecerasExtra.forEach(peticion::header);
        peticion.method(metodo, cuerpo == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(cuerpo));

        try {
            HttpResponse<String> respuesta = cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
            return new Respuesta(respuesta.statusCode(), respuesta.body(), respuesta.headers());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo completar la peticion de prueba: " + ex.getMessage(), ex);
        }
    }

    /**
     * Respuesta del borde.
     */
    public record Respuesta(int estado, String cuerpo, java.net.http.HttpHeaders cabeceras) {
        public String cabecera(String nombre) {
            return cabeceras.firstValue(nombre).orElse(null);
        }
    }

    /**
     * Identificadores de taller y de principal para un token valido.
     */
    protected static UUID tallerDePrueba() {
        return UUID.fromString("33333333-3333-3333-3333-333333333333");
    }

    protected static List<String> roles(String... roles) {
        return List.of(roles);
    }
}
