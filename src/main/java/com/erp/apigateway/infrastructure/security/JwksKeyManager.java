package com.erp.apigateway.infrastructure.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente del conjunto de claves publicas del servicio de autenticacion.
 *
 * <p>Cuatro propiedades que el cliente anterior no tenia:
 *
 * <ul>
 *   <li>La vigencia de la cache se comprueba <strong>antes</strong> de
 *       devolver una clave conocida. Antes se devolvia la clave cacheada sin
 *       comprobar nada, de modo que una clave rotada seguia aceptandose
 *       durante toda la ventana de cache.</li>
 *   <li>Un identificador de clave desconocido se <strong>rechaza</strong>. No
 *       se cae a "cualquier clave": verificar el token contra una clave
 *       arbitraria es aceptar cualquier token firmado con cualquier clave
 *       que el servicio hubiera tenido.</li>
 *   <li>La descarga tiene tiempo de espera de conexion y de lectura. Antes,
 *       una autenticacion que colgara dejaba el proceso esperando
 *       indefinidamente.</li>
 *   <li>Un fallo de descarga <strong>no vacia la cache</strong> ni actualiza
 *       la marca de exito: se conserva el ultimo conjunto valido, y el
 *       intervalo de reintento tras un fallo esta acotado, de modo que la
 *       tasa de descargas no crece con el volumen de peticiones.</li>
 * </ul>
 *
 * <p>Ver docs/doc-estado-implementacion.md, hallazgos P1 6.29 y P0 5.12.
 */
public class JwksKeyManager {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyManager.class);

    /** Vigencia de la cache tras una descarga correcta. */
    private static final long CACHE_TTL_SECONDS = 300;

    /** Intervalo entre reintentos tras una descarga fallida. */
    private static final long RETRY_INTERVAL_SECONDS = 10;

    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int READ_TIMEOUT_MILLIS = 3000;

    private final String jwksUrl;
    private final ConcurrentHashMap<String, RSAPublicKey> publicKeyMap = new ConcurrentHashMap<>();

    /** Momento de la ultima descarga correcta. */
    private volatile Instant lastSuccessTime = Instant.EPOCH;

    /** Momento del ultimo intento de descarga, correcto o no. */
    private volatile Instant lastAttemptTime = Instant.EPOCH;

    /** Si el ultimo intento de descarga fallo. */
    private volatile boolean lastAttemptFailed = false;

    public JwksKeyManager(String jwksUrl, List<String> activeProfiles) {
        JwksUrlPolicy.verificar(jwksUrl, activeProfiles);
        this.jwksUrl = jwksUrl == null ? "" : jwksUrl.trim();
    }

    public RSAPublicKey getPublicKey(String keyId) {
        // La vigencia de la cache se comprueba ANTES de devolver una clave
        // conocida: si no, una clave rotada seguiria aceptandose durante
        // toda la ventana de cache.
        refrescarSiProcede(false);

        if (keyId != null) {
            RSAPublicKey clave = publicKeyMap.get(keyId);
            if (clave != null) {
                return clave;
            }
        }

        // Identificador desconocido: puede ser una rotacion reciente.
        refrescarSiProcede(true);

        if (keyId != null) {
            return publicKeyMap.get(keyId);
        }

        // Sin identificador no hay clave que devolver. Verificar contra una
        // clave arbitraria es aceptar cualquier token.
        log.warn("Se ha presentado un token sin identificador de clave: se rechaza");
        return null;
    }

    public void registerManualKey(String keyId, RSAPublicKey publicKey) {
        if (keyId != null && publicKey != null) {
            publicKeyMap.put(keyId, publicKey);
        }
    }

    /**
     * Descarga el conjunto de claves, salvo que la cache siga vigente o que
     * el intervalo de reintento tras un fallo todavia no haya vencido.
     */
    private synchronized void refrescarSiProcede(boolean force) {
        Instant now = Instant.now();

        if (lastAttemptFailed && lastAttemptTime.plusSeconds(RETRY_INTERVAL_SECONDS).isAfter(now)) {
            // El ultimo intento fallo: no se reintenta hasta que venza el
            // intervalo. Sin esto, cada peticion que arrive con un
            // identificador desconocido golpearia al emisor, y la tasa de
            // descargas creceria con el volumen de peticiones.
            return;
        }

        if (!force && lastSuccessTime.plusSeconds(CACHE_TTL_SECONDS).isAfter(now)) {
            return;
        }

        lastAttemptTime = now;

        try {
            if (jwksUrl.isEmpty()) {
                log.warn("JWKS URL no configurada");
                lastAttemptFailed = true;
                return;
            }

            log.info("Descargando JWKS desde {}", jwksUrl);
            JWKSet jwkSet = descargar();
            ConcurrentHashMap<String, RSAPublicKey> claves = new ConcurrentHashMap<>();

            for (JWK jwk : jwkSet.getKeys()) {
                if (jwk instanceof RSAKey rsaKey) {
                    try {
                        RSAPublicKey pubKey = rsaKey.toRSAPublicKey();
                        claves.put(rsaKey.getKeyID() != null ? rsaKey.getKeyID() : "default", pubKey);
                    } catch (Exception e) {
                        log.error("Error al convertir clave RSA JWK a RSAPublicKey", e);
                    }
                }
            }

            // El conjunto valido solo se sustituye cuando la descarga ha
            // sido correcta: un fallo no deja al servicio sin ninguna clave.
            publicKeyMap.clear();
            publicKeyMap.putAll(claves);
            lastSuccessTime = now;
            lastAttemptFailed = false;

            log.info("JWKS actualizado correctamente. Claves cargadas: {}", publicKeyMap.keySet());
        } catch (Exception e) {
            lastAttemptFailed = true;
            log.warn("No se pudo cargar JWKS desde auth-service (continuando con claves existentes si las hay): {}",
                    e.getMessage());
        }
    }

    private JWKSet descargar() throws Exception {
        URL url = URI.create(jwksUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestMethod("GET");
        try (InputStream input = connection.getInputStream()) {
            return JWKSet.load(input);
        } finally {
            connection.disconnect();
        }
    }
}
