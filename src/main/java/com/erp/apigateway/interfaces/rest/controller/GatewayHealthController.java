package com.erp.apigateway.interfaces.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway")
@Tag(name = "Gateway Health", description = "Endpoints de estado operativo del API Gateway")
public class GatewayHealthController {

    private final Instant startTime = Instant.now();

    @GetMapping("/status")
    @Operation(summary = "Consultar el estado del API Gateway")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "erp-api-gateway",
                "version", "1.0.0",
                "startedAt", startTime.toString(),
                "timestamp", Instant.now().toString(),
                "mode", "Multi-Tenant Perimeter Guardian"
        ));
    }
}
