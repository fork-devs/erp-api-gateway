package com.erp.apigateway.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La tabla de rutas tiene que ENLAZARSE, no solo estar escrita.
 *
 * <p>Una clave de mapa con guiones ({@code auth-service}) no es un nombre de
 * propiedad valido: el enlazador la descarta <strong>en silencio</strong> y el
 * mapa queda VACIO. Con el mapa vacio, el gateway no aplicaba el rol
 * exigido por la ruta, ni el plazo, ni el destino configurable por variable de
 * entorno, y solo enrutaba gracias a un bloque de URLs fijas que
 * precisamente ignoraba esas variables.
 *
 * <p>Este test enlaza la configuracion REAL y comprueba que llega al
 * gateway. Es el test que faltaba: el anterior leia el YAML con un parser y
 * solo comprobaba que las rutas estaban escritas, no que se enlazaran.
 *
 * <p>Ver docs/doc-estado-implementacion.md, hallazgo P0 5.13.
 */
class GatewayRouteTableBindingTest {

    @Test
    @DisplayName("The route table of the configuration binds into the gateway")
    void laTablaDeRutasSeEnlaza() throws Exception {
        GatewayProperties properties = enlazarLaConfiguracion();

        assertThat(properties.getRoutes())
                .as("una tabla de rutas vacia significa que el gateway enruta por el bloque de URLs fijas")
                .hasSizeGreaterThanOrEqualTo(10);

        assertThat(properties.getRoutes()).allSatisfy((nombre, ruta) -> {
            assertThat(ruta.getPathPrefix()).as("prefijo de " + nombre).isNotBlank();
            assertThat(ruta.getTargetUrl()).as("destino de " + nombre).isNotBlank();
            assertThat(ruta.getTimeoutMs()).as("plazo de " + nombre).isPositive();
        });
    }

    @Test
    @DisplayName("The route that requires a role carries it")
    void laRutaQueExigeRolLoCarga() throws Exception {
        GatewayProperties properties = enlazarLaConfiguracion();

        GatewayProperties.ServiceRoute admin = properties.getRoutes().get("outbox-admin-service");

        assertThat(admin)
                .as("la ruta de administracion de la bandeja de salida debe existir")
                .isNotNull();
        assertThat(admin.getRequiredRole())
                .as("sin el rol exigido, la comprobacion del borde no puede rechazar a nadie")
                .isEqualTo("ADMIN_TALLER");
        assertThat(admin.isRequiresAuth()).isTrue();
    }

    @Test
    @DisplayName("The startup route carries the tenant filter mark")
    void laRutaDeArranqueCargaLaMarcaDelFiltro() throws Exception {
        GatewayProperties properties = enlazarLaConfiguracion();

        assertThat(properties.getRoutes().get("auth-service").isTenantFilterAllowed())
                .as("el login, el refresco y el restablecimiento necesitan el filtro de taller")
                .isTrue();
        assertThat(properties.getRoutes().get("fleet-service").isTenantFilterAllowed()).isFalse();
    }

    @Test
    @DisplayName("The destinations come from the configuration and not from a fixed block")
    void losDestinosVienenDeLaConfiguracion() throws Exception {
        GatewayProperties properties = enlazarLaConfiguracion();

        // El destino llega RESUELTO desde el marcador de posicion, de modo que
        // la variable de entorno lo puede cambiar.
        assertThat(properties.getRoutes().get("fleet-service").getTargetUrl())
                .isEqualTo("http://localhost:8083");
        assertThat(properties.getRoutes().get("inventory-service").getTargetUrl())
                .isEqualTo("http://localhost:8084");
        assertThat(properties.getRoutes().get("work-order-service").getTargetUrl())
                .isEqualTo("http://localhost:8088");
    }

    private GatewayProperties enlazarLaConfiguracion() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> fuentes = loader.load("application", new ClassPathResource("application.yml"));
        StandardEnvironment entorno = new StandardEnvironment();
        fuentes.forEach(entorno.getPropertySources()::addLast);

        return Binder.get(entorno).bind("erp.gateway", GatewayProperties.class).orElseThrow(() -> new IllegalStateException("La configuracion del gateway no se pudo enlazar"));
    }
}
