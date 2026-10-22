package com.erp.apigateway.infrastructure.security;

import com.erp.apigateway.domain.exception.InvalidTokenException;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.infrastructure.config.GatewayProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NimbusJwtValidatorTest {

    private JwksKeyManager jwksKeyManager;
    private NimbusJwtValidatorAdapter validator;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private final String keyId = "test-key-1";
    private static final String EMISOR = "https://auth.test.erp-automotriz.com";
    private static final String AUDIENCIA = "erp-api-gateway";

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();

        GatewayProperties properties = new GatewayProperties();
        // El perfil de desarrollo local es el unico que permite la URL en
        // claro; el resto de las comprobaciones las hace el propio cliente.
        this.jwksKeyManager = new JwksKeyManager(properties.getJwksUrl(), List.of("dev"));
        this.jwksKeyManager.registerManualKey(keyId, publicKey);

        this.validator = new NimbusJwtValidatorAdapter(jwksKeyManager, EMISOR, AUDIENCIA);
    }

    @Test
    @DisplayName("Should validate valid RS256 token and extract claims")
    void shouldValidateValidToken() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("roles", List.of("ADMINISTRADOR", "MECANICO"))
                .claim("email", "admin@taller.pe")
                .issuer(EMISOR)
                .audience(AUDIENCIA)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 3600000))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claimsSet
        );
        signedJWT.sign(new RSASSASigner(privateKey));
        String token = signedJWT.serialize();

        TokenClaims claims = validator.validate(token);

        assertNotNull(claims);
        assertEquals(userId, claims.getUserId());
        assertEquals(tenantId, claims.getTenantId());
        assertEquals(2, claims.getRoles().size(), "No se puede perder ningun rol del token");
        assertTrue(claims.getRoles().contains("ADMINISTRADOR"));
        assertTrue(claims.getRoles().contains("MECANICO"));
        assertEquals("admin@taller.pe", claims.getEmail());
        assertFalse(claims.isExpired());
    }

    @Test
    @DisplayName("Should reject expired token")
    void shouldRejectExpiredToken() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("roles", List.of("MECANICO"))
                .issuer(EMISOR)
                .audience(AUDIENCIA)
                .issueTime(new Date(System.currentTimeMillis() - 7200000))
                .expirationTime(new Date(System.currentTimeMillis() - 3600000))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claimsSet
        );
        signedJWT.sign(new RSASSASigner(privateKey));
        String token = signedJWT.serialize();

        assertThrows(InvalidTokenException.class, () -> validator.validate(token));
    }

    @Test
    @DisplayName("Should reject token signed with unknown/different key")
    void shouldRejectInvalidSignature() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair otherKeyPair = kpg.generateKeyPair();

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", UUID.randomUUID().toString())
                .expirationTime(new Date(System.currentTimeMillis() + 3600000))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claimsSet
        );
        signedJWT.sign(new RSASSASigner((RSAPrivateKey) otherKeyPair.getPrivate()));
        String token = signedJWT.serialize();

        assertThrows(InvalidTokenException.class, () -> validator.validate(token));
    }

    @Test
    @DisplayName("Should reject token without tenant_id claim")
    void shouldRejectTokenWithoutTenantId() throws Exception {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .expirationTime(new Date(System.currentTimeMillis() + 3600000))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claimsSet
        );
        signedJWT.sign(new RSASSASigner(privateKey));
        String token = signedJWT.serialize();

        assertThrows(InvalidTokenException.class, () -> validator.validate(token));
    }
}
