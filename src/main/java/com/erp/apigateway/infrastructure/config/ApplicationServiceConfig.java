package com.erp.apigateway.infrastructure.config;

import com.erp.apigateway.application.service.GatewayBulkheadService;
import com.erp.apigateway.application.service.GatewayRateLimitingService;
import com.erp.apigateway.application.service.GatewayRoutingService;
import com.erp.apigateway.application.service.GatewaySecurityService;
import com.erp.apigateway.domain.port.BulkheadPort;
import com.erp.apigateway.domain.port.JwtValidatorPort;
import com.erp.apigateway.domain.port.RateLimiterPort;
import com.erp.apigateway.domain.port.ReverseProxyPort;
import com.erp.apigateway.domain.port.TenantQuotaPort;
import com.erp.apigateway.infrastructure.bulkhead.SemaphoreBulkheadAdapter;
import com.erp.apigateway.infrastructure.client.TenantServiceClientAdapter;
import com.erp.apigateway.infrastructure.proxy.HttpReverseProxyAdapter;
import com.erp.apigateway.infrastructure.ratelimit.TokenBucketRateLimiterAdapter;
import com.erp.apigateway.infrastructure.security.JwksKeyManager;
import com.erp.apigateway.infrastructure.security.NimbusJwtValidatorAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class ApplicationServiceConfig {

    @Bean
    public JwksKeyManager jwksKeyManager(GatewayProperties gatewayProperties, Environment environment) {
        return new JwksKeyManager(
                gatewayProperties.getJwksUrl(),
                java.util.Arrays.asList(environment.getActiveProfiles()));
    }

    @Bean
    public JwtValidatorPort jwtValidatorPort(
            JwksKeyManager jwksKeyManager,
            @Value("${erp.security.jwt.issuer:https://auth.erp-automotriz.com}") String issuer,
            @Value("${erp.security.jwt.audience:erp-api-gateway}") String audience) {
        return new NimbusJwtValidatorAdapter(jwksKeyManager, issuer, audience);
    }

    @Bean
    public TenantQuotaPort tenantQuotaPort(GatewayProperties gatewayProperties, RestClient.Builder restClientBuilder) {
        // El builder auto-configurado es el que trae los timeouts: con uno
        // propio, un tenant-service colgado bloqueaba el hilo (6.31).
        return new TenantServiceClientAdapter(gatewayProperties, restClientBuilder);
    }

    @Bean
    public RateLimiterPort rateLimiterPort() {
        return new TokenBucketRateLimiterAdapter();
    }

    @Bean
    public BulkheadPort bulkheadPort() {
        return new SemaphoreBulkheadAdapter();
    }

    @Bean
    public ReverseProxyPort reverseProxyPort() {
        return new HttpReverseProxyAdapter();
    }

    @Bean
    public GatewaySecurityService gatewaySecurityService(
            JwtValidatorPort jwtValidatorPort,
            TenantQuotaPort tenantQuotaPort) {
        return new GatewaySecurityService(jwtValidatorPort, tenantQuotaPort);
    }

    @Bean
    public GatewayRateLimitingService gatewayRateLimitingService(RateLimiterPort rateLimiterPort) {
        return new GatewayRateLimitingService(rateLimiterPort);
    }

    @Bean
    public GatewayBulkheadService gatewayBulkheadService(BulkheadPort bulkheadPort) {
        return new GatewayBulkheadService(bulkheadPort);
    }

    @Bean
    public GatewayRoutingService gatewayRoutingService(ReverseProxyPort reverseProxyPort) {
        return new GatewayRoutingService(reverseProxyPort);
    }
}
