package com.erp.apigateway.application;

import com.erp.apigateway.infrastructure.config.GatewayProperties;
import com.erp.apigateway.infrastructure.config.GatewayProperties.ServiceRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La tabla de rutas del gateway llega a donde tiene que llegar.
 *
 * <p>La API de gestion de usuarios no era alcanzable a traves del gateway: no
 * estaba en la tabla de rutas, de modo que una peticion a /api/v1/users no
 * tenia destino. Y las rutas declaradas como publicas incluian una que no
 * existe en el servicio de autenticacion.
 *
 * <p>Ver docs/doc-estado-implementacion.md, hallazgo P1 6.26.
 */
class GatewayRouteTableTest {

    /**
     * Rutas que el gateway sirve sin autenticacion. La lista tiene que
     * coincidir con la del despachador: si divergen, una ruta queda
     * protegida de mas o de menos.
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/.well-known/jwks.json",
            "/api/v1/plans");

    @Test
    @DisplayName("La tabla de rutas incluye la de los usuarios, apuntando al servicio de autenticacion")
    void laTablaDeRutasIncluyeLaDeLosUsuarios() {
        Map<String, ServiceRoute> rutas = rutasDeLaConfiguracion();

        ServiceRoute usuarios = rutaQueEmpiezaPor(rutas, "/api/v1/users");

        assertNotNull(usuarios, "La tabla de rutas debe incluir /api/v1/users");
        assertTrue(usuarios.getTargetUrl().contains("8081"),
                "Los usuarios se sirven por el servicio de autenticacion: " + usuarios.getTargetUrl());
        assertTrue(usuarios.isRequiresAuth(),
                "La gestion de usuarios exige autenticacion");
    }

    @Test
    @DisplayName("Toda ruta publica tiene una ruta que la sirve")
    void todaRutaPublicaTieneUnaRutaQueLaSirve() {
        Map<String, ServiceRoute> rutas = rutasDeLaConfiguracion();

        for (String publica : PUBLIC_PATHS) {
            if (publica.equals("/.well-known/jwks.json")) {
                // La sirve el propio gateway.
                continue;
            }
            assertNotNull(rutaQueEmpiezaPor(rutas, publica),
                    "La ruta publica " + publica + " no tiene ninguna ruta que la sirva");
        }
    }

    @Test
    @DisplayName("Ninguna ruta de la tabla apunta a un servicio que no exista")
    void ningunaRutaPublicaApuntaAUnaOperacionInexistente() {
        // Las rutas publicas del despachador tienen que existir en los
        // servicios de destino. La de registro no existe: el alta de usuarios
        // es /api/v1/users y exige autenticacion.
        List<String> rutasPublicas = List.of(
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
                "/api/v1/plans");

        Map<String, ServiceRoute> rutas = rutasDeLaConfiguracion();

        for (String publica : rutasPublicas) {
            assertNotNull(rutaQueEmpiezaPor(rutas, publica),
                    "La ruta publica " + publica + " no tiene ninguna ruta que la sirva");
        }

        assertFalse(PUBLIC_PATHS.contains("/api/v1/auth/register"),
                "El registro de usuarios no es una operacion publica: exige autenticacion");
    }

    /**
     * Ruta que sirve una operacion: la de prefijo mas largo que encaja. La
     * de prefijo exacto es un caso particular.
     */
    private ServiceRoute rutaQueEmpiezaPor(Map<String, ServiceRoute> rutas, String operacion) {
        return rutas.values().stream()
                .filter(ruta -> operacion.equals(ruta.getPathPrefix())
                        || operacion.startsWith(ruta.getPathPrefix() + "/"))
                .max((a, b) -> a.getPathPrefix().length() - b.getPathPrefix().length())
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ServiceRoute> rutasDeLaConfiguracion() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        Map<String, ServiceRoute> resultado = new java.util.LinkedHashMap<>();
        for (String nombre : yaml.getObject().stringPropertyNames()) {
            if (!nombre.startsWith("erp.gateway.routes.")) {
                continue;
            }
            String resto = nombre.substring("erp.gateway.routes.".length());
            int punto = resto.indexOf('.');
            if (punto < 0) {
                continue;
            }
            String clave = resto.substring(0, punto);
            String atributo = resto.substring(punto + 1);

            ServiceRoute ruta = resultado.computeIfAbsent(clave, c -> {
                ServiceRoute nueva = new ServiceRoute();
                nueva.setPathPrefix("");
                return nueva;
            });

            switch (atributo) {
                case "path-prefix" -> ruta.setPathPrefix(yaml.getObject().getProperty(nombre));
                case "target-url" -> ruta.setTargetUrl(yaml.getObject().getProperty(nombre));
                case "requires-auth" -> ruta.setRequiresAuth(
                        Boolean.parseBoolean(yaml.getObject().getProperty(nombre)));
                default -> {
                    // El resto de atributos no forman parte de la comprobacion.
                }
            }
        }
        assertFalse(resultado.isEmpty(), "La tabla de rutas no esta vacia");
        return resultado;
    }
}
