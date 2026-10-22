package com.erp.apigateway.application.service;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.usecase.RouteGatewayRequestUseCase;
import com.erp.apigateway.domain.exception.InvalidTenantFilterException;
import com.erp.apigateway.domain.model.OutboundRequest;
import com.erp.apigateway.domain.model.ProxyResponse;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TokenClaims;
import com.erp.apigateway.domain.port.GatewayMetricsPort;
import com.erp.apigateway.domain.port.ReverseProxyPort;

import java.time.Duration;
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

    /**
     * Cabeceras de IDENTIDAD que el cliente puede intentar inyectar.
     *
     * <p>Nunca se reenvian tal cual: en las rutas autenticadas las pone el
     * gateway desde los claims verificados del token, y en las publicas no se
     * reenvian. Sin esto, un destino que confiera en el gateway tendria un
     * vector de suplantacion de identidad (hallazgo 6.30).
     */
    private static final Set<String> IDENTITY_HEADERS = Set.of(
            "x-user-id",
            "x-user-role",
            "x-user-email",
            "x-tenant-id"
    );

    private final ReverseProxyPort reverseProxyPort;
    private final GatewayMetricsPort gatewayMetricsPort;

    public GatewayRoutingService(ReverseProxyPort reverseProxyPort) {
        this(reverseProxyPort, null);
    }

    public GatewayRoutingService(ReverseProxyPort reverseProxyPort, GatewayMetricsPort gatewayMetricsPort) {
        this.reverseProxyPort = reverseProxyPort;
        this.gatewayMetricsPort = gatewayMetricsPort;
    }

    @Override
    public ProxyResponse route(InboundRequest inboundRequest, RouteTarget target, TokenClaims claims, String traceId) {
        String targetUrl = buildTargetUrl(target.targetBaseUrl(), inboundRequest.path(), inboundRequest.queryString());

        Map<String, List<String>> outboundHeaders = new HashMap<>();

        // 1. Copiar cabeceras originales excluyendo hop-by-hop y las de
        // identidad, que las repone el gateway con datos verificados.
        for (Map.Entry<String, List<String>> entry : inboundRequest.headers().entrySet()) {
            String lowerKey = entry.getKey().toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(lowerKey) && !IDENTITY_HEADERS.contains(lowerKey)) {
                outboundHeaders.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        // 2. Propagar trazabilidad W3C y X-Trace-ID
        String effectiveTraceId = (traceId != null && !traceId.trim().isEmpty()) ? traceId : UUID.randomUUID().toString().replace("-", "");
        outboundHeaders.put("X-Trace-ID", List.of(effectiveTraceId));

        // El contexto de traza del CLIENTE se CONTINUA, no se inventa: si el
        // gateway fabrica un traceparent, la traza del borde y la de los
        // servicios de destino quedan desconectadas y ningun span respalda
        // ese identificador (hallazgo 6.39).
        outboundHeaders.put("traceparent", List.of(continuarTraceparent(inboundRequest, effectiveTraceId)));

        // 3. Propagar identidad multi-inquilino si el token está presente
        if (claims != null) {
            outboundHeaders.put("X-Tenant-ID", List.of(claims.getTenantId().toString()));
            outboundHeaders.put("X-User-ID", List.of(claims.getUserId().toString()));
            // Se propagan TODOS los roles del token: con el singular, un
            // usuario con varios roles perdia los demas, y downstream acababa
            // aplicando el primero.
            outboundHeaders.put("X-User-Role", List.copyOf(claims.getRoles()));
            outboundHeaders.put("X-User-Email", List.of(claims.getEmail()));
        } else if (target.tenantFilterAllowed()) {
            // 4. Ruta de ARRANQUE: el cliente declara el taller como filtro.
            // Viaja como una PISTA (el destino la contrasta con el token) y
            // solo si tiene forma de identificador.
            String filtro = filtroDeTallerDelCliente(inboundRequest);
            if (filtro != null) {
                outboundHeaders.put("X-Tenant-ID", List.of(filtro));
            }
        }

        OutboundRequest outboundRequest = new OutboundRequest(
                targetUrl,
                inboundRequest.method(),
                outboundHeaders,
                inboundRequest.body(),
                target.timeoutMs()
        );

        long inicio = System.nanoTime();
        try {
            ProxyResponse respuesta = reverseProxyPort.forward(outboundRequest);
            if (gatewayMetricsPort != null) {
                gatewayMetricsPort.peticionAtendida(target.serviceName(), target.serviceName(),
                        inboundRequest.method(), respuesta.statusCode(),
                        Duration.ofNanos(System.nanoTime() - inicio));
            }
            return respuesta;
        } catch (RuntimeException ex) {
            if (gatewayMetricsPort != null) {
                gatewayMetricsPort.peticionAtendida(target.serviceName(), target.serviceName(),
                        inboundRequest.method(), 502, Duration.ofNanos(System.nanoTime() - inicio));
            }
            throw ex;
        }
    }

    /**
     * Filtro de taller declarado por el cliente en una ruta de arranque.
     *
     * <p>Se valida la FORMA: un filtro que no es un identificador no se
     * reenvia, porque el destino lo usaria como criterio de busqueda y el
     * valor no identifica a nadie.
     */
    private String filtroDeTallerDelCliente(InboundRequest inboundRequest) {
        String filtro = inboundRequest.getFirstHeader("X-Tenant-ID");
        if (filtro == null || filtro.isBlank()) {
            return null;
        }
        String limpio = filtro.trim();
        try {
            return UUID.fromString(limpio).toString();
        } catch (IllegalArgumentException ex) {
            throw new InvalidTenantFilterException(
                    "El filtro de taller declarado no tiene forma de identificador");
        }
    }

    /**
     * Continua el contexto de traza W3C del cliente.
     *
     * <p>El formato es {@code version-traceId(32 hex)-spanId(16 hex)-flags}.
     * Si el cliente no manda uno valido, se genera uno propio: en ese caso
     * el identificador de traza es el del gateway.
     */
    private String continuarTraceparent(InboundRequest inboundRequest, String traceIdDelGateway) {
        String traceparent = inboundRequest.getFirstHeader("traceparent");
        if (traceparent != null && esTraceparentValido(traceparent)) {
            String[] partes = traceparent.split("-");
            // Se conserva el identificador de traza del cliente y se genera un
            // span nuevo para este salto del borde.
            return partes[0] + "-" + partes[1] + "-" + formatTo16Hex(UUID.randomUUID().toString().replace("-", "")) + "-" + partes[3];
        }
        return "00-" + formatTo32Hex(traceIdDelGateway) + "-" + formatTo16Hex(UUID.randomUUID().toString().replace("-", "")) + "-01";
    }

    private boolean esTraceparentValido(String traceparent) {
        String[] partes = traceparent.split("-");
        if (partes.length != 4) {
            return false;
        }
        return partes[1].length() == 32 && partes[2].length() == 16 && esHex(partes[1]) && esHex(partes[2]);
    }

    private boolean esHex(String valor) {
        return valor.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
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

    /**
     * Identificador de traza de 32 digitos hexadecimales.
     *
     * <p>Si el valor de partida no es hexadecimal valido, se deriva otro: un
     * traceparent con un identificador que no es hexadecimal no lo entiende
     * ningun colector de trazas.
     */
    private String formatTo32Hex(String raw) {
        String clean = raw == null ? "" : raw.replace("-", "").toLowerCase();
        if (clean.length() == 32 && esHex(clean)) {
            return clean;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String formatTo16Hex(String raw) {
        String clean = raw.replace("-", "").toLowerCase();
        if (clean.length() == 16) return clean;
        if (clean.length() > 16) return clean.substring(0, 16);
        return String.format("%16s", clean).replace(' ', '0');
    }
}
