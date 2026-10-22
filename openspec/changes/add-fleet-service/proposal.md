# Proposal

## Why

`erp-fleet-service` **ya existe como scaffold** (6 commits, 70 ficheros, estructura Clean Architecture completa, dos migraciones Flyway, API REST con OpenAPI). Pero hoy **no sirve para nada**:

**El servicio es inalcanzable.** El gateway reenvía `/api/v1/fleet/**` **sin quitar el prefijo** (`GatewayRoutingService.java:82-92` concatena la ruta base con la ruta completa), y el servicio expone `/api/v1/customers` y `/api/v1/vehicles`. Toda petición que llega por el gateway termina en **404**. La convención de la plataforma es que el prefijo del gateway **es** la ruta del servicio (`/api/v1/auth`, `/api/v1/tenants`, `/api/v1/integrations/**`), y fleet es el único que la incumple.

**El servicio está abierto a cualquiera.** `SecurityConfig.java:26-29` deja `/api/v1/**` en `permitAll`: sin token, cualquiera crea, lista, actualiza y desactiva clientes y vehículos de **cualquier** taller. Y el taller se toma de la cabecera `X-Tenant-ID` (`@RequestHeader("X-Tenant-ID")` en todos los endpoints), **sin validar ningún token**: `nimbus-jose-jwt` está declarado en el `pom.xml` pero no hay filtro, y `erp.auth.jwks-uri` está configurado y no se usa. Son los mismos defectos que `fix-tenant-isolation` cerró en el tenant-service (P0 5.1, 5.2, 5.4; P1 6.6, 6.14, 6.53), todavía sin aplicar aquí.

**El aislamiento en base de datos es inerte.** Las migraciones hacen `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` **sin `FORCE`**, y la aplicación conecta con `fleet_user`, que es el **propietario** de las tablas: en PostgreSQL el propietario **elude** su propia política. Además `current_setting('app.tenant_id', TRUE)` resuelve a `NULL` cuando nadie establece el contexto —y nadie lo establece—, y `baseline-on-migrate: true` hace que, sobre una base ya existente, Flyway **no aplique** las migraciones y la RLS nunca llegue a crearse.

**La validación contra el hub no existe.** `doc/arquitectura-y-conexiones.md` §4.2 la describe como implementada —registro en `PENDING`, circuit breaker, reintentos—, pero **no hay cliente HTTP, ni caché, ni circuit breaker, ni barrido**: el registro se queda en `PENDING` para siempre. `ValidationStatus` tiene `PENDING/VALIDATED/FAILED`, y `FAILED` no distingue "el hub dijo que el documento no cuadra" de "el hub no respondió": dos situaciones con manejo distinto.

**Y lo que sí está construido está bien.** El dominio es limpio (sin framework), los casos de uso son interfaces en `application/usecase`, los mappers están separados de los adapters, los índices únicos por taller son correctos, `ddl-auto: validate` está activo, el puerto es el 8083 que el gateway espera, y la decisión de **REST/HTTPS** contra el hub ya está tomada en §4.2 de la doc, **coincidiendo con el diagrama C4** (`doc-viewpoints-diagrams.md:505`). Este change **no rehace el scaffold: lo completa y lo asegura**.

## What Changes

- **La API se publica bajo el prefijo que el gateway reenvía** — las rutas pasan de `/api/v1/customers` y `/api/v1/vehicles` a `/api/v1/fleet/customers` y `/api/v1/fleet/vehicles`. **BREAKING** para quien consume el servicio por su URL actual. La alternativa descartada es cambiar el gateway para que quite el prefijo: rompería la convención de la plataforma y dejaría las rutas de fleet sin espacio de nombres frente a las de los demás servicios.
- **El taller se deriva del token verificado** — la cabecera `X-Tenant-ID` deja de ser autoridad y pasa a ser una **pista** que se contrasta con el `tenant_id` del token; si no coinciden, la petición se rechaza. **BREAKING**: un cliente que hoy envía la cabecera sin token válido deja de funcionar, que es exactamente lo que debe pasar.
- **Cada endpoint exige autenticación y declara su rol** — `/api/v1/**` sale de `permitAll`; se valida el JWT contra el JWKS del emisor y cada endpoint declara el rol que exige, con **403** por rol insuficiente. Cierra P1 6.19 en este servicio.
- **El aislamiento en base de datos pasa a ser efectivo** — `FORCE ROW LEVEL SECURITY`, rol de ejecución que **no** es propietario de las tablas, contexto de taller establecido con `SET LOCAL` **dentro** de la transacción y con el orden de advice declarado, y **fail-closed**: sin contexto, la operación se rechaza en lugar de ejecutarse sin filtrar. Cierra P1 6.6 y P1 6.14 en este servicio.
- **`baseline-on-migrate` pasa a `false`** — el servicio es dueño de su esquema: sobre una base ya existente, Flyway deja de saltarse las migraciones, que es como la RLS podía no llegar a crearse.
- **La demarcación transaccional se replica** — los adapters de persistencia **dejan** de declarar `@Transactional` (hoy lo hacen en `CustomerPersistenceAdapter.java:22` y `VehiclePersistenceAdapter.java:23`), la demarcación pasa al **puerto `UnitOfWork`** del dominio implementado con `TransactionTemplate`, y se añade el test de arquitectura que falla si un adapter reintroduce la anotación. Es el patrón que `fix-transactional-outbox`established y que hoy no se sigue aquí.
- **La validación contra el hub se implementa de verdad** — cliente HTTP contra `GET /api/v1/integrations/validate/{ruc,placa}` con **caché Redis** (RUC 30 días, placa 7 días), **interruptor de circuito** con degradación a `PENDING`, **reintento programado** por taller y reintento a demanda. Cierra el vacío entre lo que §4.2 de la doc promete y lo que el código hace.
- **Los estados de validación pasan a ser `PENDING/VALID/OBSERVED`** — `VALIDATED` y `FAILED` se sustituyen: "observado" es la discrepancia que el hub informa, y un fallo del hub **no** es un resultado de validación, es un intento que se reintenta. **BREAKING** del contrato de estado.
- **El hub concede a fleet un rol acotado** — `ROLE_FLEET` por servicio, con **clave propia**: con la clave compartida actual, quien la posea puede **declararse** `erp-billing-service` y heredar sus roles, de modo que el rol acotado sin clave propia sería decorativo. Cierra la corrección que P1 6.50 enuncia.
- **Los tests corren contra PostgreSQL real** — el perfil de test deja H2 con `create-drop` y Flyway desactivado (`src/test/resources/application.yml`), con lo que las migraciones, la RLS y los índices únicos **nunca se ejecutan**; se pasa a PostgreSQL embebido con las migraciones aplicadas y `ddl-auto: validate`. Es el defecto que `fix-tenant-isolation` cerró en el tenant-service, y aquí sigue abierto porque solo existe el test `contextLoads`.
- **Los errores siguen un contrato estable** — `ProblemDetail` (RFC 9457) con código de error por familia y `tenant_id` en el error, en lugar del `Map` con `timestamp` y `error` de ahora; y la excepción no se imprime por `printStackTrace()` sino que se registra.
- **La configuración deja de llevar secretos** — `fleet_secret` como contraseña por defecto en el `application.yml` versionado pasa a variable de entorno sin valor por defecto; y la URL del JWKS se corrige: la real es `/.well-known/jwks.json` (`JwksController.java:23`), no `/oauth2/jwks`.
- **Observabilidad real** — actuator con `/health` y `/ready` que comprueban de verdad PostgreSQL y Redis, y logging estructurado con `tenant_id` y `trace_id` y el mismo esquema de eventos de seguridad que el servicio de autenticación.
- **ADR-003** — **registra** dos decisiones ya tomadas por el código y por la documentación: el transporte **REST/HTTPS** de fleet contra el hub (la alternativa gRPC queda descartada con su motivo) y la **nomenclatura canónica** `erp-fleet-service`/`erp-work-order-service`. La acción 33 del reporte exige resolverlo **antes** de construir el servicio, y ahora se cumple registrándolo.
- **Delivery** — `Dockerfile` y `compose.yaml` ya existen: se revisan, se añade `application-prod.yml` con `baseline-on-migrate: false` y `ddl-auto: validate`, y JaCoCo con umbral ≥ 80 % en `domain/`.

**No hay cambios `BREAKING` fuera del servicio**: el gateway no cambia de contrato salvo el plazo de la ruta fleet (de 10 s a 500 ms) y la adición de la ruta de roles; el hub solo cambia su configuración interna de claves y roles.

## Capabilities

### New Capabilities

- `fleet`: Registro, consulta, actualización y baja lógica de **clientes y vehículos** de un taller, con aislamiento estricto por taller derivado del token verificado, y **validación de identidad (RUC/DNI) y de vehículo (placa/VIN) no bloqueante** frente al `integration-hub`: la validación se sirve con caché, interruptor de circuito y degradación explícita a `PENDING`, de modo que la caída del proveedor externo nunca pierde ni bloquea el registro del taller.

### Modified Capabilities

Ninguna. `openspec/specs/` sigue sin specs principales: este es el sexto change del workspace y todos sus requisitos son nuevos.

## Impact

**Microservicio `erp-fleet-service` (existente, se completa y se asegura)**

| Contenido | Ficheros principales | Trazabilidad |
|:---|:---|:---|
| Autenticación, roles y contexto de taller | `infrastructure/config/SecurityConfig.java`, nuevo filtro de JWT y `TenantContext`/`TenantContextScope`, `infrastructure/persistence/TenantRlsAspect.java` | P0 5.1, 5.2, 5.4; P1 6.6, 6.14, 6.19, 6.53 |
| Esquema y RLS efectiva | `db/migration/V3__rls_y_roles_fleet.sql`, `V1/V2` revisadas, `application.yml` (`baseline-on-migrate`) | P1 6.6; acción 30 |
| Demarcación transaccional | `domain/port/UnitOfWork.java`, `infrastructure/config/UnitOfWorkAdapter.java`, `infrastructure/persistence/adapter/*`, `application/service/*` | P1 6.2 |
| Validación contra el hub | nuevo `infrastructure/client/HubValidacionAdapter.java`, `infrastructure/cache/ValidacionCacheAdapter.java`, `infrastructure/scheduler/ValidacionPendienteScheduler.java`, `domain/port/Validacion*Port.java` | §3.3 de `doc-microservices.md`; acción 30 |
| API y contrato de errores | `interfaces/rest/controller/{CustomerController,VehicleController}.java` (prefijo y roles), `interfaces/rest/advice/GlobalRestExceptionHandler.java` | P1 6.19; convención de API del proyecto |
| Pruebas | `src/test/resources/application.yml`, `src/test/java/com/erp/fleet/**` | acciones 38 y 39 |
| Delivery y secretos | `pom.xml`, `application.yml`, `application-prod.yml`, `Dockerfile`, `compose.yaml`, `.env.example` | acciones 41 y 42 |

**Microservicios existentes**

| Servicio | Cambio | Trazabilidad |
|:---|:---|:---|
| `erp-integration-hub` | Clave **por servicio** en `infrastructure/security/InternalServiceAuthenticationFilter.java` y rol `ROLE_FLEET` para `erp-fleet-service`, con ventana de compatibilidad declarada | P1 6.50 |
| `erp-api-gateway` | Plazo de la ruta `/api/v1/fleet` a 500 ms y `required-role` en las rutas de escritura de fleet | §4, "Interna sync"; P1 6.19 |

**APIs**

- `POST/GET /api/v1/fleet/customers`, `GET/PUT/DELETE /api/v1/fleet/customers/{id}` — **BREAKING**: las rutas cambian de prefijo.
- `POST/GET /api/v1/fleet/vehicles`, `GET/PUT /api/v1/fleet/vehicles/{id}`, `GET /api/v1/fleet/vehicles/customer/{customerId}` — **BREAKING**: las rutas cambian de prefijo.
- `POST /api/v1/fleet/customers/{id}/validate` y `POST /api/v1/fleet/vehicles/{id}/validate` — nuevos: reintento a demanda de la validación pendiente.
- El cuerpo de respuesta del cliente y del vehículo incorpora el estado de validación con los valores `PENDING`, `VALID` y `OBSERVED` — **BREAKING**: `VALIDATED` y `FAILED` desaparecen.
- El error pasa de un mapa libre a `ProblemDetail` con código de error estable y `tenant_id` — **BREAKING** para el cliente que leyera el campo `error`.

**Documentación**

- `docs/doc-estado-implementacion.md`: la fila 4 de §2 deja de decir "⚫ No existe" y pasa al estado real, con los hallazgos que este change cierra en este servicio.
- `docs/doc-microservices.md` §3.3: se actualiza con lo implementado y con la nota de nomenclatura del ADR-003.
- `docs/doc-database-diagrams.md` §3: se actualiza con las columnas, los índices y las políticas de RLS realmente implantadas.
- `erp-fleet-service/doc/arquitectura-y-conexiones.md`: se corrige la matriz de endpoints (§5) con el prefijo real y con la nota de que el taller viaja en el token, no en la cabecera.
- `docs/adr/ADR-003-*.md` y `docs/adr/README.md`: nuevo ADR y su entrada en el índice, con la copia canónica en el repositorio `erp-fleet-service`.
