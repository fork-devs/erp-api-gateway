package com.erp.apigateway.application.service;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.dto.OutboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.application.usecase.RouteGatewayRequestUseCase;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.ReverseProxyPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GatewayRoutingService implements RouteGatewayRequestUseCase {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host"
    );

    private final ReverseProxyPort reverseProxyPort;

    public GatewayRoutingService(ReverseProxyPort reverseProxyPort) {
        this.reverseProxyPort = reverseProxyPort;
    }

    @Override
    public ProxyResponse route(InboundRequest inboundRequest, RouteTarget target, TokenClaims claims, String traceId) {
        String targetUrl = buildTargetUrl(target.targetBaseUrl(), inboundRequest.path(), inboundRequest.queryString());

        Map<String, List<String>> outboundHeaders = new HashMap<>();

        // 1. Copiar cabeceras originales excluyendo hop-by-hop
        for (Map.Entry<String, List<String>> entry : inboundRequest.headers().entrySet()) {
            String lowerKey = entry.getKey().toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(lowerKey)) {
                outboundHeaders.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        // 2. Propagar trazabilidad W3C y X-Trace-ID
        String effectiveTraceId = (traceId != null && !traceId.trim().isEmpty()) ? traceId : UUID.randomUUID().toString().replace("-", "");
        outboundHeaders.put("X-Trace-ID", List.of(effectiveTraceId));

        String w3cTraceId = formatTo32Hex(effectiveTraceId);
        String spanId = formatTo16Hex(UUID.randomUUID().toString().replace("-", ""));
        outboundHeaders.put("traceparent", List.of("00-" + w3cTraceId + "-" + spanId + "-01"));

        // 3. Propagar identidad multi-inquilino si el token está presente
        if (claims != null) {
            outboundHeaders.put("X-Tenant-ID", List.of(claims.getTenantId().toString()));
            outboundHeaders.put("X-User-ID", List.of(claims.getUserId().toString()));
            // Se propagan TODOS los roles del token: con el singular, un
            // usuario con varios roles perdia los demas, y downstream acababa
            // aplicando el primero.
            outboundHeaders.put("X-User-Role", List.copyOf(claims.getRoles()));
            outboundHeaders.put("X-User-Email", List.of(claims.getEmail()));
        }

        OutboundRequest outboundRequest = new OutboundRequest(
                targetUrl,
                inboundRequest.method(),
                outboundHeaders,
                inboundRequest.body(),
                target.timeoutMs()
        );

        return reverseProxyPort.forward(outboundRequest);
    }

    private String buildTargetUrl(String baseUrl, String path, String queryString) {
        StringBuilder url = new StringBuilder(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        if (path != null && !path.isEmpty()) {
            if (!path.startsWith("/")) url.append("/");
            url.append(path);
        }
        if (queryString != null && !queryString.trim().isEmpty()) {
            url.append("?").append(queryString);
        }
        return url.toString();
    }

    private String formatTo32Hex(String raw) {
        String clean = raw.replace("-", "").toLowerCase();
        if (clean.length() == 32) return clean;
        if (clean.length() > 32) return clean.substring(0, 32);
        return String.format("%32s", clean).replace(' ', '0');
    }

    private String formatTo16Hex(String raw) {
        String clean = raw.replace("-", "").toLowerCase();
        if (clean.length() == 16) return clean;
        if (clean.length() > 16) return clean.substring(0, 16);
        return String.format("%16s", clean).replace(' ', '0');
    }
}
