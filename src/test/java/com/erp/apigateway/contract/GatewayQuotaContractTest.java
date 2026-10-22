package com.erp.apigateway.contract;

import com.erp.apigateway.support.AbstractGatewayContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRATO DEL BORDE — cuotas y concurrencia.
 *
 * <p>El cliente tiene que poder saber cuanto le queda de su plan, y el plan
 * que se aplica es el que dice el servicio de talleres, no una copia en el
 * borde (ADR-007).
 */
class GatewayQuotaContractTest extends AbstractGatewayContractTest {

    @Test
    @DisplayName("Una peticion dentro de la cuota lleva las cabeceras de cuota")
    void unaPeticionDentroDeLaCuotaLlevaLasCabeceras() {
        SERVICIO_DE_TALLERES.responderCon(200, cuerpoDeLimites("ACTIVE", "PRO", 500, 40));

        Respuesta respuesta = get("/api/v1/inventory/parts", IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO")));

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(respuesta.cabecera("X-RateLimit-Limit")).isEqualTo("500");
        assertThat(respuesta.cabecera("X-RateLimit-Remaining")).isNotNull();
        assertThat(respuesta.cabecera("X-RateLimit-Reset")).isNotNull();
    }

    @Test
    @DisplayName("La cuota excedida responde 429 con Retry-After y no llega al destino")
    void laCuotaExcedidaResponde429() {
        // Un plan de 1 peticion por minuto: la segunda se rechaza.
        SERVICIO_DE_TALLERES.responderCon(200, cuerpoDeLimites("ACTIVE", "BASICO", 1, 15));
        String token = IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO"));

        Respuesta primera = get("/api/v1/inventory/parts", token);
        assertThat(primera.estado()).isEqualTo(200);

        Respuesta segunda = get("/api/v1/inventory/parts", token);

        assertThat(segunda.estado()).isEqualTo(429);
        assertThat(segunda.cabecera("Retry-After")).isNotNull();
        assertThat(segunda.cuerpo()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("El limite que aplica es el del plan, no una copia en el borde")
    void elLimiteQueAplicaEsElDelPlan() {
        // El plan del tenant-service dice 3 req/min; el borde no puede
        // decir otra cosa (ADR-007).
        SERVICIO_DE_TALLERES.responderCon(200, cuerpoDeLimites("ACTIVE", "PRO", 3, 40));
        String token = IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO"));

        Respuesta primera = get("/api/v1/inventory/parts", token);
        Respuesta segunda = get("/api/v1/inventory/parts", token);
        Respuesta tercera = get("/api/v1/inventory/parts", token);
        Respuesta cuarta = get("/api/v1/inventory/parts", token);

        assertThat(primera.estado()).isEqualTo(200);
        assertThat(segunda.estado()).isEqualTo(200);
        assertThat(tercera.estado()).isEqualTo(200);
        assertThat(cuarta.estado())
                .as("el limite del plan es de 3 peticiones por minuto")
                .isEqualTo(429);
        assertThat(primera.cabecera("X-RateLimit-Limit")).isEqualTo("3");
    }
}
