package com.erp.apigateway.infrastructure.config;

import com.erp.apigateway.domain.model.RouteDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El catalogo de rutas tiene UNA sola fuente de verdad.
 *
 * <p>Antes habia un segundo bloque con URLs fijas localhost:808X que, si
 * faltaba una ruta en el YAML, hacia caer al gateway en un destino local en
 * produccion, ignorando las variables de entorno (hallazgo 6.34).
 */
class RouteCatalogAdapterTest {

    private RouteCatalogAdapter catalogo;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.getRoutes().put("fleet-service",
                new GatewayProperties.ServiceRoute("/api/v1/fleet", "http://localhost:8083", true, 500));
        properties.getRoutes().put("auth-service",
                new GatewayProperties.ServiceRoute("/api/v1/auth", "http://localhost:8081", false, 10000));
        GatewayProperties.ServiceRoute admin = new GatewayProperties.ServiceRoute("/api/v1/admin/outbox", "http://localhost:8082", true, 500);
        admin.setRequiredRole("ADMIN_TALLER");
        properties.getRoutes().put("admin-outbox", admin);
        GatewayProperties.ServiceRoute arranque = new GatewayProperties.ServiceRoute("/api/v1/auth", "http://localhost:8081", false, 10000);
        arranque.setTenantFilterAllowed(true);
        properties.getRoutes().put("auth-service", arranque);

        catalogo = new RouteCatalogAdapter(properties);
    }

    @Test
    @DisplayName("Should resolve a declared route")
    void debeResolverUnaRutaDeclarada() {
        Optional<RouteDefinition> definicion = catalogo.findByPath("/api/v1/fleet/vehicles");

        assertThat(definicion).isPresent();
        assertThat(definicion.get().serviceName()).isEqualTo("fleet-service");
        assertThat(definicion.get().targetUrl()).isEqualTo("http://localhost:8083");
        assertThat(definicion.get().requiresAuth()).isTrue();
    }

    @Test
    @DisplayName("Should not resolve a route that is not declared")
    void noDebeResolverUnaRutaNoDeclarada() {
        assertThat(catalogo.findByPath("/api/v1/no-existe")).isEmpty();
        assertThat(catalogo.findByPath("/otra-cosa")).isEmpty();
    }

    @Test
    @DisplayName("Should carry the required role of a route")
    void debeTrasladarElRolExigido() {
        assertThat(catalogo.findByPath("/api/v1/admin/outbox/dead-letters").orElseThrow().requiredRole())
                .isEqualTo("ADMIN_TALLER");
    }

    @Test
    @DisplayName("Should mark the startup routes as allowing the tenant filter")
    void debeMarcarLasRutasDeArranque() {
        assertThat(catalogo.findByPath("/api/v1/auth/login").orElseThrow().tenantFilterAllowed()).isTrue();
        assertThat(catalogo.findByPath("/api/v1/fleet/vehicles").orElseThrow().tenantFilterAllowed()).isFalse();
    }

    @Test
    @DisplayName("Should recognise the public paths")
    void debeReconocerLasRutasPublicas() {
        assertThat(catalogo.isPublicEndpoint("/api/v1/auth/login")).isTrue();
        assertThat(catalogo.isPublicEndpoint("/api/v1/auth/refresh")).isTrue();
        assertThat(catalogo.isPublicEndpoint("/.well-known/jwks.json")).isTrue();
        assertThat(catalogo.isPublicEndpoint("/api/v1/plans")).isTrue();
        assertThat(catalogo.isPublicEndpoint("/api/v1/fleet/vehicles")).isFalse();
    }

    @Test
    @DisplayName("Should not require authentication on a public route")
    void noDebeExigirAutenticacionEnUnaRutaPublica() {
        assertThat(catalogo.findByPath("/api/v1/auth/login").orElseThrow().requiresAuth()).isFalse();
    }
}
