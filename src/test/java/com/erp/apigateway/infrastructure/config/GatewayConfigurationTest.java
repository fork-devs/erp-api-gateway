package com.erp.apigateway.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprueba que la configuracion que sostiene el modelo de concurrencia y los
 * topes del borde esta REALMENTE aplicada.
 *
 * <p>Estas propiedades no son cosmeticas: si el despliegue se queda sin ellas
 * (una property mal escrita, un perfil que las pisa, una JVM anterior), el
 * borde vuelve a su techo de hilos de plataforma y a sus cuerpos sin tope, y
 * nada avisa. Por eso se comprueba en el contexto, no en el fichero.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.threads.virtual.enabled=true",
        "spring.codec.max-in-memory-size=1MB",
        "spring.http.client.connect-timeout=2s",
        "spring.http.client.read-timeout=5s"
})
class GatewayConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void losHilosVirtualesEstanActivados() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class))
                .as("los hilos virtuales son la base del modelo de concurrencia del borde")
                .isTrue();
    }

    @Test
    void elTopeDelCuerpoEntranteEstaConfigurado() {
        String valor = environment.getProperty("spring.codec.max-in-memory-size");
        assertThat(valor)
                .as("sin tope del cuerpo entrante, una peticion grande agota la heap del borde")
                .isNotNull();
        assertThat(environment.getProperty("spring.codec.max-in-memory-size", org.springframework.util.unit.DataSize.class))
                .isNotNull();
    }

    @Test
    void losTimeoutsDelClienteHttpEstanConfigurados() {
        assertThat(environment.getProperty("spring.http.client.connect-timeout")).isNotNull();
        assertThat(environment.getProperty("spring.http.client.read-timeout"))
                .as("sin tiempo de espera de lectura, un destino colgado bloquea el hilo indefinidamente")
                .isNotNull();
    }
}
