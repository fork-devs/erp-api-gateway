package com.erp.apigateway.infrastructure.client;

import com.erp.apigateway.domain.exception.TenantStatusUnavailableException;
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

    /**
     * Limite de trafico por defecto: el del plan MAS BASICO. Se usa cuando el
     * tenant-service no ha enviado los limites del plan (ADR-007). Es el mas
     * bajo a proposito: caer hacia abajo nunca entrega mas de lo prometido.
     */
    static final int LIMITE_POR_DEFECTO_POR_MINUTO = 100;
    static final int LIMITE_POR_DEFECTO_CONCURRENTES = 15;

    private final GatewayProperties gatewayProperties;
    private final RestClient restClient;
    private final Cache<UUID, TenantQuota> quotaCache;

    public TenantServiceClientAdapter(GatewayProperties gatewayProperties) {
        this(gatewayProperties, RestClient.builder());
    }

    public TenantServiceClientAdapter(GatewayProperties gatewayProperties, RestClient.Builder restClientBuilder) {
        this.gatewayProperties = gatewayProperties;
        // El cliente se construye con el builder AUTO-CONFIGURADO, que es el
        // unico que trae los timeouts de conexion y de lectura. Con un
        // builder propio, un tenant-service colgado bloqueaba el hilo
        // indefinidamente (hallazgo 6.31).
        this.restClient = restClientBuilder.build();
        this.quotaCache = Caffeine.newBuilder()
                .expireAfterWrite(gatewayProperties.getTenantQuotaCacheTtl())
                .maximumSize(5000)
                .build();
    }

    public TenantServiceClientAdapter(GatewayProperties gatewayProperties, RestClient restClient) {
        this.gatewayProperties = gatewayProperties;
        this.restClient = restClient;
        this.quotaCache = Caffeine.newBuilder()
                .expireAfterWrite(gatewayProperties.getTenantQuotaCacheTtl())
                .maximumSize(5000)
                .build();
    }

    @Override
    public TenantQuota getQuota(UUID tenantId, String callerToken) {
        if (tenantId == null) {
            return TenantQuota.createDefault(UUID.randomUUID(), "BASICO");
        }

        return quotaCache.get(tenantId, key -> fetchQuotaFromService(key, callerToken));
    }

    public void putCache(UUID tenantId, TenantQuota quota) {
        quotaCache.put(tenantId, quota);
    }

    private TenantQuota fetchQuotaFromService(UUID tenantId, String callerToken) {
        String baseUrl = gatewayProperties.getTenantServiceUrl();
        String url = baseUrl + "/api/v1/internal/tenants/" + tenantId + "/limits";

        try {
            log.debug("Consultando cuota y estado del tenant {} en {}", tenantId, url);
            org.springframework.web.client.RestClient.RequestHeadersSpec<?> spec = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON);

            // El token del llamante se reenvia: el servicio de tenants deriva
            // el taller de la peticion del token verificado, de modo que una
            // consulta sin token no podria resolver el taller.
            if (callerToken != null && !callerToken.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + callerToken);
            }

            TenantLimitsDto dto = spec.retrieve().body(TenantLimitsDto.class);

            if (dto != null) {
                return new TenantQuota(
                        dto.tenantId() != null ? dto.tenantId() : tenantId,
                        dto.status(),
                        dto.planCode(),
                        limiteDeTrafico(dto.maxRequestsPerMinute(), LIMITE_POR_DEFECTO_POR_MINUTO),
                        limiteDeTrafico(dto.maxConcurrentSlots(), LIMITE_POR_DEFECTO_CONCURRENTES)
                );
            }
        } catch (Exception e) {
            // Modo CERRADO: si no se puede determinar el estado del taller, la
            // peticion se rechaza. Devolver una cuota por defecto con estado
            // ACTIVE hacia que un taller SUSPENDIDO volviera a operar con
            // normalidad cada vez que el tenant-service no respondia
            // (hallazgo 6.31).
            log.warn("No se pudo determinar el estado del tenant {} desde {}: {}", tenantId, url, e.getMessage());
            throw new TenantStatusUnavailableException(
                    "No se pudo determinar el estado del taller: " + e.getMessage(), e);
        }

        throw new TenantStatusUnavailableException(
                "El servicio de talleres no devolvio los limites del taller " + tenantId);
    }

    /**
     * El limite de trafico lo posee el tenant-service, que es el dueno del
     * plan (ADR-007). Aqui NO se deduce del codigo de plan: se usa el que
     * llega, y si no llega se cae al MAS BAJO.
     *
     * <p>Caer hacia abajo y nunca hacia arriba es deliberado: un despliegue
     * desordenado, en el que el gateway va por delante del tenant-service, no
     * puede PROMETER mas de lo que el plan permite.
     */
    private int limiteDeTrafico(Integer limiteDelPlan, int limitePorDefecto) {
        if (limiteDelPlan == null || limiteDelPlan <= 0) {
            return limitePorDefecto;
        }
        return limiteDelPlan;
    }
}
