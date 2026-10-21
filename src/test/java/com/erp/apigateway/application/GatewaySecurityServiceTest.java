package com.erp.apigateway.application;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.service.GatewaySecurityService;
import com.erp.apigateway.domain.exception.InvalidTokenException;
import com.erp.apigateway.domain.exception.TenantForbiddenException;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.JwtValidatorPort;
import com.erp.apigateway.domain.port.TenantQuotaPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewaySecurityServiceTest {

    @Mock
    private JwtValidatorPort jwtValidatorPort;

    @Mock
    private TenantQuotaPort tenantQuotaPort;

    private static final String TOKEN = "valid.jwt.token";

    private GatewaySecurityService securityService;

    @BeforeEach
    void setUp() {
        securityService = new GatewaySecurityService(jwtValidatorPort, tenantQuotaPort);
    }

    @Test
    @DisplayName("Should bypass token validation for public routes")
    void shouldBypassValidationForPublicRoutes() {
        RouteTarget publicTarget = new RouteTarget("auth-service", "http://localhost:8081", 5000, false);
        InboundRequest request = new InboundRequest("POST", "/api/v1/auth/login", "", Map.of(), new byte[0], "127.0.0.1");

        TokenClaims claims = securityService.validateRequest(request, publicTarget);

        assertNull(claims);
        verifyNoInteractions(jwtValidatorPort, tenantQuotaPort);
    }

    @Test
    @DisplayName("Should reject request with missing Authorization header on protected route")
    void shouldRejectMissingAuthHeader() {
        RouteTarget protectedTarget = new RouteTarget("fleet-service", "http://localhost:8083", 5000, true);
        InboundRequest request = new InboundRequest("GET", "/api/v1/fleet/vehicles", "", Map.of(), new byte[0], "127.0.0.1");

        assertThrows(InvalidTokenException.class, () -> securityService.validateRequest(request, protectedTarget));
    }

    @Test
    @DisplayName("Should validate token and active tenant successfully")
    void shouldValidateTokenAndActiveTenant() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String validToken = "valid.jwt.token";

        RouteTarget protectedTarget = new RouteTarget("fleet-service", "http://localhost:8083", 5000, true);
        InboundRequest request = new InboundRequest(
                "GET",
                "/api/v1/fleet/vehicles",
                "",
                Map.of("Authorization", List.of("Bearer " + validToken)),
                new byte[0],
                "127.0.0.1"
        );

        TokenClaims mockClaims = new TokenClaims(userId, tenantId, "ADMINISTRADOR", "admin@taller.pe", "erp-auth", Instant.now(), Instant.now().plusSeconds(3600));
        TenantQuota activeQuota = new TenantQuota(tenantId, "ACTIVE", "PRO", 500, 40);

        when(jwtValidatorPort.validate(validToken)).thenReturn(mockClaims);
        when(tenantQuotaPort.getQuota(tenantId, TOKEN)).thenReturn(activeQuota);

        TokenClaims result = securityService.validateRequest(request, protectedTarget);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertEquals(tenantId, result.getTenantId());
    }

    @Test
    @DisplayName("Should throw TenantForbiddenException if tenant is SUSPENDED")
    void shouldRejectSuspendedTenant() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String validToken = "valid.jwt.token";

        RouteTarget protectedTarget = new RouteTarget("fleet-service", "http://localhost:8083", 5000, true);
        InboundRequest request = new InboundRequest(
                "GET",
                "/api/v1/fleet/vehicles",
                "",
                Map.of("Authorization", List.of("Bearer " + validToken)),
                new byte[0],
                "127.0.0.1"
        );

        TokenClaims mockClaims = new TokenClaims(userId, tenantId, "ADMINISTRADOR", "admin@taller.pe", "erp-auth", Instant.now(), Instant.now().plusSeconds(3600));
        TenantQuota suspendedQuota = new TenantQuota(tenantId, "SUSPENDED", "PRO", 500, 40);

        when(jwtValidatorPort.validate(validToken)).thenReturn(mockClaims);
        when(tenantQuotaPort.getQuota(tenantId, TOKEN)).thenReturn(suspendedQuota);

        assertThrows(TenantForbiddenException.class, () -> securityService.validateRequest(request, protectedTarget));
    }
}
