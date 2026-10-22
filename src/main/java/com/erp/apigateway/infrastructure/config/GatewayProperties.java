package com.erp.apigateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "erp.gateway")
public class GatewayProperties {

    private String jwksUrl = "https://auth.erp-automotriz.com/.well-known/jwks.json";
    private String tenantServiceUrl = "http://localhost:8082";
    private int defaultTimeoutMs = 10000;
    private Map<String, ServiceRoute> routes = new HashMap<>();

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
}
