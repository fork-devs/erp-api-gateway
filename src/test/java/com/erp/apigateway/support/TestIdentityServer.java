package com.erp.apigateway.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Emisor de identidades de los tests de contrato.
 *
 * <p>Levanta un servidor real con el conjunto de claves publicas y firma
 * tokens de verdad, de modo que el gateway los valida contra el JWKS que
 * descarga: no hay simulacion en el camino de la identidad.
 */
public final class TestIdentityServer {

    public static final String ISSUER = "https://auth.erp-automotriz.com";
    public static final String AUDIENCE = "erp-api-gateway";

    private final RSAKey rsaKey;
    private final HttpServer server;

    private TestIdentityServer(RSAKey rsaKey, HttpServer server) {
        this.rsaKey = rsaKey;
        this.server = server;
    }

    public static TestIdentityServer arrancar(int puerto) throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        KeyPair keyPair = generador.generateKeyPair();
        RSAKey rsaKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("clave-de-prueba-1")
                .build();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", puerto), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            byte[] cuerpo = new JWKSet(rsaKey.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, cuerpo.length);
            try (java.io.OutputStream salida = exchange.getResponseBody()) {
                salida.write(cuerpo);
            }
        });
        server.start();

        return new TestIdentityServer(rsaKey, server);
    }

    public String jwksUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    public void detener() {
        server.stop(0);
    }

    /**
     * Token valido para un taller, con los roles indicados.
     */
    public String token(UUID tenantId, List<String> roles) {
        return firmar(tenantId, roles, ISSUER, AUDIENCE, 3600);
    }

    /**
     * Token ya vencido: el gateway debe rechazarlo.
     */
    public String tokenVencido(UUID tenantId, List<String> roles) {
        return firmar(tenantId, roles, ISSUER, AUDIENCE, -60);
    }

    /**
     * Token con una firma que el emisor no puede verificar.
     */
    public String tokenInvalido() {
        return "no.es.un.token";
    }

    private String firmar(UUID tenantId, List<String> roles, String issuer, String audience, int expiraEnSegundos) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("tenant_id", tenantId.toString())
                    .claim("roles", roles)
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + expiraEnSegundos * 1000L));

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", ex);
        }
    }
}
