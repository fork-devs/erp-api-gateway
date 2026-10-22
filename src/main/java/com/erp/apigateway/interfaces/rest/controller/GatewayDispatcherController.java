package com.erp.apigateway.interfaces.rest.controller;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.application.usecase.ExecuteBulkheadUseCase;
import com.erp.apigateway.application.usecase.ExecuteRateLimitUseCase;
import com.erp.apigateway.application.usecase.RouteGatewayRequestUseCase;
import com.erp.apigateway.application.usecase.ValidateGatewayRequestUseCase;
import com.erp.apigateway.domain.exception.TargetServiceUnavailableException;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.TenantQuotaPort;
import com.erp.apigateway.infrastructure.config.GatewayProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@Tag(name = "Gateway Dispatcher", description = "Punto de entrada perimetral con validación JWKS, rate limiting y aislamiento multi-tenant")
public class GatewayDispatcherController {

    private static final Logger log = LoggerFactory.getLogger(GatewayDispatcherController.class);

    /**
     * Rutas que se sirven sin autenticacion.
     *
     * <p>Cada una tiene que existir en el servicio de destino. Una ruta
     * declarada como publica que no existe no es un problema de seguridad
     * (no hay nada que proteger), pero es un fallo de configuracion que no
     * se ve: la peticion simplemente no encuentra nada.
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/.well-known/jwks.json",
            "/api/v1/plans"
    );

    private final GatewayProperties gatewayProperties;
    private final ValidateGatewayRequestUseCase validateGatewayRequestUseCase;
    private final ExecuteRateLimitUseCase executeRateLimitUseCase;
    private final ExecuteBulkheadUseCase executeBulkheadUseCase;
    private final RouteGatewayRequestUseCase routeGatewayRequestUseCase;
    private final TenantQuotaPort tenantQuotaPort;

    public GatewayDispatcherController(
            GatewayProperties gatewayProperties,
            ValidateGatewayRequestUseCase validateGatewayRequestUseCase,
            ExecuteRateLimitUseCase executeRateLimitUseCase,
            ExecuteBulkheadUseCase executeBulkheadUseCase,
            RouteGatewayRequestUseCase routeGatewayRequestUseCase,
            TenantQuotaPort tenantQuotaPort) {
        this.gatewayProperties = gatewayProperties;
        this.validateGatewayRequestUseCase = validateGatewayRequestUseCase;
        this.executeRateLimitUseCase = executeRateLimitUseCase;
        this.executeBulkheadUseCase = executeBulkheadUseCase;
        this.routeGatewayRequestUseCase = routeGatewayRequestUseCase;
        this.tenantQuotaPort = tenantQuotaPort;
    }

    @RequestMapping(value = {"/api/v1/**", "/.well-known/**"})
    @Operation(summary = "Despachador central de peticiones hacia microservicios downstream")
    public ResponseEntity<byte[]> dispatch(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {

        String path = request.getRequestURI();
        String method = request.getMethod();
        String queryString = request.getQueryString();
        String traceId = extractOrGenerateTraceId(request);

        RouteTarget target = resolveTarget(path);
        if (target == null) {
            throw new TargetServiceUnavailableException("No existe ruta configurada en el gateway para el path: " + path);
        }

        Map<String, List<String>> headers = extractHeaders(request);
        InboundRequest inboundRequest = new InboundRequest(
                method,
                path,
                queryString,
                headers,
                body != null ? body : new byte[0],
                request.getRemoteAddr()
        );

        // 1. Validar autenticación y estado del tenant
        TokenClaims claims = validateGatewayRequestUseCase.validateRequest(inboundRequest, target);

        // 2. Control de tráfico y concurrencia por taller (si está autenticado)
        UUID tenantId = claims != null ? claims.getTenantId() : null;
        if (tenantId != null) {
            TenantQuota quota = tenantQuotaPort.getQuota(tenantId, bearerTokenOf(inboundRequest));
            executeRateLimitUseCase.evaluateRateLimit(tenantId, quota);
            executeBulkheadUseCase.acquireSlot(tenantId, quota.getMaxConcurrentSlots());
        }

        try {
            // 3. Enrutar y despachar petición al microservicio destino
            ProxyResponse proxyResponse = routeGatewayRequestUseCase.route(inboundRequest, target, claims, traceId);

            // 4. Construir respuesta con cabeceras downstream y trazabilidad
            HttpHeaders responseHeaders = new HttpHeaders();
            for (Map.Entry<String, List<String>> entry : proxyResponse.headers().entrySet()) {
                String headerKey = entry.getKey();
                if (!isRestrictedResponseHeader(headerKey)) {
                    responseHeaders.put(headerKey, entry.getValue());
                }
            }
            responseHeaders.set("X-Trace-ID", traceId);

            return ResponseEntity.status(proxyResponse.statusCode())
                    .headers(responseHeaders)
                    .body(proxyResponse.body());

        } finally {
            if (tenantId != null) {
                executeBulkheadUseCase.releaseSlot(tenantId);
            }
        }
    }

    private RouteTarget resolveTarget(String path) {
        // 1. Verificar si coincide con alguna ruta configurada en application.yml
        for (Map.Entry<String, GatewayProperties.ServiceRoute> entry : gatewayProperties.getRoutes().entrySet()) {
            GatewayProperties.ServiceRoute route = entry.getValue();
            if (path.startsWith(route.getPathPrefix())) {
                boolean requiresAuth = route.isRequiresAuth() && !isPublicEndpoint(path);
                return new RouteTarget(entry.getKey(), route.getTargetUrl(), route.getTimeoutMs(), requiresAuth);
            }
        }

        // 2. Rutas por defecto del ecosistema ERP
        if (path.startsWith("/api/v1/auth") || path.startsWith("/.well-known")) {
            boolean requiresAuth = !isPublicEndpoint(path);
            return new RouteTarget("erp-auth-service", "http://localhost:8081", 10000, requiresAuth);
        }
        if (path.startsWith("/api/v1/tenants") || path.startsWith("/api/v1/plans")) {
            boolean requiresAuth = !isPublicEndpoint(path);
            return new RouteTarget("erp-tenant-service", gatewayProperties.getTenantServiceUrl(), 10000, requiresAuth);
        }
        if (path.startsWith("/api/v1/fleet")) {
            return new RouteTarget("erp-fleet-service", "http://localhost:8083", 10000, true);
        }
        if (path.startsWith("/api/v1/work-orders")) {
            return new RouteTarget("erp-work-order-service", "http://localhost:8084", 10000, true);
        }
        if (path.startsWith("/api/v1/inventory")) {
            return new RouteTarget("erp-inventory-service", "http://localhost:8085", 10000, true);
        }
        if (path.startsWith("/api/v1/billing")) {
            return new RouteTarget("erp-billing-service", "http://localhost:8086", 10000, true);
        }
        if (path.startsWith("/api/v1/telemetry")) {
            return new RouteTarget("erp-telemetry-service", "http://localhost:8087", 10000, true);
        }

        return null;
    }

    private boolean isPublicEndpoint(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (path.equalsIgnoreCase(publicPath) || path.startsWith(publicPath + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Token del llamante, tal cual lo envio, para reenviarlo al destino. */
    private String bearerTokenOf(InboundRequest inboundRequest) {
        String authHeader = inboundRequest.getFirstHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private String extractOrGenerateTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-ID");
        if (traceId != null && !traceId.trim().isEmpty()) {
            return traceId.trim();
        }
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null && traceparent.startsWith("00-")) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                return parts[1];
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, List<String>> extractHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                Enumeration<String> values = request.getHeaders(name);
                List<String> valueList = values != null ? Collections.list(values) : new ArrayList<>();
                headers.put(name, valueList);
            }
        }
        return headers;
    }

    private boolean isRestrictedResponseHeader(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        return lower.equals("transfer-encoding") ||
               lower.equals("content-length") ||
               lower.equals("connection");
    }
}
