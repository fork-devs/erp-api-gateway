# Proposal

## Why

El aislamiento multi-inquilino (QA-4) está **roto en los tres servicios que ya tienen el contrato de tenant**, y el reporte de estado (`docs/doc-estado-implementacion.md`) lo confirma con tres hallazgos P0 encadenados:

- En `erp-auth-service`, `POST /api/v1/users` toma el `tenantId` y el conjunto de `roles` **del cuerpo de la petición** (`UserController.java:58-67`). Cualquier principal autenticado —incluso un `MECANICO`— puede crear un `SUPERADMIN` dentro de un tenant ajeno (P0 5.1).
- En el mismo servicio, el filtro JWT fija el contexto de tenant **desde la cabecera `X-Tenant-ID` del cliente, antes y con independencia de validar el token** (`JwtAuthenticationFilter.java:39-45`), mientras `/api/v1/auth/**` es `permitAll` (`SecurityConfig.java:37`). Un cliente anónimo puede crear usuarios en cualquier tenant (P0 5.2).
- En `erp-tenant-service`, `SecurityConfig.java:27-33` marca `permitAll()` sobre `/api/v1/**`, comodín que cubre también `/api/v1/internal/**`. Cualquiera puede crear, suspender, re-planificar o leer cualquier taller, sin autenticación (P0 5.4).

A esto se suman tres defectos P1 que dejan el aislamiento sin respaldo en la base de datos y en el borde:

- La Row-Level Security declarada en `erp-auth-service` **es inerte**: `V3__configure_tenant_rls.sql:1-2` usa `ENABLE` sin `FORCE`, y la aplicación conecta con el usuario que posee las tablas (`compose.yaml:6-8`), que bypasea RLS por diseño de PostgreSQL. El aspecto `PostgresTenantRlsAspect.java:26-38` además traga los fallos del `SET LOCAL` y falla abierto (P1 6.6).
- `tenant_outbox` (`V1__create_tenant_tables.sql:33-41`) no tiene columna `tenant_id`, lo que impide aplicar RLS o filtrar por taller sobre la cola de eventos, que es donde vive el dato más sensible (P1 6.14).
- En `erp-integration-hub`, el tenant se toma de la cabecera `X-Tenant-ID` y, si falta, cae al tenant "todos ceros" `00000000-0000-0000-0000-000000000000` horneado en la capa de dominio (`IdentityValidationController.java:39-53`, `IdentityValidationService.java:43`) (P1 6.53).

QA-4 exige denegación del 100% de los accesos cruzados entre talleres, verificada con pruebas de penetración tenant-cross. Hoy el requisito es incumplido de forma trivial y silenciosa: no hay un solo test que intente el cruce.

## What Changes

- **P0 5.1** — `erp-auth-service`: `POST /api/v1/users` deja de aceptar `tenantId` ni `roles` del cuerpo de la petición. El tenant se deriva del token JWT verificado y el conjunto de roles asignables se calcula a partir de los roles del actor. Un `ASESOR` no puede crear un `ADMIN_TALLER` ni un `SUPERADMIN`. **BREAKING** (el contrato de la petición cambia y los clientes actuales deben dejar de enviar ambos campos).
- **P0 5.2** — `erp-auth-service`: la cabecera `X-Tenant-ID` deja de ser una fuente de tenant. El contexto de tenant se establece **exclusivamente** desde el claim `tenant_id` de un JWT validado; si el token es inválido o no trae ese claim, el contexto queda vacío y las rutas que lo exigen responden con error. La cabecera se sigue aceptando como pista de trazabilidad, nunca como autoridad. **BREAKING** (comportamiento de las rutas públicas ante `X-Tenant-ID`).
- **P0 5.4** — `erp-tenant-service`: `SecurityConfig` deja de aplicar `permitAll()` a `/api/v1/**`. Los endpoints de negocio exigirán un principal autenticado, y los endpoints de `/api/v1/internal/**` exigirán un principal de servicio a servicio, con autorización por tenant derivada del propio principal. **BREAKING** (todos los endpoints del servicio pasan a exigir credenciales).
- **P1 6.6** — `erp-auth-service`: la Row-Level Security pasa a ser efectiva. La migración añade `FORCE ROW LEVEL SECURITY` a las tablas con `tenant_id`, la aplicación deja de conectar con el propietario de las tablas y usa un rol dedicado sin privilegios de propietario, y el establecimiento del contexto de tenant deja de fallar en silencio: si no hay transacción activa, la operación que requiere aislamiento falla de forma explícita en lugar de ejecutarse sin filtro. `refresh_tokens` y `password_reset_tokens` reciben `tenant_id` y quedan cubiertas por RLS.
- **P1 6.14** — `erp-tenant-service`: `tenant_outbox` incorpora columna `tenant_id` (not null, con valor por defecto para las filas existentes) e índice parcial por `tenant_id` y `published`, de modo que la cola de eventos de dominio sea filtrable por taller y susceptible de RLS como cualquier otra tabla de negocio.
- **P1 6.53** — `erp-integration-hub`: el tenant efectivo se deriva del principal autenticado y la cabecera `X-Tenant-ID` pasa a ser opcional y puramente informativa. Se elimina el tenant "todos ceros" de la capa de dominio: cuando no hay tenant resoluble, la operación se rechaza con `403` en lugar de ejecutarse bajo un tenant ficticio. **BREAKING** (los endpoints de validación de RUC y placa dejan de tolerar la ausencia de tenant).
- **Cobertura transversal** — se incorpora una suite de pruebas cross-tenant que ejercita, contra PostgreSQL real y con el aislamiento de base de datos activo, los tres vectores de cruce (tenant en el cuerpo, tenant en la cabecera, tenant omitido) y verifica que cada uno de los tres servicios rechaza el acceso. Es la evidencia que QA-4 y `doc-tactics.md` exigen y que hoy no existe.

## Capabilities

### New Capabilities
- `tenant-isolation`: Garantía de que ningún dato de negocio de un taller es legible ni modificable desde otro taller, en los tres servicios que participan del contrato de tenant, con defensa en profundidad: derivación del tenant desde el token verificado, autorización explícita por principal y RLS efectiva en PostgreSQL como capa de的最后 recurso.

### Modified Capabilities
Ninguna. Es el primer change del repositorio y `openspec/specs/` está vacío: no existen specs principales que enmendar.

## Impact

**Microservicios afectados**

| Servicio | Archivos principales | Hallazgos cerrados |
|:---|:---|:---|
| `erp-auth-service` | `infrastructure/security/filter/JwtAuthenticationFilter.java`, `infrastructure/persistence/rls/PostgresTenantRlsAspect.java`, `infrastructure/persistence/rls/TenantContextHolder.java`, `infrastructure/config/SecurityConfig.java`, `interfaces/rest/controller/UserController.java`, `interfaces/rest/dto/request/RegisterUserRequest.java`, `application/service/RegisterUserService.java`, `application/usecase/RegisterUserUseCase.java`, `domain/port/UserRepositoryPort.java`, `infrastructure/persistence/adapter/UserPersistenceAdapter.java`, `infrastructure/persistence/entity/UserJpaEntity.java`, `infrastructure/persistence/repository/SpringDataUserRepository.java` | P0 5.1, P0 5.2, P1 6.6 |
| `erp-tenant-service` | `infrastructure/config/SecurityConfig.java`, `infrastructure/messaging/outbox/TenantOutboxRelayScheduler.java`, `domain/model/TenantOutboxMessage.java`, `infrastructure/persistence/entity/TenantOutboxJpaEntity.java`, `infrastructure/persistence/mapper/TenantOutboxPersistenceMapper.java`, `infrastructure/persistence/repository/SpringDataTenantOutboxRepository.java`, `src/main/resources/db/migration/V1__create_tenant_tables.sql` (migración nueva) | P0 5.4, P1 6.14 |
| `erp-integration-hub` | `interfaces/rest/controller/IdentityValidationController.java`, `interfaces/rest/controller/ElectronicInvoicingController.java`, `interfaces/rest/controller/PaymentIntegrationController.java`, `infrastructure/security/InternalServiceAuthenticationFilter.java`, `infrastructure/security/AuthenticatedUser.java`, `application/service/IdentityValidationService.java` (constante `SYSTEM_TENANT_ID`) | P1 6.53 |
| `erp-api-gateway` | `infrastructure/client/TenantServiceClientAdapter.java` | — (tarea 4.7: la consulta de cuotas debe reenviar el token del llamante para que el tenant-service pueda derivar el tenant) |

**APIs y contratos afectados**

- `POST /api/v1/users`: deja de aceptar `tenantId` ni `roles` en el cuerpo. **BREAKING**.
- `GET /api/v1/users`, `POST /api/v1/tenants`, `PATCH /api/v1/tenants/{id}/status`, `PATCH /api/v1/tenants/{id}/plan`, `PUT /api/v1/tenants/{id}/branding` y los endpoints `/api/v1/internal/**`: pasan a exigir credenciales. **BREAKING**.
- `GET /api/v1/integrations/validate/ruc/{ruc}` y `GET /api/v1/integrations/validate/placa/{placa}`: pasan a exigir tenant resoluble. **BREAKING**.
- Cabecera `X-Tenant-ID`: en `erp-auth-service` deja de ser autoridad de tenant; en `erp-integration-hub` deja de ser obligatoria. Su uso en el resto del sistema (gateway → servicios) no cambia en este change.

**Base de datos** — migraciones Flyway nuevas: `FORCE ROW LEVEL SECURITY` y `tenant_id` en las tablas de tokens (auth), rol de aplicación sin privilegios de propietario y política RLS equivalente, `tenant_id` en `tenant_outbox` (tenant). `baseline-on-migrate` permanece en `false` tras esta corrección.

**Dependencias y configuración** — sin dependencias de librerías nuevas (`nimbus-jose-jwt` ya está declarado en el `pom.xml` del tenant-service, sin usar). Requiere variables de entorno nuevas para el rol de aplicación de PostgreSQL en los tres servicios, y que el gateway reenvíe el token del llamante original en su consulta de límites del tenant-service (`TenantServiceClientAdapter.fetchQuotaFromService`), que hasta ahora viaja sin cabecera `Authorization`.

**Documentación** — `docs/doc-estado-implementacion.md` se actualiza al archivar el change para reflejar qué P0 quedan cerrados. `docs/doc-architecture-principles.md` §2.8 y `docs/doc-microservices.md` §2 (decisión transversal de aislamiento) no requieren cambio de contenido: este change los lleva a la práctica, no los altera.

**Fuera de alcance** — la emisión de JWT, la rotación de refresh tokens, MFA, RBAC y la revocación de access tokens pertenecen al change de ciclo de vida de identidad; la demarcación transaccional y el outbox transaccional pertenecen al change de atomicidad; el comportamiento de los adaptadores externos y la idempotencia del hub pertenecen al change de integración con proveedores. Este change no toca ninguno de ellos, salvo donde sea inseparable del aislamiento (el `tenant_id` de `user_roles`, que es requisito del propio `P0 5.5` del ciclo de vida de identidad y por tanto se coordina allí).
