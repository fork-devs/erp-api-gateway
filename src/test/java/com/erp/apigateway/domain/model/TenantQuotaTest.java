package com.erp.apigateway.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantQuotaTest {

    private static final UUID TALLER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void rechazaUnTallerNulo() {
        assertThatThrownBy(() -> new TenantQuota(null, "ACTIVE", "BASICO", 100, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void normalizaElEstadoYElPlanAMayusculas() {
        TenantQuota cuota = new TenantQuota(TALLER, "active", "pro", 500, 40);

        assertThat(cuota.getStatus()).isEqualTo("ACTIVE");
        assertThat(cuota.getPlanCode()).isEqualTo("PRO");
    }

    @Test
    void unEstadoNuloSeTreataComoActivo() {
        TenantQuota cuota = new TenantQuota(TALLER, null, null, 100, 15);

        assertThat(cuota.isActive()).isTrue();
        assertThat(cuota.isSuspended()).isFalse();
        assertThat(cuota.getPlanCode()).isEqualTo("BASICO");
    }

    @Test
    void unTallerSuspendidoNoEstaActivo() {
        TenantQuota cuota = new TenantQuota(TALLER, "SUSPENDED", "PRO", 500, 40);

        assertThat(cuota.isSuspended()).isTrue();
        assertThat(cuota.isActive()).isFalse();
    }

    @Test
    void losLimitesNoValidosSeSustituyenPorElDelPlanMasBajo() {
        TenantQuota cuota = new TenantQuota(TALLER, "ACTIVE", "ENTERPRISE", 0, -1);

        assertThat(cuota.getMaxReqPerMin()).isEqualTo(TenantQuota.LIMITE_POR_DEFECTO_POR_MINUTO);
        assertThat(cuota.getMaxConcurrentSlots()).isEqualTo(TenantQuota.LIMITE_POR_DEFECTO_CONCURRENTES);
    }

    @Test
    void laCuotaPorDefectoEsLaDelPlanMasBajo() {
        // El gateway NO deduce los limites del plan: los posee el
        // tenant-service (ADR-007). La cuota por defecto es el suelo, y el
        // plan solo se conserva para trazabilidad.
        TenantQuota basico = TenantQuota.createDefault(TALLER, "BASICO");
        TenantQuota pro = TenantQuota.createDefault(TALLER, "PRO");
        TenantQuota enterprise = TenantQuota.createDefault(TALLER, "ENTERPRISE");

        assertThat(basico.getMaxReqPerMin()).isEqualTo(100);
        assertThat(basico.getMaxConcurrentSlots()).isEqualTo(15);
        assertThat(pro.getMaxReqPerMin()).isEqualTo(100);
        assertThat(pro.getMaxConcurrentSlots()).isEqualTo(15);
        assertThat(enterprise.getMaxReqPerMin()).isEqualTo(100);
        assertThat(enterprise.getMaxConcurrentSlots()).isEqualTo(15);
        assertThat(pro.getPlanCode()).isEqualTo("PRO");
    }

    @Test
    void laCuotaPorDefectoNoDeclaraSuspendidoUnTaller() {
        // El estado por defecto es ACTIVO: por eso el adaptador DEBE fallar
        // cerrado cuando no puede determinar el estado real (hallazgo 6.31).
        TenantQuota cuota = TenantQuota.createDefault(TALLER, "BASICO");

        assertThat(cuota.isActive()).isTrue();
        assertThat(cuota.getTenantId()).isEqualTo(TALLER);
    }
}
