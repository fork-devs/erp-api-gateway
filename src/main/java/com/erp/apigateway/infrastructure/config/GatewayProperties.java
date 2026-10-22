package com.erp.apigateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "erp.gateway")
public class GatewayProperties {

    private String jwksUrl = "https://auth.erp-automotriz.com/.well-known/jwks.json";
    private String tenantServiceUrl = "http://localhost:8082";
    private int defaultTimeoutMs = 10000;
    private Map<String, ServiceRoute> routes = new HashMap<>();

    /**
     * Topes del cuerpo, en bytes. El entrante acota la memoria del borde (y
     * lo acompaña la propiedad spring.codec.max-in-memory-size); el saliente
     * lo acota el adaptador de reenvio, porque ninguna propiedad lo cubre
     * (hallazgo 6.33).
     */
    private int maxInboundBodyBytes = BodyLimits.DEFAULT_INBOUND_BYTES;
    private int maxOutboundBodyBytes = BodyLimits.DEFAULT_OUTBOUND_BYTES;

    public static class ServiceRoute {
        private String pathPrefix;
        private String targetUrl;
        private boolean requiresAuth = true;
        private int timeoutMs = 10000;
        /**
         * Rol que el principal tiene que ostentar para usar la ruta. Vacio
         * significa que basta con estar autenticado.
         *
         * <p>Comprobarlo en el BORDE, antes de reenviar, evita que una
         * peticion con un rol insuficiente llegue siquiera a tocar el
         * servicio de destino.
         */
        private String requiredRole;
        /**
         * Ruta de ARRANQUE (login, refresco, restablecimiento): el cliente
         * declara el taller como filtro porque la consulta por hash ocurre
         * antes de conocer la fila. El valor viaja al destino como una
         * PISTA, que el destino contrasta con el token.
         *
         * <p>En cualquier otra ruta, la cabecera de identidad del cliente se
         * descarta (hallazgo 6.30).
         */
        private boolean tenantFilterAllowed = false;

        public ServiceRoute() {}

        public ServiceRoute(String pathPrefix, String targetUrl, boolean requiresAuth, int timeoutMs) {
            this.pathPrefix = pathPrefix;
            this.targetUrl = targetUrl;
            this.requiresAuth = requiresAuth;
            this.timeoutMs = timeoutMs;
        }

        public String getRequiredRole() {
            return requiredRole;
        }

        public void setRequiredRole(String requiredRole) {
            this.requiredRole = requiredRole;
        }

        public boolean isTenantFilterAllowed() {
            return tenantFilterAllowed;
        }

        public void setTenantFilterAllowed(boolean tenantFilterAllowed) {
            this.tenantFilterAllowed = tenantFilterAllowed;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public String getTargetUrl() {
            return targetUrl;
        }

        public void setTargetUrl(String targetUrl) {
            this.targetUrl = targetUrl;
        }

        public boolean isRequiresAuth() {
            return requiresAuth;
        }

        public void setRequiresAuth(boolean requiresAuth) {
            this.requiresAuth = requiresAuth;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    public String getTenantServiceUrl() {
        return tenantServiceUrl;
    }

    public void setTenantServiceUrl(String tenantServiceUrl) {
        this.tenantServiceUrl = tenantServiceUrl;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public Map<String, ServiceRoute> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, ServiceRoute> routes) {
        this.routes = routes;
    }

    public int getMaxInboundBodyBytes() {
        return maxInboundBodyBytes;
    }

    public void setMaxInboundBodyBytes(int maxInboundBodyBytes) {
        this.maxInboundBodyBytes = maxInboundBodyBytes;
    }

    public int getMaxOutboundBodyBytes() {
        return maxOutboundBodyBytes;
    }

    public void setMaxOutboundBodyBytes(int maxOutboundBodyBytes) {
        this.maxOutboundBodyBytes = maxOutboundBodyBytes;
    }

    public BodyLimits bodyLimits() {
        return new BodyLimits(maxInboundBodyBytes, maxOutboundBodyBytes);
    }
}
