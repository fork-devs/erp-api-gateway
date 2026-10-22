package com.erp.apigateway.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionTest {

    @Test
    void cadaFalloDelBordeTieneSuCodigoEstable() {
        assertThat(new InvalidTokenException("token invalido").getErrorCode()).isEqualTo("INVALID_OR_EXPIRED_JWT");
        assertThat(new TenantForbiddenException("taller suspendido").getErrorCode()).isEqualTo("TENANT_ACCESS_FORBIDDEN");
        assertThat(new TargetServiceUnavailableException("destino caido").getErrorCode()).isEqualTo("TARGET_SERVICE_UNAVAILABLE");
        assertThat(new BulkheadExceededException("saturado").getErrorCode()).isEqualTo("BULKHEAD_CAPACITY_EXCEEDED");
        assertThat(new RateLimitExceededException("cuota excedida", 30).getErrorCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void elCodigoDeErrorEsParteDelContrato() {
        GatewayException excepcion = new InvalidTokenException("token invalido");

        assertThat(excepcion.getErrorCode()).isNotBlank();
        assertThat(excepcion.getMessage()).isEqualTo("token invalido");
    }

    @Test
    void elLimiteDePeticionesExponeCuantoEsperar() {
        RateLimitExceededException excepcion = new RateLimitExceededException("cuota excedida", 42);

        assertThat(excepcion.getRetryAfterSeconds()).isEqualTo(42);
    }

    @Test
    void elDestinoNoDisponibleConservaLaCausa() {
        IllegalStateException causa = new IllegalStateException("socket cerrado");

        TargetServiceUnavailableException excepcion = new TargetServiceUnavailableException("destino caido", causa);

        assertThat(excepcion.getCause()).isSameAs(causa);
    }
}
