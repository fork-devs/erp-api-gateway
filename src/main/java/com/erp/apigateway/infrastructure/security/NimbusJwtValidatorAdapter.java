package com.erp.apigateway.infrastructure.security;

import com.erp.apigateway.domain.exception.InvalidTokenException;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.JwtValidatorPort;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public class NimbusJwtValidatorAdapter implements JwtValidatorPort {

    private static final Logger log = LoggerFactory.getLogger(NimbusJwtValidatorAdapter.class);

    private final JwksKeyManager jwksKeyManager;

    public NimbusJwtValidatorAdapter(JwksKeyManager jwksKeyManager) {
        this.jwksKeyManager = jwksKeyManager;
    }

    @Override
    public TokenClaims validate(String bearerToken) {
        if (bearerToken == null || bearerToken.trim().isEmpty()) {
            throw new InvalidTokenException("Token JWT no proporcionado");
        }

        try {
            SignedJWT signedJWT = SignedJWT.parse(bearerToken.trim());
            String keyId = signedJWT.getHeader().getKeyID();

            RSAPublicKey publicKey = jwksKeyManager.getPublicKey(keyId);
            if (publicKey == null) {
                throw new InvalidTokenException("No se encontró la clave pública RSA correspondiente al token (kid: " + keyId + ")");
            }

            RSASSAVerifier verifier = new RSASSAVerifier(publicKey);
            if (!signedJWT.verify(verifier)) {
                throw new InvalidTokenException("Firma criptográfica del token inválida");
            }

            JWTClaimsSet claimsSet = signedJWT.getJWTClaimsSet();

            Date expirationTime = claimsSet.getExpirationTime();
            if (expirationTime != null && new Date().after(expirationTime)) {
                throw new InvalidTokenException("El token ha expirado");
            }

            String subject = claimsSet.getSubject();
            if (subject == null || subject.trim().isEmpty()) {
                throw new InvalidTokenException("El token no contiene el subject (userId)");
            }

            UUID userId;
            try {
                userId = UUID.fromString(subject);
            } catch (IllegalArgumentException e) {
                throw new InvalidTokenException("El subject del token no es un UUID válido");
            }

            Object tenantIdClaim = claimsSet.getClaim("tenant_id");
            if (tenantIdClaim == null) {
                throw new InvalidTokenException("El token no contiene el claim obligatorio 'tenant_id'");
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdClaim.toString());
            } catch (IllegalArgumentException e) {
                throw new InvalidTokenException("El claim 'tenant_id' no es un UUID válido");
            }

            String role = claimsSet.getStringClaim("role");
            String email = claimsSet.getStringClaim("email");
            String issuer = claimsSet.getIssuer();
            Instant issuedAt = claimsSet.getIssueTime() != null ? claimsSet.getIssueTime().toInstant() : Instant.now();
            Instant expiresAt = expirationTime != null ? expirationTime.toInstant() : Instant.now().plusSeconds(3600);

            return new TokenClaims(userId, tenantId, role, email, issuer, issuedAt, expiresAt);

        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error al procesar y validar el token JWT: {}", e.getMessage());
            throw new InvalidTokenException("Token JWT malformado o inválido: " + e.getMessage());
        }
    }
}
