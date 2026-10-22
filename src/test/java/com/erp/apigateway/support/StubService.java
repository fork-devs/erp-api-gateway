package com.erp.apigateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio de destino simulado, pero REAL: escucha en un puerto de verdad y
 * registra las peticiones que recibe.
 *
 * <p>Sirve para comprobar dos cosas que un mock no puede: que la peticion
 * LLEGA al destino, y que no llega cuando no debe.
 */
public final class StubService {

    private final HttpServer server;
    private final List<PeticionRecibida> peticiones = new CopyOnWriteArrayList<>();
    private final AtomicInteger llamadas = new AtomicInteger();

    private volatile int estado = 200;
    private volatile String cuerpo = "{\"ok\":true}";
    private volatile long retardoMs = 0;

    private StubService(HttpServer server) {
        this.server = server;
    }

    /**
     * Arranca el stub en un puerto concreto.
     *
     * <p>Se usan los puertos que la configuracion de produccion ya declara,
     * de modo que los tests NO tienen que sobreescribir la tabla de rutas:
     * la configuracion que se prueba es la de verdad.
     */
    public static StubService arrancar(int puerto) throws IOException {
        // Un backlog amplio: con cientos de conexiones a la vez, una cola de
        // espera corta hace que el servidor de test sea el cuello de botella
        // y que los tiempos de espera parezcan fallos del borde.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", puerto), 512);
        StubService stub = new StubService(server);
        server.createContext("/", stub::responder);
        // El servidor por defecto atiende las peticiones en el hilo que las
        // acepta: con conexiones persistentes (keep-alive) se queda bloqueado
        // con la primera y las siguientes fallan. Con un pool, el destino
        // aguanta la concurrencia que se le pide.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return stub;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void detener() {
        server.stop(0);
    }

    public void responderCon(int estado, String cuerpo) {
        this.estado = estado;
        this.cuerpo = cuerpo;
    }

    public void retardar(long ms) {
        this.retardoMs = ms;
    }

    public int llamadasRecibidas() {
        return llamadas.get();
    }

    /**
     * Llamadas recibidas en una ruta concreta (por prefijo de ruta).
     */
    public int llamadasA(String prefijoDeRuta) {
        return (int) peticiones.stream().filter(p -> p.ruta().startsWith(prefijoDeRuta)).count();
    }

    public List<PeticionRecibida> peticiones() {
        return peticiones;
    }

    public PeticionRecibida ultimaPeticion() {
        return peticiones.isEmpty() ? null : peticiones.get(peticiones.size() - 1);
    }

    public void limpiar() {
        peticiones.clear();
        llamadas.set(0);
    }

    private void responderder(HttpExchange exchange) throws IOException {
        byte[] cuerpoRecibido = leer(exchange.getRequestBody());
        peticiones.add(new PeticionRecibida(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                cabeceras(exchange),
                cuerpoRecibido));
        llamadas.incrementAndGet();

        if (retardoMs > 0) {
            try {
                Thread.sleep(retardoMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] respuesta = cuerpo.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(estado, respuesta.length);
        try (OutputStream salida = exchange.getResponseBody()) {
            salida.write(respuesta);
        }
    }

    private void responder(HttpExchange exchange) throws IOException {
        responderder(exchange);
    }

    private byte[] leer(InputStream entrada) throws IOException {
        return entrada.readAllBytes();
    }

    private Map<String, List<String>> cabeceras(HttpExchange exchange) {
        return Map.copyOf(exchange.getRequestHeaders());
    }

    /**
     * Peticion que ha llegado al destino.
     */
    public record PeticionRecibida(
            String metodo,
            String ruta,
            Map<String, List<String>> cabeceras,
            byte[] cuerpo
    ) {
        public String cabecera(String nombre) {
            for (Map.Entry<String, List<String>> entrada : cabeceras.entrySet()) {
                if (entrada.getKey().equalsIgnoreCase(nombre)) {
                    return entrada.getValue().isEmpty() ? null : entrada.getValue().get(0);
                }
            }
            return null;
        }
    }
}
