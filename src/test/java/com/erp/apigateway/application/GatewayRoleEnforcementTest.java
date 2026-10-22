package com.erp.apigateway.application;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.service.GatewaySecurityService;
import com.erp.apigateway.domain.exception.TenantForbiddenException;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.port.JwtValidatorPort;
import com.erp.apigateway.domain.port.TenantQuotaPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El borde comprueba el rol requerido por la ruta ANTES de reenviar.
 *
 * <p>Una peticion con un rol insuficiente no debe llegar a tocar el servicio
 * de destino: el borde es el unico punto donde se puede cortar antes de
 * gastar la peticion.
 */
class GatewayRoleEnforcementTest {

    private GatewaySecurityService servicio;
    private JwtValidatorPort validator;
    private TenantQuotaPort quota;

    @BeforeEach
    void setUp() {
        validator = mock(JwtValidatorPort.class);
        quota = mock(TenantQuotaPort.class);
        when(quota.getQuota(any(), any())).thenReturn(null);
        servicio = new GatewaySecurityService(validator, quota);
    }

    @Test
    @DisplayName("Un principal con el rol requerido pasa")
    void unPrincipalConElRolRequeridoPasa() {
        when(validator.validate("token")).thenReturn(claims("ADMIN_TALLER"));

        RouteTarget objetivo = new RouteTarget("outbox-admin", "http://localhost:8082", 10000, true, "ADMIN_TALLER");

        TokenClaims resultado = servicio.validateRequest(peticionConToken(), objetivo);

        assertTrue(resultado != null, "La peticion debe validarse");
    }

    @Test
    @DisplayName("Un principal con un rol insuficiente se rechaza antes de reenviar")
    void unPrincipalConUnRolInsuficienteSeRechaza() {
        when(validator.validate("token")).thenReturn(claims("MECANICO"));

        RouteTarget objetivo = new RouteTarget("outbox-admin", "http://localhost:8082", 10000, true, "ADMIN_TALLER");

        TenantForbiddenException excepcion = assertThrows(TenantForbiddenException.class,
                () -> servicio.validateRequest(peticionConToken(), objetivo));

        assertTrue(excepcion.getMessage().contains("ADMIN_TALLER"),
                "El mensaje debe indicar el rol requerido: " + excepcion.getMessage());
    }

    @Test
    @DisplayName("Una ruta sin rol requerido solo exige estar autenticado")
    void unaRutaSinRolRequeridoSoloExigeAutenticacion() {
        when(validator.validate("token")).thenReturn(claims("MECANICO"));

        RouteTarget objetivo = new RouteTarget("cualquiera", "http://localhost:8083", 10000, true, null);

        assertTrue(servicio.validateRequest(peticionConToken(), objetivo) != null,
                "Sin rol declarado, basta con estar autenticado");
    }

    @Test
    @DisplayName("El rol se compara sin distinguir mayusculas ni el prefijo ROLE_")
    void elRolSeComparaSinDistinguirMayusculasNiPrefijo() {
        when(validator.validate("token")).thenReturn(claims("ROLE_ADMIN_TALLER"));

        RouteTarget objetivo = new RouteTarget("outbox-admin", "http://localhost:8082", 10000, true, "admin_taller");

        assertTrue(servicio.validateRequest(peticionConToken(), objetivo) != null,
                "El rol debe compararse de forma tolerante");
    }

    private InboundRequest peticionConToken() {
        return new InboundRequest(
                "GET",
                "/api/v1/admin/outbox/dead-letters",
                "",
                java.util.Map.of("Authorization", java.util.List.of("Bearer token")),
                new byte[0],
                "127.0.0.1");
    }

    private TokenClaims claims(String rol) {
        return new TokenClaims(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Set.of(rol),
                "correo@taller.pe",
                "https://auth.test.erp-automotriz.com",
                Instant.now(),
                Instant.now().plusSeconds(3600));
    }
}
