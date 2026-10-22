package com.erp.apigateway.interfaces.rest.controller;

import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.application.usecase.ExecuteBulkheadUseCase;
import com.erp.apigateway.application.usecase.ExecuteRateLimitUseCase;
import com.erp.apigateway.application.usecase.RouteGatewayRequestUseCase;
import com.erp.apigateway.application.usecase.ValidateGatewayRequestUseCase;
import com.erp.apigateway.domain.model.BulkheadResult;
import com.erp.apigateway.domain.model.RateLimitResult;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.TenantQuotaPort;
import com.erp.apigateway.infrastructure.config.GatewayProperties;
import com.erp.apigateway.infrastructure.metrics.GatewayMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Cabeceras de cuota (hallazgo 6.35).
 *
 * <p>El gateway las declaraba en la configuracion de CORS pero no las
 * emitia: el cliente no podia saber cuanto le quedaba ni cuando reintentar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayDispatcherControllerQuotaTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private ValidateGatewayRequestUseCase validateGatewayRequestUseCase;

    @Mock
    private ExecuteRateLimitUseCase executeRateLimitUseCase;

    @Mock
    private ExecuteBulkheadUseCase executeBulkheadUseCase;

    @Mock
    private RouteGatewayRequestUseCase routeGatewayRequestUseCase;

    @Mock
    private TenantQuotaPort tenantQuotaPort;

    @Mock
    private GatewayMetrics gatewayMetrics;

    private GatewayDispatcherController controller;

    @BeforeEach
    void setUp() {
        GatewayProperties gatewayProperties = new GatewayProperties();
        gatewayProperties.getRoutes().put("fleet-service",
                new GatewayProperties.ServiceRoute("/api/v1/fleet", "http://localhost:8083", true, 500));

        controller = new GatewayDispatcherController(
                gatewayProperties,
                gatewayMetrics,
                validateGatewayRequestUseCase,
                executeRateLimitUseCase,
                executeBulkheadUseCase,
                routeGatewayRequestUseCase,
                tenantQuotaPort);
    }

    @Test
    @DisplayName("Should emit the quota headers of the request")
    void debeEmitirLasCabecerasDeCuota() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        TokenClaims claims = new TokenClaims(userId, tenantId, Set.of("MECANICO"), "mecanico@taller.pe",
                "erp-auth", Instant.now(), Instant.now().plusSeconds(600));

        when(request.getRequestURI()).thenReturn("/api/v1/fleet/vehicles");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(validateGatewayRequestUseCase.validateRequest(any(), any())).thenReturn(claims);
        when(tenantQuotaPort.getQuota(any(), any())).thenReturn(new TenantQuota(tenantId, "ACTIVE", "PRO", 500, 40));
        when(executeRateLimitUseCase.evaluateRateLimit(any(), any())).thenReturn(RateLimitResult.allowed(497, 500, 30));
        when(executeBulkheadUseCase.acquireSlot(any(), any(int.class))).thenReturn(BulkheadResult.success(1, 40));
        when(routeGatewayRequestUseCase.route(any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, Map.of(), new byte[0]));

        ResponseEntity<byte[]> respuesta = controller.dispatch(request, new byte[0]);

        assertThat(respuesta.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("500");
        assertThat(respuesta.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("497");
        assertThat(respuesta.getHeaders().getFirst("X-RateLimit-Reset")).isEqualTo("30");
    }
}
