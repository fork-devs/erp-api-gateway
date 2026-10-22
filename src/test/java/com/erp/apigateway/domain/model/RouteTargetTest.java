package com.erp.apigateway.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteTargetTest {

    @Test
    void rechazaUnNombreDeServicioVacio() {
        assertThatThrownBy(() -> new RouteTarget(" ", "http://localhost:8081", 500, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceName");
    }

    @Test
    void rechazaUnaUrlDeDestinoVacia() {
        assertThatThrownBy(() -> new RouteTarget("auth-service", null, 500, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetBaseUrl");
    }

    @Test
    void unPlazoNoPositivoCaeAlPlazoPorDefecto() {
        RouteTarget destino = new RouteTarget("auth-service", "http://localhost:8081", 0, true);

        assertThat(destino.timeoutMs()).isEqualTo(10000);
    }

    @Test
    void elConstructorDeCuatroArgumentosNoExigeRol() {
        RouteTarget destino = new RouteTarget("auth-service", "http://localhost:8081", 500, true);

        assertThat(destino.requiredRole()).isNull();
        assertThat(destino.serviceName()).isEqualTo("auth-service");
        assertThat(destino.requiresAuth()).isTrue();
    }

    @Test
    void elDestinoConservaElRolExigido() {
        RouteTarget destino = new RouteTarget("admin-outbox", "http://localhost:8082", 500, true, "ADMIN_TALLER");

        assertThat(destino.requiredRole()).isEqualTo("ADMIN_TALLER");
    }
}
