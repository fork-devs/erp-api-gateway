# DriveOS — API Gateway (Kong OSS en Azure Container Apps)

Puerta de enlace (*API Gateway*) de la plataforma SaaS para talleres automotrices **DriveOS Workshop**. Implementada utilizando **Kong Gateway Open Source (OSS)** en modo declarativo sin base de datos (**DB-less**).

Conforme al [ADR-006](docs/adr/ADR-006-el-gateway-es-un-proxy-propio.md), esta implementación sustituye al prototipo previo en Spring MVC para optimizar la huella de memoria (<100 MB RAM), acelerar el tiempo de arranque en frío (<1 segundo) y maximizar la compatibilidad con el modelo serverless de **Azure Container Apps**.

---

## 1. Características Clave

* **Modo DB-less (Declarativo):** Todas las rutas, servicios y plugins se configuran en `kong.yml`, versionados en Git y empaquetados directamente en el contenedor Docker (*baking the config*).
* **Validación de Identidad en el Borde:** Mediante el plugin comunitario `kong-openid-connect` (instalado en Docker vía LuaRocks), valida la firma de los tokens JWT contra el endpoint `/.well-known/jwks.json` de `erp-auth-service`.
* **Rate Limiting Distribuido:** Plugin nativo `rate-limiting` de Kong configurado con política `redis` conectada a un nodo interno en Azure Container Apps para compartir cuotas entre réplicas elásticas.
* **Aislamiento Multi-tenant:** El plugin `request-transformer` elimina cualquier cabecera `X-Tenant-ID` entrante desde internet para prevenir suplantaciones; el contexto de taller se deriva estrictamente del token verificado.
* **CORS Global:** Configurado para clientes Web y Mobile de talleres.

---

## 2. Catálogo de Rutas

| Servicio de Destino | Prefijo de Ruta | Seguridad en el Borde | Puerto Interno |
| :--- | :--- | :--- | :---: |
| `erp-auth-service` | `/api/v1/auth/login`, `refresh`, `forgot-password`, `reset-password`, `/.well-known/jwks.json` | Pública (Sin JWT) | `8081` |
| `erp-auth-service` | `/api/v1/auth/logout`, `/api/v1/users` | Protegida (Valida JWT vía JWKS) | `8081` |
| `erp-tenant-service` | `/api/v1/plans` | Pública (Consulta de planes) | `8082` |
| `erp-tenant-service` | `/api/v1/tenants`, `/api/v1/admin/outbox` | Protegida (Valida JWT) | `8082` |
| `erp-fleet-service` | `/api/v1/fleet/**` | Protegida (Valida JWT) | `8083` |
| `erp-inventory-service`| `/api/v1/inventory/**` | Protegida (Valida JWT) | `8084` |
| `erp-integration-hub` | `/api/v1/integrations/webhooks/**` | Pública (Firma HMAC de proveedor) | `8085` |
| `erp-integration-hub` | `/api/v1/integrations/**` | Protegida (Valida JWT) | `8085` |
| `erp-work-order-service`| `/api/v1/work-orders/**` | Protegida (Valida JWT) | `8088` |

---

## 3. Ejecución Local (Docker Compose)

Para levantar Kong Gateway y Redis en tu máquina local:

```bash
docker compose up -d
```

* **Proxy público:** `http://localhost:8000`
* **Admin API (solo lectura en DB-less):** `http://localhost:8001`

Probar conectividad al gateway:
```bash
curl -i http://localhost:8000/api/v1/plans
```

---

## 4. Despliegue en Azure Container Apps

1. **Construir y publicar imagen a Azure Container Registry (ACR):**
   ```bash
   az acr build --registry <TU_ACR> --image erp-api-gateway:latest .
   ```

2. **Desplegar en Azure Container Apps:**
   ```bash
   az containerapp create \
     --name erp-api-gateway \
     --resource-group <TU_GRUPO_RECURSOS> \
     --environment <TU_ENTORNO_ACA> \
     --image <TU_ACR>.azurecr.io/erp-api-gateway:latest \
     --target-port 8000 \
     --ingress external \
     --cpu 0.25 --memory 0.5Gi \
     --env-vars KONG_DATABASE=off KONG_DECLARATIVE_CONFIG=/etc/kong/kong.yml REDIS_HOST=erp-redis-service REDIS_PORT=6379
   ```
