package com.erp.apigateway.application;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.dto.OutboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.application.service.GatewayRoutingService;
import com.erp.apigateway.domain.exception.InvalidTenantFilterException;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.ReverseProxyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayRoutingServiceTest {

    @Mock
    private ReverseProxyPort reverseProxyPort;

    private GatewayRoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new GatewayRoutingService(reverseProxyPort);
    }

    @Test
    @DisplayName("Should inject tenant context and OTel trace headers to outbound request")
    void shouldInjectHeadersAndRoute() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TokenClaims claims = new TokenClaims(userId, tenantId, Set.of("MECANICO"), "mecanico@taller.pe", "erp-auth", Instant.now(), Instant.now().plusSeconds(3600));
        RouteTarget target = new RouteTarget("fleet-service", "http://localhost:8083", 5000, true);

        InboundRequest inboundRequest = new InboundRequest(
                "POST",
                "/api/v1/fleet/vehicles",
                "page=1&size=10",
                Map.of(
                        "Content-Type", List.of("application/json"),
                        "Host", List.of("api.erp-automotive.pe"),
                        "Connection", List.of("keep-alive")
                ),
                "{\"plate\":\"ABC-123\"}".getBytes(),
                "127.0.0.1"
        );

        when(reverseProxyPort.forward(any(OutboundRequest.class)))
                .thenReturn(new ProxyResponse(201, Map.of("Content-Type", List.of("application/json")), "{}".getBytes()));

        ProxyResponse response = routingService.route(inboundRequest, target, claims, "abcdef1234567890abcdef1234567890");

        assertNotNull(response);
        assertEquals(201, response.statusCode());

        ArgumentCaptor<OutboundRequest> captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(reverseProxyPort, times(1)).forward(captor.capture());

        OutboundRequest outbound = captor.getValue();
        assertEquals("http://localhost:8083/api/v1/fleet/vehicles?page=1&size=10", outbound.targetUrl());
        assertEquals("POST", outbound.method());

        // Header assertions
        Map<String, List<String>> headers = outbound.headers();
        assertEquals(tenantId.toString(), headers.get("X-Tenant-ID").get(0));
        assertEquals(userId.toString(), headers.get("X-User-ID").get(0));
        assertEquals("MECANICO", headers.get("X-User-Role").get(0));
        assertEquals("mecanico@taller.pe", headers.get("X-User-Email").get(0));
        assertEquals("abcdef1234567890abcdef1234567890", headers.get("X-Trace-ID").get(0));
        assertTrue(headers.containsKey("traceparent"));

        // Hop-by-hop headers should be stripped
        assertFalse(headers.containsKey("Host"));
        assertFalse(headers.containsKey("Connection"));
    }

    @Test
    @DisplayName("Should not forward the identity headers injected by the client")
    void noDebeReenviarLaIdentidadInyectadaPorElCliente() {
        // El atacante es el taller B y el taller A es la victima: el atacante
        // intenta que el servicio de destino crea que el principal es un
        // usuario del taller A (hallazgo 6.30).
        UUID usuarioAtacante = UUID.randomUUID();
        UUID tallerAtacante = UUID.randomUUID();
        UUID victimaDelTallerA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        TokenClaims claims = new TokenClaims(usuarioAtacante, tallerAtacante, Set.of("MECANICO"),
                "atacante@tallerB.pe", "erp-auth", Instant.now(), Instant.now().plusSeconds(3600));
        RouteTarget target = new RouteTarget("fleet-service", "http://localhost:8083", 5000, true);

        InboundRequest inboundRequest = new InboundRequest(
                "GET",
                "/api/v1/fleet/vehicles",
                null,
                Map.of(
                        "X-User-ID", List.of(victimaDelTallerA.toString()),
                        "X-User-Role", List.of("SUPERADMIN"),
                        "X-User-Email", List.of("admin@tallerA.pe"),
                        "X-Tenant-ID", List.of("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                ),
                new byte[0],
                "127.0.0.1"
        );

        when(reverseProxyPort.forward(any(OutboundRequest.class)))
                .thenReturn(new ProxyResponse(200, Map.of(), new byte[0]));

        routingService.route(inboundRequest, target, claims, "traza");

        ArgumentCaptor<OutboundRequest> captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(reverseProxyPort).forward(captor.capture());
        Map<String, List<String>> headers = captor.getValue().headers();

        assertEquals(usuarioAtacante.toString(), headers.get("X-User-ID").get(0));
        assertEquals("MECANICO", headers.get("X-User-Role").get(0));
        assertEquals("atacante@tallerB.pe", headers.get("X-User-Email").get(0));
        assertEquals(tallerAtacante.toString(), headers.get("X-Tenant-ID").get(0));
    }

    @Test
    @DisplayName("Should not forward the identity headers on a public route")
    void noDebeReenviarLaIdentidadEnUnaRutaPublica() {
        RouteTarget target = new RouteTarget("auth-service", "http://localhost:8081", 10000, false);

        InboundRequest inboundRequest = new InboundRequest(
                "POST",
                "/api/v1/auth/login",
                null,
                Map.of(
                        "X-User-ID", List.of("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "X-User-Role", List.of("SUPERADMIN"),
                        "X-User-Email", List.of("admin@tallerA.pe"),
                        "X-Tenant-ID", List.of("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                ),
                new byte[0],
                "127.0.0.1"
        );

        when(reverseProxyPort.forward(any(OutboundRequest.class)))
                .thenReturn(new ProxyResponse(200, Map.of(), new byte[0]));

        routingService.route(inboundRequest, target, null, "traza");

        ArgumentCaptor<OutboundRequest> captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(reverseProxyPort).forward(captor.capture());
        Map<String, List<String>> headers = captor.getValue().headers();

        assertFalse(headers.containsKey("X-User-ID"));
        assertFalse(headers.containsKey("X-User-Role"));
        assertFalse(headers.containsKey("X-User-Email"));
        assertFalse(headers.containsKey("X-Tenant-ID"));
    }

    @Test
    @DisplayName("Should forward the tenant filter on a startup route")
    void debeReenviarElFiltroDeTallerEnUnaRutaDeArranque() {
        UUID taller = UUID.randomUUID();
        RouteTarget target = new RouteTarget("auth-service", "http://localhost:8081", 10000, false, null, true);

        InboundRequest inboundRequest = new InboundRequest(
                "POST",
                "/api/v1/auth/login",
                null,
                Map.of("X-Tenant-ID", List.of(taller.toString())),
                new byte[0],
                "127.0.0.1"
        );

        when(reverseProxyPort.forward(any(OutboundRequest.class)))
                .thenReturn(new ProxyResponse(200, Map.of(), new byte[0]));

        routingService.route(inboundRequest, target, null, "traza");

        ArgumentCaptor<OutboundRequest> captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(reverseProxyPort).forward(captor.capture());

        assertEquals(taller.toString(), captor.getValue().headers().get("X-Tenant-ID").get(0));
    }

    @Test
    @DisplayName("Should reject a tenant filter that is not an identifier")
    void debeRechazarUnFiltroDeTallerQueNoEsIdentificador() {
        RouteTarget target = new RouteTarget("auth-service", "http://localhost:8081", 10000, false, null, true);

        InboundRequest inboundRequest = new InboundRequest(
                "POST",
                "/api/v1/auth/login",
                null,
                Map.of("X-Tenant-ID", List.of("no-es-un-uuid")),
                new byte[0],
                "127.0.0.1"
        );

        assertThrows(InvalidTenantFilterException.class,
                () -> routingService.route(inboundRequest, target, null, "traza"));

        verify(reverseProxyPort, never()).forward(any(OutboundRequest.class));
    }
}
