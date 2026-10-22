package com.erp.apigateway.application;

import com.erp.apigateway.application.service.GatewayRouteResolver;
import com.erp.apigateway.domain.model.RouteDefinition;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.port.RouteCatalogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * La resolucion de la ruta vive en la aplicacion, no en el controlador: es la
 * segunda pieza mas importante del borde, y desde ahi se puede probar sin
 * levantar un servlet (hallazgo 6.38).
 */
@ExtendWith(MockitoExtension.class)
class GatewayRouteResolverTest {

    @Mock
    private RouteCatalogPort routeCatalogPort;

    private GatewayRouteResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GatewayRouteResolver(routeCatalogPort);
    }

    @Test
    @DisplayName("Should resolve a declared route into a destination")
    void debeResolverUnaRutaDeclarada() {
        when(routeCatalogPort.findByPath("/api/v1/fleet/vehicles")).thenReturn(Optional.of(
                new RouteDefinition("fleet-service", "/api/v1/fleet", "http://localhost:8083", 500, true, null, false)));

        RouteTarget destino = resolver.resolve("/api/v1/fleet/vehicles");

        assertThat(destino).isNotNull();
        assertThat(destino.serviceName()).isEqualTo("fleet-service");
        assertThat(destino.targetBaseUrl()).isEqualTo("http://localhost:8083");
        assertThat(destino.timeoutMs()).isEqualTo(500);
        assertThat(destino.requiresAuth()).isTrue();
    }

    @Test
    @DisplayName("Should not resolve a route that is not declared")
    void noDebeResolverUnaRutaNoDeclarada() {
        when(routeCatalogPort.findByPath("/api/v1/desconocido")).thenReturn(Optional.empty());

        assertThat(resolver.resolve("/api/v1/desconocido"))
                .as("una ruta no declarada no tiene destino: no hay destino por defecto al que caerse")
                .isNull();
    }

    @Test
    @DisplayName("Should carry the role and the startup flag of the route")
    void debeTrasladarElRolYLaMarcaDeArranque() {
        when(routeCatalogPort.findByPath("/api/v1/admin/outbox")).thenReturn(Optional.of(
                new RouteDefinition("admin-outbox", "/api/v1/admin/outbox", "http://localhost:8082", 500, true, "ADMIN_TALLER", false)));

        RouteTarget destino = resolver.resolve("/api/v1/admin/outbox");

        assertThat(destino.requiredRole()).isEqualTo("ADMIN_TALLER");
        assertThat(destino.tenantFilterAllowed()).isFalse();
    }
}
