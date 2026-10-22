package com.erp.apigateway.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cliente del conjunto de claves se comporta como debe.
 *
 * <p>Estas comprobaciones son las MISMAS en el gateway y en el hub: los dos
 * servicios tienen una copia del cliente, y no hay todavia una biblioteca
 * compartida entre ellos. Mantener las comprobaciones identicas es lo que
 * impide que las copias diverjan sin que nadie se entere.
 *
 * <p>Ver docs/doc-estado-implementacion.md, hallazgos P1 6.29 y P0 5.12.
 */
class JwksKeyManagerTest {

    private HttpServer servidor;
    private String url;

    @AfterEach
    void tearDown() {
        if (servidor != null) {
            servidor.stop(0);
            servidor = null;
        }
    }

    @Test
    @DisplayName("Una URL en claro se rechaza fuera del perfil de desarrollo local")
    void unaUrlEnClaroSeRechaza() {
        IllegalStateException excepcion = assertThrows(IllegalStateException.class,
                () -> new JwksKeyManager("http://auth.ejemplo.com/.well-known/jwks.json", List.of("prod")));

        assertTrue(excepcion.getMessage().contains("esquema cifrado"),
                "El mensaje debe explicar la exigencia: " + excepcion.getMessage());
    }

    @Test
    @DisplayName("El perfil de desarrollo local permite la URL en claro")
    void elPerfilDeDesarrolloLocalPermiteLaUrlEnClaro() {
        assertNotNull(new JwksKeyManager("http://localhost:8081/.well-known/jwks.json", List.of("dev")));
    }

    @Test
    @DisplayName("Una URL sin configurar se rechaza")
    void unaUrlSinConfigurarSeRechaza() {
        assertThrows(IllegalStateException.class, () -> new JwksKeyManager("", List.of("dev")));
    }

    @Test
    @DisplayName("Un identificador de clave desconocido se rechaza, sin verificar contra ninguna clave")
    void unIdentificadorDesconocidoSeRechaza() throws Exception {
        arrancarServidor(200, cuerpoConClaves("clave-conocida"));

        JwksKeyManager manager = new JwksKeyManager(url, List.of("dev"));

        assertNull(manager.getPublicKey("clave-inventada"),
                "Un identificador desconocido no puede resolverse contra una clave arbitraria");
    }

    @Test
    @DisplayName("Un fallo de descarga no vacia la cache")
    void unFalloDeDescargaNoVaciaLaCache() throws Exception {
        arrancarServidor(200, cuerpoConClaves("clave-conocida"));

        JwksKeyManager manager = new JwksKeyManager(url, List.of("dev"));
        assertNotNull(manager.getPublicKey("clave-conocida"), "La clave debe cargarse");

        // El emisor pasa a estar inalcanzable.
        servidor.stop(0);
        servidor = null;

        assertNotNull(manager.getPublicKey("clave-conocida"),
                "Un fallo de descarga no puede dejar al servicio sin ninguna clave");
    }

    @Test
    @DisplayName("El intervalo de reintento tras un fallo acota las descargas")
    void elIntervaloDeReintentoAcotaLasDescargas() throws Exception {
        AtomicInteger descargas = new AtomicInteger();
        arrancarServidorContador(descargas, 500, "no es un conjunto de claves");

        JwksKeyManager manager = new JwksKeyManager(url, List.of("dev"));

        for (int i = 0; i < 20; i++) {
            manager.getPublicKey("clave-cualquiera");
        }

        assertTrue(descargas.get() <= 2,
                "Con el emisor inalcanzable, las descargas deben estar acotadas, no una por peticion: "
                        + descargas.get());
    }

    // ------------------------------------------------------------- helpers

    private void arrancarServidor(int status, String cuerpo) throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/.well-known/jwks.json", exchange -> {
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        servidor.start();
        url = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/.well-known/jwks.json";
    }

    private void arrancarServidorContador(AtomicInteger contador, int status, String cuerpo) throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/.well-known/jwks.json", exchange -> {
            contador.incrementAndGet();
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        servidor.start();
        url = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/.well-known/jwks.json";
    }

    private String cuerpoConClaves(String kid) throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID(kid).generate();
        RSAPublicKey publica = rsaKey.toRSAPublicKey();
        return new JWKSet(rsaKey.toPublicJWK()).toString();
    }
}
