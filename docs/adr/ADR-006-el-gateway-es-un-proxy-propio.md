# ADR-006 — Adopción de Kong Gateway OSS (DB-less) en Azure Container Apps

- **Estado:** Aceptado (sustituye y revierte la decisión original de proxy propio en Spring MVC)
- **Fecha:** 2026-10-27
- **Ámbito:** `erp-api-gateway` y, por consecuencia, toda la plataforma (el gateway es el borde único)
- **Copia canónica:** `erp-api-gateway/docs/adr/ADR-006-el-gateway-es-un-proxy-propio.md` (ruta relativa al workspace; este repositorio guarda un espejo)

## Contexto

El gateway es el **borde único** de la plataforma: enruta, valida el token, propaga el taller, aplica el plan del taller (rate-limit) y protege los servicios internos. Todo el tráfico de la plataforma lo atraviesa.

En la primera etapa de diseño se implementó un proxy inverso artesanal sobre Spring MVC (Java 21, Tomcat con hilos virtuales, Nimbus JOSE y Resilience4j). Dicha decisión respondió a la intención de mantener una arquitectura homogénea de cuatro capas en Java en todos los microservicios.

Sin embargo, al planificar el despliegue de producción en **Azure Container Apps (ACA)** y evaluar los costos operativos y de mantenimiento de la plataforma, surgieron factores determinantes:

1. **Costo de mantenimiento de código de infraestructura:** Mantener un proxy HTTP personalizado significaba sostener ~3.400 líneas de código Java de reenvío, buffers de streaming, clientes HTTP y lógica de reintentos que no aportan valor de negocio automotriz y requerían auditoría continua.
2. **Requisitos de Azure Container Apps (ACA):** La plataforma de Azure Container Apps funciona bajo un modelo serverless/stateless con escalado a cero (*scale-to-zero*), arranque rápido y asignación de recursos optimizada. Una JVM con Spring Boot consume entre 300 y 500 MB de RAM por réplica y tiene un arranque en frío de varios segundos, lo que encarece y degrada el escalado elástico en la nube.
3. **Condición de revisión cumplida:** El propio ADR-006 original establecía en sus condiciones de revisión (§Condiciones de revisión, ítem 4) que si el despliegue exigía *scale-to-zero*, arranques en frío mínimos o si la huella de la JVM limitaba el presupuesto de infraestructura, la decisión debía revertirse a favor de una solución de borde de alta eficiencia.

## Decisión

**1. Adopción de Kong Gateway Open Source (OSS) en modo DB-less (declarativo).**
Se reemplaza la implementación Spring MVC por **Kong Gateway OSS** (licencia Apache 2.0). Kong se ejecuta en modo **DB-less (`KONG_DATABASE=off`)**, leyendo su configuración de un archivo estático `kong.yml` empaquetado directamente en la imagen de contenedor (*baking the config*). No se utiliza base de datos relacional para el gateway, eliminando costos de bases de datos administradas y migraciones en frío.

**2. Topología de Red en Azure Container Apps (ACA).**
* **Ingress Externo:** Únicamente el contenedor de Kong tiene habilitado el acceso público (`ingress.external = true`) en el puerto `8000`, aprovechando la terminación TLS/HTTPS y el certificado administrado automático y gratuito de Azure.
* **Ingress Interno:** Todos los microservicios transaccionales (`erp-auth-service`, `erp-tenant-service`, `erp-fleet-service`, `erp-inventory-service`, `erp-work-order-service`, `erp-integration-hub`) se configuran con `ingress.external = false`. La resolución DNS interna de ACA (`http://<servicio>:puerto`) conecta a Kong con los servicios dentro del entorno virtual privado, haciendo imposible que clientes externos se salten el gateway.

**3. Validación de JWT y JWKS en el Borde mediante Plugin OIDC.**
Para evitar la compra de la versión Enterprise de Kong y no depender de claves públicas estáticas, la imagen Docker de Kong instala vía LuaRocks el plugin comunitario `kong-openid-connect` (basado en `lua-resty-openidc`). Este plugin consulta dinámicamente y cachea el endpoint `/.well-known/jwks.json` expuesto por `erp-auth-service`, validando la firma del token (RS256) antes de reenviar el tráfico a los servicios internos. Como defensa en profundidad, los microservicios transaccionales conservan la capacidad de revalidar el token para la inyección de `tenant_id` en la política RLS de PostgreSQL.

**4. Rate Limiting Distribuido con Backend Redis.**
Se activa el plugin oficial de Kong `rate-limiting` configurado con política `redis`. Las cuotas de peticiones se sincronizan contra una instancia de Redis interna en Azure Container Apps. Con ello, si Kong escala horizontalmente a múltiples réplicas en ACA, los límites de peticiones por taller o IP se aplican de forma global y consistente.

**5. Sanitización y Protección de Cabeceras de Identidad.**
Se configura el plugin `request-transformer` de Kong para eliminar (*strip*) cualquier cabecera `X-Tenant-ID` inyectada maliciosamente por clientes externos. La identidad y el contexto del taller viajan de manera inviolable en los claims verificados del token JWT.

**6. Configuración Declarativa Versionada en Git (GitOps).**
Toda la definición de servicios, rutas hacia microservicios, CORS y plugins reside en `kong.yml`. Cualquier adición de ruta o servicio nuevo se gestiona como código en el repositorio, reconstruyendo la imagen Docker y activando una nueva revisión en ACA sin tiempo de inactividad (*zero-downtime*).

## Alternativas Descartadas

- **Mantener el proxy propio en Spring MVC:** Descartado. Exige mantener miles de líneas de código Java artesanal de proxy, aumenta el consumo de memoria en Azure Container Apps y ralentiza el arranque en frío ante eventos de escalado.
- **Kong Gateway con PostgreSQL (DB Mode):** Descartado. Obligaría a aprovisionar una base de datos PostgreSQL administrada en Azure exclusivamente para Kong (~15-30 USD/mes), requiriendo además tareas de migración (`kong migrations`) previas a cada despliegue.
- **Spring Cloud Gateway (Reactivo o MVC):** Descartado por las mismas razones expuestas en el análisis inicial: complejidad reactiva innecesaria y huella de memoria superior frente a un runtime nativo en C/OpenResty.
- **Kong Konnect / Kong Enterprise:** Descartado para cumplir con el requisito estricto de presupuesto cero en licencias de software de borde.

## Consecuencias

### Positivas
- **Ahorro operativo y de infraestructura:** Kong OSS en modo DB-less requiere solo ~80-120 MB de RAM y 0.25 vCPU, operando dentro del nivel de consumo gratuito de Azure Container Apps.
- **Desacoplamiento total:** Se eliminan más de 3.400 líneas de código Java en `erp-api-gateway`. El borde pasa a ser una pieza de infraestructura estándar de la industria.
- **Arranque instantáneo (< 1 segundo):** Permite aprovechar plenamente la capacidad de *scale-to-zero* de Azure Container Apps.
- **Seguridad en capas:** TLS administrado por Azure en el borde, validación de firma JWKS en Kong y aislamiento RLS estricto en cada microservicio de destino.

### Negativas / Mitigaciones
- **Dependencia de plugin comunitario de Lua:** El soporte de JWKS dinámico en Kong OSS requiere compilar el plugin `kong-openid-connect` en el `Dockerfile`. *Mitigación:* Se empaqueta en una imagen base estable fijando la versión de LuaRocks y se valida en la integración continua.
- **Pérdida de lógica de autorización ultra-personalizada en el borde:** Kong enruta y valida la firma del token, mientras que la autorización de granularidad fina (roles por endpoint y permisos sobre la orden) se ejecuta en las anotaciones `@PreAuthorize` de cada microservicio Spring Boot.
