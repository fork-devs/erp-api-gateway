package com.erp.apigateway.contract;

import com.erp.apigateway.support.AbstractGatewayContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRATO DEL BORDE — identidad.
 *
 * <p>Lo que se fija aqui es el invariante mas importante del borde: una
 * peticion sin privilegio suficiente NO LLEGA al servicio de destino. No
 * basta con que la respuesta sea un 403: si la peticion hubiera tocado el
 * destino, el dano ya estaria hecho.
 */
class GatewayIdentityContractTest extends AbstractGatewayContractTest {

    @Test
    @DisplayName("Una peticion con rol insuficiente recibe 403 y NO llega al destino")
    void unRolInsuficienteNoLlegaAlDestino() {
        // El atacante es un mecanico del taller B: la ruta exige un rol que su
        // token no declara.
        UUID tallerAtacante = UUID.randomUUID();
        String token = IDENTIDAD.token(tallerAtacante, roles("MECANICO"));

        Respuesta respuesta = get("/api/v1/admin/outbox/dead-letters", token);

        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(respuesta.cuerpo()).contains("TENANT_ACCESS_FORBIDDEN");
        assertThat(DESTINO.llamadasRecibidas())
                .as("una peticion sin privilegio no debe tocar el servicio de destino")
                .isZero();
    }

    @Test
    @DisplayName("Una peticion con el rol exigido llega al destino y vuelve con su respuesta")
    void unRolSuficienteLlegaAlDestino() {
        String token = IDENTIDAD.token(UUID.randomUUID(), roles("ADMIN_TALLER"));

        Respuesta respuesta = get("/api/v1/admin/outbox/dead-letters", token);

        assertThat(respuesta.estado()).isEqualTo(200);
        // La ruta de administracion de la bandeja de salida la sirve el
        // servicio de talleres, que es el destino declarado. El mismo stub
        // attendia antes la consulta de la cuota del taller, de modo que se
        // cuenta la llamada a la RUTA y no todas las llamadas.
        assertThat(SERVICIO_DE_TALLERES.llamadasA("/api/v1/admin/outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("Un token invalido recibe 401 y NO llega al destino")
    void unTokenInvalidoNoLlegaAlDestino() {
        Respuesta respuesta = get("/api/v1/fleet/vehicles", IDENTIDAD.tokenInvalido());

        assertThat(respuesta.estado()).isEqualTo(401);
        assertThat(DESTINO.llamadasRecibidas()).isZero();
    }

    @Test
    @DisplayName("Un token vencido recibe 401 y NO llega al destino")
    void unTokenVencidoNoLlegaAlDestino() {
        String token = IDENTIDAD.tokenVencido(UUID.randomUUID(), roles("MECANICO"));

        Respuesta respuesta = get("/api/v1/fleet/vehicles", token);

        assertThat(respuesta.estado()).isEqualTo(401);
        assertThat(DESTINO.llamadasRecibidas()).isZero();
    }

    @Test
    @DisplayName("Una peticion sin token a una ruta autenticada recibe 401")
    void unaPeticionSinTokenRecibe401() {
        Respuesta respuesta = get("/api/v1/fleet/vehicles", null);

        assertThat(respuesta.estado()).isEqualTo(401);
        assertThat(DESTINO.llamadasRecibidas()).isZero();
    }

    @Test
    @DisplayName("Un taller suspendido recibe 403 y NO llega al destino")
    void unTallerSuspendidoNoOpera() {
        UUID taller = UUID.randomUUID();
        SERVICIO_DE_TALLERES.responderCon(200, cuerpoDeLimites("SUSPENDED", "PRO", 500, 40));

        Respuesta respuesta = get("/api/v1/fleet/vehicles", IDENTIDAD.token(taller, roles("MECANICO")));

        assertThat(respuesta.estado()).isEqualTo(403);
        assertThat(DESTINO.llamadasRecibidas())
                .as("un taller suspendido no puede operar")
                .isZero();
    }

    @Test
    @DisplayName("Si el estado del taller no se puede determinar, la peticion se rechaza con 503")
    void siNoSePuedeDeterminarElEstadoDelTallerSeRechaza() {
        // El modo cerrado: sin estado no se opera (hallazgo 6.31).
        UUID taller = UUID.randomUUID();
        SERVICIO_DE_TALLERES.responderCon(500, "{\"error\":\"caido\"}");

        Respuesta respuesta = get("/api/v1/fleet/vehicles", IDENTIDAD.token(taller, roles("MECANICO")));

        assertThat(respuesta.estado()).isEqualTo(503);
        assertThat(respuesta.cuerpo()).contains("TENANT_STATUS_UNAVAILABLE");
        assertThat(DESTINO.llamadasRecibidas()).isZero();
    }

    @Test
    @DisplayName("La identidad que llega al destino es la del token, no la que envia el cliente")
    void laIdentidadQueLlegaEsLaDelToken() {
        UUID taller = UUID.randomUUID();
        UUID victimaDelTallerA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        String token = IDENTIDAD.token(taller, roles("MECANICO"));

        Respuesta respuesta = exchange("GET", "/api/v1/fleet/vehicles", null, token,
                java.util.Map.of(
                        "X-User-ID", victimaDelTallerA.toString(),
                        "X-User-Role", "SUPERADMIN",
                        "X-User-Email", "admin@tallerA.pe",
                        "X-Tenant-ID", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

        assertThat(respuesta.estado()).isEqualTo(200);
        assertThat(DESTINO.llamadasRecibidas()).isEqualTo(1);

        var peticion = DESTINO.ultimaPeticion();
        assertThat(peticion.cabecera("X-Tenant-ID")).isEqualTo(taller.toString());
        assertThat(peticion.cabecera("X-User-ID"))
                .as("la identidad del cliente no puede suplantar la del token")
                .isNotEqualTo(victimaDelTallerA.toString());
        assertThat(peticion.cabecera("X-User-Role")).doesNotContain("SUPERADMIN");
    }

}
