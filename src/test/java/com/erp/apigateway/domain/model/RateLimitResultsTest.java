package com.erp.apigateway.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitResultsTest {

    @Test
    void unaPeticionPermitidaConservaLosTokensRestantes() {
        RateLimitResult resultado = RateLimitResult.allowed(42, 100, 30);

        assertThat(resultado.allowed()).isTrue();
        assertThat(resultado.remainingTokens()).isEqualTo(42);
        assertThat(resultado.limit()).isEqualTo(100);
        assertThat(resultado.resetAfterSeconds()).isEqualTo(30);
    }

    @Test
    void unaPeticionDenegadaNoDejaTokens() {
        RateLimitResult resultado = RateLimitResult.denied(100, 15);

        assertThat(resultado.allowed()).isFalse();
        assertThat(resultado.remainingTokens()).isZero();
        assertThat(resultado.limit()).isEqualTo(100);
        assertThat(resultado.resetAfterSeconds()).isEqualTo(15);
    }

    @Test
    void unMamparoConcedidoIndicaElVueloActual() {
        BulkheadResult resultado = BulkheadResult.success(3, 15);

        assertThat(resultado.acquired()).isTrue();
        assertThat(resultado.currentInFlight()).isEqualTo(3);
        assertThat(resultado.maxCapacity()).isEqualTo(15);
    }

    @Test
    void unMamparoRechazadoIndicaElVueloActual() {
        BulkheadResult resultado = BulkheadResult.rejected(15, 15);

        assertThat(resultado.acquired()).isFalse();
        assertThat(resultado.currentInFlight()).isEqualTo(15);
        assertThat(resultado.maxCapacity()).isEqualTo(15);
    }
}
