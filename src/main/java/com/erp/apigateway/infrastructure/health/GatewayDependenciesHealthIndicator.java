package com.erp.apigateway.infrastructure.health;

import com.erp.apigateway.infrastructure.config.GatewayProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Salud REAL de las dependencias del borde.
 *
 * <p>El indicador de salud era un stub que respondia siempre "UP": un
 * orquestador recibia la misma respuesta con el tenant-service caido que con
 * todo en pie (hallazgo 6.37).
 */
@Component("gatewayDependencies")
public class GatewayDependenciesHealthIndicator implements HealthIndicator {

    private final GatewayProperties gatewayProperties;
    private final HttpClient httpClient;

    public GatewayDependenciesHealthIndicator(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        boolean jwksOk = alcanzable(gatewayProperties.getJwksUrl());
        builder.withDetail("conjuntoDeClaves", jwksOk ? "alcanzable" : "NO ALCANZABLE");

        boolean tenantsOk = alcanzable(gatewayProperties.getTenantServiceUrl() + "/actuator/health");
        builder.withDetail("servicioDeTalleres", tenantsOk ? "alcanzable" : "NO ALCANZABLE");

        if (!jwksOk || !tenantsOk) {
            return builder.down()
                    .withDetail("motivo", "Una dependencia del borde no responde")
                    .build();
        }
        return builder.build();
    }

    private boolean alcanzable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            HttpRequest peticion = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> respuesta = httpClient.send(peticion, HttpResponse.BodyHandlers.discarding());
            return respuesta.statusCode() < 500;
        } catch (Exception ex) {
            return false;
        }
    }
}
