package com.erp.apigateway.infrastructure.proxy;

import com.erp.apigateway.application.dto.OutboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.domain.exception.CircuitOpenException;
import com.erp.apigateway.domain.exception.PayloadTooLargeException;
import com.erp.apigateway.domain.exception.TargetServiceUnavailableException;
import com.erp.apigateway.domain.port.ReverseProxyPort;
import com.erp.apigateway.infrastructure.config.BodyLimits;
import com.erp.apigateway.infrastructure.config.GatewayResilienceProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public class HttpReverseProxyAdapter implements ReverseProxyPort {

    private static final Logger log = LoggerFactory.getLogger(HttpReverseProxyAdapter.class);

    private static final Set<String> RESTRICTED_FORWARD_HEADERS = Set.of(
            "content-length",
            "host",
            "connection",
            "upgrade"
    );

    private final HttpClient httpClient;
    private final CircuitBreakerRegistry circuitBreakers;
    private final Map<String, TimeLimiter> timeLimiters;
    private final GatewayResilienceProperties resilienceProperties;
    private final BodyLimits bodyLimits;
    /**
     * El interruptor de tiempo ejecuta la llamada bloqueante en un hilo
     * virtual: el portador se libera mientras espera, que es justo lo que
     * hace que el borde escale.
     */
    private final ExecutorService executor;

    public HttpReverseProxyAdapter(GatewayResilienceProperties resilienceProperties) {
        this(resilienceProperties, BodyLimits.porDefecto());
    }

    public HttpReverseProxyAdapter(GatewayResilienceProperties resilienceProperties, BodyLimits bodyLimits) {
        this.resilienceProperties = resilienceProperties;
        this.bodyLimits = bodyLimits;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        this.timeLimiters = new ConcurrentHashMap<>();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public HttpReverseProxyAdapter(
            HttpClient httpClient,
            GatewayResilienceProperties resilienceProperties,
            BodyLimits bodyLimits,
            CircuitBreakerRegistry circuitBreakers,
            Map<String, TimeLimiter> timeLimiters,
            ExecutorService executor) {
        this.httpClient = httpClient;
        this.resilienceProperties = resilienceProperties;
        this.bodyLimits = bodyLimits;
        this.circuitBreakers = circuitBreakers;
        this.timeLimiters = timeLimiters;
        this.executor = executor;
    }

    @Override
    public ProxyResponse forward(OutboundRequest request) {
        String destino = destinoDe(request.targetUrl());
        CircuitBreaker interruptor = circuitBreakers.circuitBreaker(destino, () -> configuracionDelCircuito(destino));
        String clave = destino + "@" + request.timeoutMs();
        TimeLimiter limitador = timeLimiters.computeIfAbsent(clave, this::crearInterruptorDeTiempo);

        try {
            return limitador.executeFutureSupplier(() -> CompletableFuture.supplyAsync(
                    () -> interruptor.executeSupplier(() -> enviar(request)),
                    executor));
        } catch (CallNotPermittedException ex) {
            // El circuito esta abierto: el destino no se llama.
            log.warn("Circuito abierto para el destino {}: no se reenvia la peticion", destino);
            throw new CircuitOpenException(
                    "El servicio destino no esta disponible temporalmente: " + destino);
        } catch (TimeoutException ex) {
            log.warn("Se excedio el plazo del interruptor de tiempo para el destino {}", destino);
            throw new TargetServiceUnavailableException(
                    "El microservicio destino excedio su plazo (" + request.targetUrl() + ")", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TargetServiceUnavailableException(
                    "Peticion interrumpida al comunicar con servicio destino: " + request.targetUrl(), ex);
        } catch (Exception e) {
            log.error("Error al comunicar con servicio downstream ({}): {}", request.targetUrl(), e.getMessage());
            throw new TargetServiceUnavailableException(
                    "El microservicio destino no esta disponible o timeout (" + request.targetUrl() + ")", e);
        }
    }

    /**
     * La llamada real al destino. Es bloqueante a proposito: con hilos
     * virtuales, el puerto de plataforma se libera mientras espera.
     */
    private ProxyResponse enviar(OutboundRequest request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.targetUrl()))
                    .timeout(Duration.ofMillis(request.timeoutMs()));

            // Construir cuerpo según método HTTP
            HttpRequest.BodyPublisher bodyPublisher;
            if ("GET".equalsIgnoreCase(request.method()) || "HEAD".equalsIgnoreCase(request.method())) {
                bodyPublisher = HttpRequest.BodyPublishers.noBody();
            } else {
                bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(request.body() != null ? request.body() : new byte[0]);
            }
            builder.method(request.method().toUpperCase(), bodyPublisher);

            // Inyectar cabeceras
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                String headerName = entry.getKey();
                if (!RESTRICTED_FORWARD_HEADERS.contains(headerName.toLowerCase()) && entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        if (value != null && !value.trim().isEmpty()) {
                            try {
                                builder.header(headerName, value);
                            } catch (IllegalArgumentException ex) {
                                log.debug("Omitiendo cabecera restringida por el cliente HTTP: {}", headerName);
                            }
                        }
                    }
                }
            }

            HttpRequest httpRequest = builder.build();
            log.debug("Despachando proxy {} hacia {}", httpRequest.method(), httpRequest.uri());

            // La respuesta se lee como FLUJO y con TOPE: con
            // BodyHandlers.ofByteArray() el cuerpo entero del destino queda
            // acumulado en la heap del borde, sin limite (hallazgo 6.33).
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] cuerpo = leerConTope(response.body(), bodyLimits.outboundBytes());

            return new ProxyResponse(
                    response.statusCode(),
                    response.headers().map(),
                    cuerpo
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TargetServiceUnavailableException("Petición interrumpida al comunicar con servicio destino: " + request.targetUrl(), e);
        } catch (Exception e) {
            log.error("Error al comunicar con servicio downstream ({}): {}", request.targetUrl(), e.getMessage());
            throw new TargetServiceUnavailableException("El microservicio destino no está disponible o timeout (" + request.targetUrl() + ")", e);
        }
    }

    /**
     * Lee el cuerpo de la respuesta con un tope de bytes.
     *
     * <p>Si el destino devuelve mas de lo que el borde admite, la lectura
     * falla y el reenvio se reporta como fallo del destino: el borde NO
     * trunca en silencio una respuesta, porque un cliente que recibe media
     * respuesta sin saberlo es peor que un error.
     */
    private byte[] leerConTope(InputStream flujo, int maxBytes) throws IOException {
        if (flujo == null) {
            return new byte[0];
        }
        try (InputStream entrada = flujo) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream acumulado = new java.io.ByteArrayOutputStream();
            int leidos;
            while ((leidos = entrada.read(buffer)) != -1) {
                if (acumulado.size() + leidos > maxBytes) {
                    throw new IOException("La respuesta del servicio destino excede el tope de " + maxBytes + " bytes");
                }
                acumulado.write(buffer, 0, leidos);
            }
            return acumulado.toByteArray();
        }
    }

    /**
     * Configuracion del interruptor por DESTINO. Cada destino tiene el suyo:
     * un destino caido no puede abrir el circuito de los demas.
     */
    private CircuitBreakerConfig configuracionDelCircuito(String destino) {
        GatewayResilienceProperties.Settings ajustes =
                resilienceProperties.getCircuitBreaker().paraDestino(destino);

        return CircuitBreakerConfig.custom()
                .failureRateThreshold(ajustes.getFailureRateThreshold())
                .minimumNumberOfCalls(ajustes.getMinimumNumberOfCalls())
                .slidingWindowSize(ajustes.getSlidingWindowSize())
                .waitDurationInOpenState(ajustes.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(ajustes.getPermittedNumberOfCallsInHalfOpenState())
                .build();
    }

    private TimeLimiterConfig configuracionDelInterruptorDeTiempo(String clave) {
        return TimeLimiterConfig.custom()
                .timeoutDuration(plazoDeLaRuta(clave))
                .cancelRunningFuture(true)
                .build();
    }

    private TimeLimiter crearInterruptorDeTiempo(String clave) {
        return TimeLimiter.of(clave, configuracionDelInterruptorDeTiempo(clave));
    }

    /**
     * El plazo del interruptor de tiempo es el de la ruta mas un margen: el
     * plazo de la ruta lo aplica el cliente HTTP, y este es el red que
     * impide que la llamada bloqueada lo exceda.
     */
    private Duration plazoDeLaRuta(String clave) {
        int indice = clave.lastIndexOf('@');
        int timeoutMs = indice > 0 ? Integer.parseInt(clave.substring(indice + 1)) : 10000;
        return Duration.ofMillis(timeoutMs).plus(resilienceProperties.getTimeLimiter().getMargin());
    }

    /**
     * Identificador del destino: host y puerto. Es la granularidad correcta
     * para un circuito: dos rutas al mismo servicio comparten destino.
     */
    private String destinoDe(String targetUrl) {
        try {
            URI uri = URI.create(targetUrl);
            int puerto = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme() != null && uri.getScheme().equals("https") ? 443 : 80);
            return uri.getHost() + ":" + puerto;
        } catch (RuntimeException ex) {
            return targetUrl;
        }
    }
}
