package com.erp.apigateway.interfaces.rest.controller;

import com.erp.apigateway.infrastructure.health.GatewayDependenciesHealthIndicator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Estado operativo del borde.
 *
 * <p>Antes era un stub que respondia SIEMPRE "UP", sin comprobar ninguna
 * dependencia: un orquestador recibia la misma respuesta con el tenant-service
 * caido que con todo en pie (hallazgo 6.37).
 */
@RestController
@RequestMapping("/api/v1/gateway")
@Tag(name = "Gateway Health", description = "Endpoints de estado operativo del API Gateway")
public class GatewayHealthController {

    private final Instant startTime = Instant.now();
    private final GatewayDependenciesHealthIndicator dependenciesHealthIndicator;

    public GatewayHealthController(GatewayDependenciesHealthIndicator dependenciesHealthIndicator) {
        this.dependenciesHealthIndicator = dependenciesHealthIndicator;
    }

    @GetMapping("/status")
    @Operation(summary = "Consultar el estado del API Gateway")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Health salud = dependenciesHealthIndicator.health();
        boolean arriba = salud.getStatus() == Status.UP;

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("status", arriba ? "UP" : "DOWN");
        respuesta.put("service", "erp-api-gateway");
        respuesta.put("version", "1.0.0");
        respuesta.put("startedAt", startTime.toString());
        respuesta.put("timestamp", Instant.now().toString());
        respuesta.put("mode", "Multi-Tenant Perimeter Guardian");
        respuesta.put("dependencies", salud.getDetails());

        return ResponseEntity.status(arriba ? 200 : 503).body(respuesta);
    }
}
