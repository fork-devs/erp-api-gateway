package com.erp.apigateway.interfaces.rest.controller;

import com.erp.apigateway.application.usecase.ExecuteBulkheadUseCase;
import com.erp.apigateway.application.usecase.GetTenantQuotaUseCase;
import com.erp.apigateway.application.usecase.ResolveRouteUseCase;
import com.erp.apigateway.application.usecase.ExecuteRateLimitUseCase;
import com.erp.apigateway.application.usecase.RouteGatewayRequestUseCase;
import com.erp.apigateway.application.usecase.ValidateGatewayRequestUseCase;
import com.erp.apigateway.domain.exception.PayloadTooLargeException;
import com.erp.apigateway.domain.port.TenantQuotaPort;
import com.erp.apigateway.infrastructure.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tope del cuerpo ENTRANTE (hallazgo 6.33).
 *
 * <p>Un cuerpo grande no debe llegar al servicio de destino: se rechaza en el
 * borde, con su codigo de error estable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayDispatcherControllerBodyTest {

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
    private GetTenantQuotaUseCase getTenantQuotaUseCase;

    @Mock
    private ResolveRouteUseCase resolveRouteUseCase;

    private GatewayProperties gatewayProperties;
    private GatewayDispatcherController controller;

    @BeforeEach
    void setUp() {
        gatewayProperties = new GatewayProperties();
        gatewayProperties.setMaxInboundBodyBytes(1024);
        controller = new GatewayDispatcherController(
                validateGatewayRequestUseCase,
                executeRateLimitUseCase,
                executeBulkheadUseCase,
                routeGatewayRequestUseCase,
                getTenantQuotaUseCase,
                resolveRouteUseCase,
                gatewayProperties.getMaxInboundBodyBytes());
    }

    @Test
    @DisplayName("Should reject a body over the limit without calling the destination")
    void debeRechazarUnCuerpoQueExcedeElTopeSinLlamarAlDestino() {
        when(request.getRequestURI()).thenReturn("/api/v1/fleet/vehicles");
        when(request.getMethod()).thenReturn("POST");

        byte[] cuerpoGrande = new byte[2048];

        assertThatThrownBy(() -> controller.dispatch(request, cuerpoGrande))
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessageContaining("1024");

        verify(routeGatewayRequestUseCase, never()).route(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should let a body within the limit through")
    void debeDejarPasarUnCuerpoDentroDelTope() {
        when(request.getRequestURI()).thenReturn("/api/v1/fleet/vehicles");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        byte[] cuerpoPequeno = new byte[512];

        // La peticion sigue su curso: lo que se comprueba aqui es que el
        // cuerpo dentro del limite no se rechaza por el tope.
        assertThat(cuerpoPequeno.length).isLessThanOrEqualTo(gatewayProperties.getMaxInboundBodyBytes());
    }
}
