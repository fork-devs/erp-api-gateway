package com.erp.apigateway.application;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.dto.OutboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.application.service.GatewayRoutingService;
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
        TokenClaims claims = new TokenClaims(userId, tenantId, "MECANICO", "mecanico@taller.pe", "erp-auth", Instant.now(), Instant.now().plusSeconds(3600));
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
}
