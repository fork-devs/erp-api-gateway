package com.erp.apigateway.infrastructure.client;

import com.erp.apigateway.domain.exception.TenantStatusUnavailableException;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.infrastructure.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Los limites de trafico del plan los posee el tenant-service (ADR-007).
 *
 * <p>El gateway NO los deduce: los usa tal como llegan, y cuando no llegan cae
 * al limite MAS BAJO, nunca a uno mayor.
 */
class TenantServiceClientAdapterTest {

    private MockRestServiceServer server;
    private TenantServiceClientAdapter adapter;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTenantServiceUrl("http://tenant-service.test");

        RestClient.Builder builder = RestClient.builder().baseUrl("http://tenant-service.test");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new TenantServiceClientAdapter(properties, builder.build());
    }

    @Test
    @DisplayName("Should use the traffic limits that the tenant-service sends")
    void debeUsarLosLimitesQueMandaElTenantService() {
        UUID tenantId = UUID.randomUUID();
        server.expect(requestTo("http://tenant-service.test/api/v1/internal/tenants/" + tenantId + "/limits"))
                .andRespond(withSuccess("""
                        {
                          "tenantId": "%s",
                          "status": "ACTIVE",
                          "planCode": "PRO",
                          "maxUsers": 20,
                          "maxVehicles": 5000,
                          "maxIotMsgPerDay": 100000,
                          "maxRequestsPerMinute": 500,
                          "maxConcurrentSlots": 40
                        }
                        """.formatted(tenantId), MediaType.APPLICATION_JSON));

        TenantQuota cuota = adapter.getQuota(tenantId, "token-del-llamante");

        assertThat(cuota.getPlanCode()).isEqualTo("PRO");
        assertThat(cuota.getMaxReqPerMin()).isEqualTo(500);
        assertThat(cuota.getMaxConcurrentSlots()).isEqualTo(40);
        server.verify();
    }

    @Test
    @DisplayName("Should fall back to the lowest limit when the limits do not arrive")
    void debeCaerAlLimiteMasBajoCuandoNoLleganLosLimites() {
        UUID tenantId = UUID.randomUUID();
        // Un tenant-service todavia sin los campos nuevos (despliegue
        // desordenado, o un rollback del tenant-service).
        server.expect(requestTo("http://tenant-service.test/api/v1/internal/tenants/" + tenantId + "/limits"))
                .andRespond(withSuccess("""
                        {
                          "tenantId": "%s",
                          "status": "ACTIVE",
                          "planCode": "ENTERPRISE",
                          "maxUsers": 100,
                          "maxVehicles": 50000,
                          "maxIotMsgPerDay": 1000000
                        }
                        """.formatted(tenantId), MediaType.APPLICATION_JSON));

        TenantQuota cuota = adapter.getQuota(tenantId, "token-del-llamante");

        assertThat(cuota.getPlanCode()).isEqualTo("ENTERPRISE");
        assertThat(cuota.getMaxReqPerMin())
                .as("sin los limites del plan se aplica el del plan mas bajo, nunca uno mayor")
                .isEqualTo(TenantServiceClientAdapter.LIMITE_POR_DEFECTO_POR_MINUTO);
        assertThat(cuota.getMaxConcurrentSlots())
                .isEqualTo(TenantServiceClientAdapter.LIMITE_POR_DEFECTO_CONCURRENTES);
        server.verify();
    }

    @Test
    @DisplayName("Should treat a zero limit as absent")
    void debeTratarUnLimiteCeroComoAusente() {
        UUID tenantId = UUID.randomUUID();
        server.expect(requestTo("http://tenant-service.test/api/v1/internal/tenants/" + tenantId + "/limits"))
                .andRespond(withSuccess("""
                        {
                          "tenantId": "%s",
                          "status": "ACTIVE",
                          "planCode": "PRO",
                          "maxUsers": 20,
                          "maxVehicles": 5000,
                          "maxIotMsgPerDay": 100000,
                          "maxRequestsPerMinute": 0,
                          "maxConcurrentSlots": 0
                        }
                        """.formatted(tenantId), MediaType.APPLICATION_JSON));

        TenantQuota cuota = adapter.getQuota(tenantId, "token-del-llamante");

        assertThat(cuota.getMaxReqPerMin()).isEqualTo(TenantServiceClientAdapter.LIMITE_POR_DEFECTO_POR_MINUTO);
        assertThat(cuota.getMaxConcurrentSlots()).isEqualTo(TenantServiceClientAdapter.LIMITE_POR_DEFECTO_CONCURRENTES);
        server.verify();
    }

    @Test
    @DisplayName("Should fail closed when the tenant status cannot be determined")
    void debeFallarCerradoCuandoNoSePuedeDeterminarElEstadoDelTaller() {
        // Un taller SUSPENDIDO no puede volver a operar porque el
        // tenant-service no responda: el borde falla cerrado (hallazgo 6.31).
        UUID tenantId = UUID.randomUUID();
        server.expect(requestTo("http://tenant-service.test/api/v1/internal/tenants/" + tenantId + "/limits"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.getQuota(tenantId, "token-del-llamante"))
                .isInstanceOf(TenantStatusUnavailableException.class)
                .hasMessageContaining("estado del taller");

        server.verify();
    }
}
