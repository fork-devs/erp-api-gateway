package com.erp.apigateway.infrastructure.security;

import com.erp.apigateway.infrastructure.config.GatewayProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class JwksKeyManager {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyManager.class);
    private static final long CACHE_TTL_SECONDS = 300; // 5 minutos de caché

    private final GatewayProperties gatewayProperties;
    private final AtomicReference<JWKSet> jwkSetCache = new AtomicReference<>(null);
    private final ConcurrentHashMap<String, RSAPublicKey> publicKeyMap = new ConcurrentHashMap<>();
    private volatile Instant lastFetchTime = Instant.EPOCH;

    public JwksKeyManager(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    public RSAPublicKey getPublicKey(String keyId) {
        if (keyId != null && publicKeyMap.containsKey(keyId)) {
            return publicKeyMap.get(keyId);
        }

        refreshKeysIfNecessary(false);

        if (keyId != null && publicKeyMap.containsKey(keyId)) {
            return publicKeyMap.get(keyId);
        }

        // Si aún no se encuentra, forzar actualización (en caso de rotación de claves en auth-service)
        refreshKeysIfNecessary(true);
        return keyId != null ? publicKeyMap.get(keyId) : getAnyPublicKey();
    }

    public void registerManualKey(String keyId, RSAPublicKey publicKey) {
        publicKeyMap.put(keyId, publicKey);
    }

    private synchronized void refreshKeysIfNecessary(boolean force) {
        Instant now = Instant.now();
        if (!force && jwkSetCache.get() != null && lastFetchTime.plusSeconds(CACHE_TTL_SECONDS).isAfter(now)) {
            return;
        }

        try {
            String jwksUrl = gatewayProperties.getJwksUrl();
            if (jwksUrl == null || jwksUrl.trim().isEmpty()) {
                log.warn("JWKS URL no configurada");
                return;
            }

            log.info("Descargando JWKS desde {}", jwksUrl);
            JWKSet jwkSet = JWKSet.load(URI.create(jwksUrl).toURL());
            jwkSetCache.set(jwkSet);
            lastFetchTime = now;

            publicKeyMap.clear();
            for (JWK jwk : jwkSet.getKeys()) {
                if (jwk instanceof RSAKey rsaKey) {
                    try {
                        RSAPublicKey pubKey = rsaKey.toRSAPublicKey();
                        if (rsaKey.getKeyID() != null) {
                            publicKeyMap.put(rsaKey.getKeyID(), pubKey);
                        } else {
                            publicKeyMap.put("default", pubKey);
                        }
                    } catch (Exception e) {
                        log.error("Error al convertir clave RSA JWK a RSAPublicKey", e);
                    }
                }
            }
            log.info("JWKS actualizado correctamente. Claves cargadas: {}", publicKeyMap.keySet());
        } catch (Exception e) {
            log.warn("No se pudo cargar JWKS desde auth-service (continuando con claves existentes si las hay): {}", e.getMessage());
        }
    }

    private RSAPublicKey getAnyPublicKey() {
        return publicKeyMap.values().stream().findFirst().orElse(null);
    }
}
