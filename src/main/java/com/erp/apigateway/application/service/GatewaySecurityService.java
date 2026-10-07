package com.erp.apigateway.application.service;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.usecase.ValidateGatewayRequestUseCase;
import com.erp.apigateway.domain.exception.InvalidTokenException;
import com.erp.apigateway.domain.exception.TenantForbiddenException;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.JwtValidatorPort;
import com.erp.apigateway.domain.port.TenantQuotaPort;

public class GatewaySecurityService implements ValidateGatewayRequestUseCase {

    private final JwtValidatorPort jwtValidatorPort;
    private final TenantQuotaPort tenantQuotaPort;

    public GatewaySecurityService(JwtValidatorPort jwtValidatorPort, TenantQuotaPort tenantQuotaPort) {
        this.jwtValidatorPort = jwtValidatorPort;
        this.tenantQuotaPort = tenantQuotaPort;
    }

    @Override
    public TokenClaims validateRequest(InboundRequest request, RouteTarget target) {
        if (!target.requiresAuth()) {
            // Ruta pública (ej. /login, /register, JWKS)
            return null;
        }

        String authHeader = request.getFirstHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new InvalidTokenException("Cabecera Authorization ausente o formato Bearer inválido");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw new InvalidTokenException("Token JWT vacío");
        }

        // 1. Validar firma RS256, emisor y expiración del JWT
        TokenClaims claims = jwtValidatorPort.validate(token);
        if (claims.isExpired()) {
            throw new InvalidTokenException("El token JWT ha expirado");
        }

        // 2. Validar estado del inquilino (Tenant)
        TenantQuota quota = tenantQuotaPort.getQuota(claims.getTenantId());
        if (quota != null && quota.isSuspended()) {
            throw new TenantForbiddenException("El taller inquilino se encuentra suspendido. Contacte al administrador.");
        }

        return claims;
    }
}
