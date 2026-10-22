package com.erp.apigateway.contract;

import com.erp.apigateway.support.AbstractGatewayContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRATO DEL BORDE — concurrencia.
 *
 * <p>El techo del borde NO lo ponen los hilos de plataforma: con los hilos
 * virtuales, el `send()` bloqueante libera el portador mientras espera. Este
 * test lo demuestra de la forma mas directa posible: el perfil de test deja
 * el pool de Tomcat en VEINTE hilos, y la rafaga son CIEN peticiones
 * concurrentes. Si el techo siguieran siendo los hilos, la mitad se
 * rechazaria (hallazgo 6.56).
 */
class GatewayConcurrencyContractTest extends AbstractGatewayContractTest {

    /** El ASR QA-2 pide 100 usuarios concurrentes por taller: aqui se prueba ese numero. */
    private static final int PETICIONES = 100;

    @Test
    @DisplayName("El borde atiende cientos de peticiones simultaneas")
    void elBordeAtiendeCientosDePeticionesSimultaneas() throws Exception {
        SERVICIO_DE_TALLERES.responderCon(200, cuerpoDeLimites("ACTIVE", "ENTERPRISE", 5000, 1000));
        String token = IDENTIDAD.token(UUID.randomUUID(), roles("MECANICO"));

        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ExecutorService ejecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Calentamiento: la primera peticion descarga el conjunto de claves y
        // consulta la cuota del taller. Lo que se mide aqui es el REGIMEN
        // ESTABLE del borde, no el arranque en frio de sus caches.
        get("/api/v1/fleet/vehicles", token);
        DESTINO.limpiar();

        List<CompletableFuture<Integer>> peticiones = new ArrayList<>();
        long inicio = System.nanoTime();
        for (int i = 0; i < PETICIONES; i++) {
            peticiones.add(CompletableFuture.supplyAsync(() -> {
                try {
                    HttpRequest peticion = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + puerto + "/api/v1/fleet/vehicles"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build();
                    return cliente.send(peticion, HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (Exception ex) {
                    return 0;
                }
            }, ejecutor));
        }

        CompletableFuture.allOf(peticiones.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        long duracionMs = (System.nanoTime() - inicio) / 1_000_000L;

        List<Integer> estados = peticiones.stream().map(CompletableFuture::join).toList();
        long correctas = estados.stream().filter(codigo -> codigo == 200).count();

        java.util.Map<Integer, Long> reparto = estados.stream()
                .collect(java.util.stream.Collectors.groupingBy(codigo -> codigo, java.util.stream.Collectors.counting()));

        assertThat(correctas)
                .as("el techo del borde no puede ser el numero de hilos de plataforma; reparto=" + reparto)
                .isEqualTo(PETICIONES);
        assertThat(estados).doesNotContain(0);
        assertThat(DESTINO.llamadasRecibidas()).isEqualTo(PETICIONES);

        ejecutor.shutdown();
    }
}
