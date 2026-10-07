package com.erp.apigateway.infrastructure.client;

import com.erp.apigateway.domain.model.TenantQuota;
import com.erp.apigateway.domain.port.TenantQuotaPort;
import com.erp.apigateway.infrastructure.client.dto.TenantLimitsDto;
import com.erp.apigateway.infrastructure.config.GatewayProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

public class TenantServiceClientAdapter implements TenantQuotaPort {

    private static final Logger log = LoggerFactory.getLogger(TenantServiceClientAdapter.class);

    private final GatewayProperties gatewayProperties;
    private final RestClient restClient;
    private final Cache<UUID, TenantQuota> quotaCache;

    public TenantServiceClientAdapter(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
        this.restClient = RestClient.builder().build();
        this.quotaCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(5000)
                .build();
    }

    public TenantServiceClientAdapter(GatewayProperties gatewayProperties, RestClient restClient) {
        this.gatewayProperties = gatewayProperties;
        this.restClient = restClient;
        this.quotaCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(5000)
                .build();
    }

    @Override
    public TenantQuota getQuota(UUID tenantId) {
        if (tenantId == null) {
            return TenantQuota.createDefault(UUID.randomUUID(), "BASICO");
        }

        return quotaCache.get(tenantId, this::fetchQuotaFromService);
    }

    public void putCache(UUID tenantId, TenantQuota quota) {
        quotaCache.put(tenantId, quota);
    }

    private TenantQuota fetchQuotaFromService(UUID tenantId) {
        String baseUrl = gatewayProperties.getTenantServiceUrl();
        String url = baseUrl + "/api/v1/internal/tenants/" + tenantId + "/limits";

        try {
            log.debug("Consultando cuota y estado del tenant {} en {}", tenantId, url);
            TenantLimitsDto dto = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(TenantLimitsDto.class);

            if (dto != null) {
                return new TenantQuota(
                        dto.tenantId() != null ? dto.tenantId() : tenantId,
                        dto.status(),
                        dto.planCode(),
                        resolveMaxReqPerMin(dto.planCode()),
                        resolveMaxConcurrentSlots(dto.planCode())
                );
            }
        } catch (Exception e) {
            log.warn("No se pudo obtener la cuota del tenant {} desde {}: {}. Usando cuota por defecto.", tenantId, url, e.getMessage());
        }

        return TenantQuota.createDefault(tenantId, "BASICO");
    }

    private int resolveMaxReqPerMin(String planCode) {
        if (planCode == null) return 100;
        return switch (planCode.toUpperCase()) {
            case "ENTERPRISE" -> 2000;
            case "PRO" -> 500;
            default -> 100;
        };
    }

    private int resolveMaxConcurrentSlots(String planCode) {
        if (planCode == null) return 15;
        return switch (planCode.toUpperCase()) {
            case "ENTERPRISE" -> 100;
            case "PRO" -> 40;
            default -> 15;
        };
    }
}
