package com.erp.apigateway.infrastructure.proxy;

import com.erp.apigateway.application.dto.OutboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.domain.exception.TargetServiceUnavailableException;
import com.erp.apigateway.domain.port.ReverseProxyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HttpReverseProxyAdapter implements ReverseProxyPort {

    private static final Logger log = LoggerFactory.getLogger(HttpReverseProxyAdapter.class);

    private static final Set<String> RESTRICTED_FORWARD_HEADERS = Set.of(
            "content-length",
            "host",
            "connection",
            "upgrade"
    );

    private final HttpClient httpClient;

    public HttpReverseProxyAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpReverseProxyAdapter(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProxyResponse forward(OutboundRequest request) {
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

            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

            return new ProxyResponse(
                    response.statusCode(),
                    response.headers().map(),
                    response.body() != null ? response.body() : new byte[0]
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TargetServiceUnavailableException("Petición interrumpida al comunicar con servicio destino: " + request.targetUrl(), e);
        } catch (Exception e) {
            log.error("Error al comunicar con servicio downstream ({}): {}", request.targetUrl(), e.getMessage());
            throw new TargetServiceUnavailableException("El microservicio destino no está disponible o timeout (" + request.targetUrl() + ")", e);
        }
    }
}
