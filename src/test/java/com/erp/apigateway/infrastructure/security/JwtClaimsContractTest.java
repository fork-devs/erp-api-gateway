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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El contrato de claims que el gateway espera del servicio de autenticacion.
 *
 * <p>El emisor y el verificador tienen que hablar el mismo idioma: los roles
 * como array, el emisor y la audiencia comprobados, y ningun rol por
 * defecto.
 */
class JwtClaimsContractTest {

    private static final String EMISOR = "https://auth.test.erp-automotriz.com";
    private static final String AUDIENCIA = "erp-api-gateway";

    private NimbusJwtValidatorAdapter validator;
    private RSAPrivateKey privateKey;
    private final String keyId = "test-key-1";

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        GatewayProperties properties = new GatewayProperties();
        JwksKeyManager manager = new JwksKeyManager(properties.getJwksUrl(), List.of("dev"));
        manager.registerManualKey(keyId, publicKey);

        this.validator = new NimbusJwtValidatorAdapter(manager, EMISOR, AUDIENCIA);
    }

    @Test
    @DisplayName("Un token con tres roles produce los tres roles")
    void unTokenConTresRolesProduceLosTresRoles() throws Exception {
        String token = firmar(List.of("ADMIN_TALLER", "ASESOR", "MECANICO"), EMISOR, AUDIENCIA);

        TokenClaims claims = validator.validate(token);

        assertEquals(3, claims.getRoles().size(), "No se puede perder ningun rol");
        assertTrue(claims.getRoles().contains("ADMIN_TALLER"));
        assertTrue(claims.getRoles().contains("ASESOR"));
        assertTrue(claims.getRoles().contains("MECANICO"));
    }

    @Test
    @DisplayName("Un token sin claim de roles se rechaza")
    void unTokenSinRolesSeRechaza() throws Exception {
        String token = firmar(null, EMISOR, AUDIENCIA);

        InvalidTokenException excepcion = assertThrows(InvalidTokenException.class,
                () -> validator.validate(token));

        assertTrue(excepcion.getMessage().contains("roles"),
                "El mensaje debe indicar que falta el claim de roles: " + excepcion.getMessage());
    }

    @Test
    @DisplayName("Un token con un emisor distinto se rechaza")
    void unTokenConEmisorDistintoSeRechaza() throws Exception {
        String token = firmar(List.of("MECANICO"), "https://otro-emisor.example", AUDIENCIA);

        InvalidTokenException excepcion = assertThrows(InvalidTokenException.class,
                () -> validator.validate(token));

        assertTrue(excepcion.getMessage().contains("emisor"),
                "El mensaje debe indicar que el emisor no es el esperado: " + excepcion.getMessage());
    }

    @Test
    @DisplayName("Un token con una audiencia distinta se rechaza")
    void unTokenConAudienciaDistintaSeRechaza() throws Exception {
        String token = firmar(List.of("MECANICO"), EMISOR, "otro-servicio");

        InvalidTokenException excepcion = assertThrows(InvalidTokenException.class,
                () -> validator.validate(token));

        assertTrue(excepcion.getMessage().contains("audiencia"),
                "El mensaje debe indicar que la audiencia no es la esperada: " + excepcion.getMessage());
    }

    @Test
    @DisplayName("Un token sin audiencia se rechaza")
    void unTokenSinAudienciaSeRechaza() throws Exception {
        String token = firmar(List.of("MECANICO"), EMISOR, null);

        assertThrows(InvalidTokenException.class, () -> validator.validate(token));
    }

    private String firmar(List<String> roles, String issuer, String audiencia) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", UUID.randomUUID().toString())
                .issuer(issuer)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 3600000));

        if (roles != null) {
            claims.claim("roles", roles);
        }
        if (audiencia != null) {
            claims.audience(audiencia);
        }

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claims.build()
        );
        signedJWT.sign(new RSASSASigner(privateKey));
        return signedJWT.serialize();
    }
}
