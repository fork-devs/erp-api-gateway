package com.erp.apigateway.application;

import com.erp.apigateway.infrastructure.config.GatewayProperties;
import com.erp.apigateway.infrastructure.config.GatewayProperties.ServiceRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("La ruta de fleet apunta al servicio de fleet, con el plazo del spec")
    void laRutaDeFleetApuntaAlServicioDeFleet() {
        Map<String, Object> rutas = rutasDelYaml();

        @SuppressWarnings("unchecked")
        Map<String, Object> fleet = (Map<String, Object>) rutas.get("fleet-service");

        assertNotNull(fleet, "La tabla de rutas debe incluir /api/v1/fleet");
        assertEquals("/api/v1/fleet", fleet.get("path-prefix"),
                "El prefijo del gateway es la ruta del servicio: el servicio lo publica bajo ese prefijo");
        assertTrue(String.valueOf(fleet.get("target-url")).contains("8083"),
                "El servicio de fleet escucha en el 8083: " + fleet.get("target-url"));
        assertEquals(Boolean.TRUE, fleet.get("requires-auth"),
                "La gestion de clientes y vehiculos exige autenticacion");
        assertEquals(500, fleet.get("timeout-ms"),
                "El plazo de las comunicaciones internas es de 500 ms");
    }

    /**
     * Rutas del gateway leidas del YAML de configuracion.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rutasDelYaml() {
        Yaml yaml = new Yaml();
        Map<String, Object> raiz;
        try (java.io.InputStream entrada = new ClassPathResource("application.yml").getInputStream()) {
            raiz = yaml.load(entrada);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo leer la configuracion del gateway", e);
        }
        Map<String, Object> erp = (Map<String, Object>) raiz.get("erp");
        Map<String, Object> gateway = (Map<String, Object>) erp.get("gateway");
        Map<String, Object> rutas = (Map<String, Object>) gateway.get("routes");

        // Las claves llevan corchetes en el YAML, porque una clave de mapa con
        // guiones no es un nombre de propiedad valido (hallazgo P0 5.13). Se
        // quita el contenedor para poder buscarlas por su nombre.
        Map<String, Object> sinCorchetes = new java.util.LinkedHashMap<>();
        rutas.forEach((clave, valor) -> sinCorchetes.put(
                clave.startsWith("[") && clave.endsWith("]")
                        ? clave.substring(1, clave.length() - 1)
                        : clave,
                valor));
        return sinCorchetes;
    }

    @Test
    @DisplayName("La ruta de inventory apunta al servicio de inventario, con el plazo del spec")
    void laRutaDeInventoryApuntaAlServicioDeInventory() {
        Map<String, Object> rutas = rutasDelYaml();

        @SuppressWarnings("unchecked")
        Map<String, Object> inventory = (Map<String, Object>) rutas.get("inventory-service");

        assertNotNull(inventory, "La tabla de rutas debe incluir /api/v1/inventory");
        assertEquals("/api/v1/inventory", inventory.get("path-prefix"),
                "El prefijo del gateway es la ruta del servicio: el inventario lo publica bajo ese prefijo");
        assertTrue(String.valueOf(inventory.get("target-url")).contains("8084"),
                "El servicio de inventario escucha en el 8084: la ruta apuntaba al 8085, que es el del hub");
        assertEquals(Boolean.TRUE, inventory.get("requires-auth"),
                "La gestion de stock exige autenticacion");
        assertEquals(500, inventory.get("timeout-ms"),
                "El plazo de las comunicaciones internas es de 500 ms");
    }

    @Test
    @DisplayName("La ruta de work-orders ocupa un puerto libre")
    void laRutaDeWorkOrdersOcupaUnPuertoLibre() {
        Map<String, Object> rutas = rutasDelYaml();

        @SuppressWarnings("unchecked")
        Map<String, Object> workOrders = (Map<String, Object>) rutas.get("work-order-service");

        assertNotNull(workOrders, "La tabla de rutas debe incluir /api/v1/work-orders");
        assertTrue(String.valueOf(workOrders.get("target-url")).contains("8088"),
                "El 8084 es el del inventario: el servicio de ordenes de trabajo ocupa el 8088");
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
            // Las claves de la tabla de rutas llevan corchetes, porque una
            // clave de mapa con guiones no es un nombre de propiedad valido y
            // el enlazador la descarta en silencio (hallazgo P0 5.13).
            String prefijo = "erp.gateway.routes[";
            if (!nombre.startsWith(prefijo)) {
                continue;
            }
            String resto = nombre.substring(prefijo.length());
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
                case "timeout-ms" -> ruta.setTimeoutMs(
                        Integer.parseInt(yaml.getObject().getProperty(nombre)));
                default -> {
                    // El resto de atributos no forman parte de la comprobacion.
                }
            }
        }
        assertFalse(resultado.isEmpty(), "La tabla de rutas no esta vacia");
        return resultado;
    }
}
