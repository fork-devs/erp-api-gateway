# Design

## Context

Estado actual, verificado sobre el código clonado (`erp-fleet-service`, rama `develop`, commit `fc15135`, 70 ficheros). La motivación está en `proposal.md` — Why.

**Lo que está construido y se conserva**

- Clean Architecture de cuatro capas con los nombres de paquete de la plataforma (`com.erp.fleet.{domain,application,infrastructure,interfaces}`), dominio sin framework, value objects con validación (`TenantId`, `CustomerId`, `VehicleId`, `Plate`, `DocNumber`), puertos de dominio, casos de uso como interfaces en `application/usecase` e implementación en `application/service`, mappers separados de los adapters, `ddl-auto: validate`, Flyway como dueño del esquema, índices únicos `(tenant_id, doc_number)` y `(tenant_id, plate)`, puerto 8083, `Dockerfile` y `compose.yaml`.
- La decisión de **validación asíncrona con estado `PENDING`** y la de **REST/HTTPS contra el hub** (`doc/arquitectura-y-conexiones.md` §4.2), que coincide con el diagrama C4 (`docs/doc-viewpoints-diagrams.md:505`).
- La nomenclatura `erp-fleet-service`, que es la del spec canónico (§2 de `docs/doc-microservices.md`).

**Lo que está mal y este change corrige**

| # | Hecho verificado | Dónde |
|:---|:---|:---|
| 1 | `/api/v1/**` en `permitAll` | `infrastructure/config/SecurityConfig.java:26-29` |
| 2 | El taller se toma de la cabecera `X-Tenant-ID`, sin validar token; no hay filtro de JWT | `CustomerController.java:46`, `VehicleController.java:43` y demás |
| 3 | `erp.auth.jwks-uri` configurado y **sin usar**; apunta a `/oauth2/jwks`, y la ruta real es `/.well-known/jwks.json` | `application.yml`, `erp-auth-service` `JwksController.java:23` |
| 4 | RLS con `ENABLE` **sin `FORCE`**, con la aplicación conectando como `fleet_user`, que es el **propietario** | `V1__create_customers_table.sql:17-20`, `V2__create_vehicles_table.sql:15-18` |
| 5 | `baseline-on-migrate: true` | `application.yml` |
| 6 | Los adapters declaran su propio `@Transactional` | `CustomerPersistenceAdapter.java:22`, `VehiclePersistenceAdapter.java:23` |
| 7 | No existe el puerto `UnitOfWork`; la demarcación es una anotación de Spring en `application/service` | `CustomerManagementService.java:36` |
| 8 | Test en H2 con `create-drop` y Flyway desactivado; un único test `contextLoads` | `src/test/resources/application.yml`, `ErpFleetServiceApplicationTests.java` |
| 9 | La validación contra el hub **no existe**: no hay cliente, ni caché, ni circuit breaker, ni barrido | no hay paquete `infrastructure/client` |
| 10 | Rutas bajo `/api/v1/customers` y `/api/v1/vehicles`, mientras el gateway reenvía `/api/v1/fleet/**` **sin quitar el prefijo** | `GatewayRoutingService.java:82-92` |
| 11 | `ValidationStatus` = `PENDING/VALIDATED/FAILED` | `domain/model/ValidationStatus.java` |
| 12 | Errores como `Map` libre con `timestamp` y `error`; `printStackTrace()` | `GlobalRestExceptionHandler.java` |
| 13 | `fleet_secret` como contraseña por defecto en el `application.yml` versionado | `application.yml` |
| 14 | Sin actuator, sin Redis, sin resilience4j, sin JaCoCo, sin PostgreSQL embebido; `webflux` declarado sin usar | `pom.xml` |

**Restricciones del entorno**: no hay Docker, así que los tests de integración corren contra **PostgreSQL embebido real**, como en los otros tres servicios Java. La documentación de arquitectura del servicio (`doc/arquitectura-y-conexiones.md`) describe como implemented cosas que no lo están —§4.2 y §5—: este change la corrige.

## Goals / Non-Goals

**Goals:**

- Que el servicio sea **alcanzable por el gateway** y devuelva una respuesta real, no un 404.
- Que **nada** de lo que expone sea alcanzable sin token verificado, y que el taller provenga **del token**, nunca de una cabecera.
- Que el aislamiento por taller sea **efectivo en la base de datos**, no una convención de las consultas.
- Que la validación contra el hub **exista** y degrade como promete la documentación.
- Cerrar los patrones que los otros tres servicios ya tienen cerrados, **sin reescribir** el scaffold que funciona.

**Non-Goals:**

- **No** se rehace el dominio: los value objects, los agregados y los nombres de las excepciones se conservan.
- **No** se reescribe el historial ya publicado. `develop` contiene un commit de merge (`fc15135`); el trabajo de este change va en una rama **apilada sobre `develop`**, y a partir de ahora **sin merges**, como en los otros cuatro repositorios.
- **No** se toca el modelo fiscal ni la numeración (son de `billing-service` y del hub, ADR-002).
- **No** se construye `erp-contracts` ni `erp-observability`: fleet lleva sus propias copias, como ya hacen el gateway y el hub con el cliente del conjunto de claves.
- **No** se implementa la saga de stock ni la orden de trabajo: es de `erp-work-order-service`.
- **No** se migra el gateway a gRPC.

## Decisions

### D1. El servicio se publica bajo `/api/v1/fleet`, y el gateway no se toca

**Decisión.** Los endpoints pasan a `/api/v1/fleet/customers` y `/api/v1/fleet/vehicles`. El gateway **no cambia de contrato**: su `path-prefix` ya es `/api/v1/fleet` y reenvía la ruta tal cual.

**Alternativa descartada.** Ajustar el gateway para que quite el prefijo antes de reenviar. La razón: rompe la convención de la plataforma, donde el prefijo del gateway **es** la ruta del servicio —`/api/v1/auth`, `/api/v1/tenants`, `/api/v1/plans`, `/api/v1/integrations/**`— y, sobre todo, dejaría las rutas de fleet sin espacio de nombres: `/api/v1/customers` colisionaría con cualquier ruta futura de otro servicio, y `erp-work-order-service` ya va a consumir estos mismos datos (ver §4.3 de la doc del servicio).

**Consecuencia.** Es el único cambio `BREAKING` de la API pública, y afecta **solo** a quien consume el servicio por su URL actual —hoy, nadie, porque por el gateway no到达不到 ningún endpoint.

### D2. El taller sale del token verificado; la cabecera es una pista

**Decisión.** El servicio valida el JWT contra el JWKS del emisor (`/.well-known/jwks.json`, con `https` salvo en `dev`/`local`), toma el `tenant_id` del token verificado y **contrasta** la cabecera `X-Tenant-ID` si viene: si no coinciden, rechaza.

**Alternativa descartada.** Seguir leyendo el taller de la cabecera, que es lo que hace hoy. La razón: la cabecera la pone el cliente; el gateway la inyecta, pero **nadie verifica que quien llama sea el gateway**. Con la cabecera como autoridad, cualquiera que alcance el servicio elige el taller y lee o escribe los datos de otro. Es el defecto que `fix-tenant-isolation` cerró en el tenant-service (P0 5.1, 5.2 y P1 6.6).

**Consecuencia.** El servicio **no** confía en la red interna: valida el token como los demás. La URL del JWKS se corrige y pasa a exigir `https` fuera de `dev`/`local`.

### D3. Cada endpoint declara su rol, y el gateway comprueba el suyo

**Decisión.** Se habilita la seguridad a nivel de método y cada endpoint declara el rol que exige: escritura para `SUPERADMIN`, `ADMIN_TALLER` y `ASESOR`; lectura también para `MECANICO`. En el borde, las rutas de escritura de fleet declaran `required-role`, como ya hace la ruta de administración de outbox del tenant-service.

**Alternativa descartada.** Dejar `/api/v1/**` en `permitAll`, como está hoy. La razón: un `permitAll` sobre el comodín de la API deja **todos** los endpoints abiertos: crear, listar, actualizar y dar de baja clientes y vehículos de cualquier taller, sin token. Y dejar la comprobación **solo** en el borde no sirve: el servicio es alcanzable directamente.

**Consecuencia.** Cada endpoint queda con su rol declarado y verificable, y la denegación es `403`, distinguible de un fallo de la operación.

### D4. La RLS se hace efectiva: `FORCE`, rol de ejecución y contexto en la transacción

**Decisión.** `V3__rls_y_roles_fleet.sql` añade `FORCE ROW LEVEL SECURITY`, el rol de ejecución `erp_fleet_app` (que no es propietario de las tablas) y el rol de plataforma para el barrido. El contexto de taller se establece con `SET LOCAL` **dentro** de la transacción, con el **orden de advice declarado** (interceptor transaccional en el orden 0, aspecto de RLS en el 1), y sin contexto la operación **se rechaza**.

**Alternativa descartada.** (a) **Confiar en el filtrado de cada consulta**, que es lo que hacen hoy los repositorios (`findByIdAndTenantId`, `findAllByTenantId`). La razón: es un filtro que depende de que cada consulta lo recuerde, y con `ENABLE` sin `FORCE` la RLS **no aporta nada**: el propietario la elude. (b) **Dejar la RLS como está y confiar en el `tenant_id` de la cabecera**: es exactamente el hueco que `fix-tenant-isolation` cerró con su test de regresión.

**Consecuencia.** Se replica el patrón del tenant-service: el contexto lo fija el **principal**, nunca la fila; sin contexto, la operación falla en lugar de ejecutarse sin filtrar.

### D5. `baseline-on-migrate` pasa a `false`

**Decisión.** El servicio es dueño de su esquema: `baseline-on-migrate: false`.

**Alternativa descartada.** Mantener `true`, que es lo que hay hoy. La razón: con `true`, sobre una base ya existente Flyway **marca el esquema como línea base y no aplica las migraciones**. Si el servicio se despliega contra una base creada por el `compose.yaml` actual, **la política de RLS no llega a crearse** y el servicio arranca con el illusion de estar protegido. El `baseline-on-migrate: false` es la convención del proyecto, y por eso el despliegue tiene queser el que cree el esquema.

### D6. La demarcación transaccional se replica: puerto `UnitOfWork` y adapters sin demarcación propia

**Decisión.** Se replica el patrón de `fix-transactional-outbox`: `domain/port/UnitOfWork.java` implementado por `infrastructure/config/UnitOfWorkAdapter.java` sobre `TransactionTemplate`; los casos de uso dependen del puerto y **no** de anotaciones de Spring; los adapters de persistencia **dejan** de declarar `@Transactional` y comprueban al entrar que hay una transacción activa, fallando de forma explícita si no la hay. Se añade el test de arquitectura `TransactionalDemarcationArchitectureTest`, que falla si un adapter reintroduce la anotación.

**Alternativa descartada.** Dejar `@Transactional` en los adapters y en los servicios. La razón: cada llamada a un repositorio abría su propia transacción, de modo que una operación con dos escrituras no podía ser atómica. Es el defecto que el propio cambio cerró en los tres servicios existentes, y el test de arquitectura que lo vigila es **el mismo** que se va a portar aquí.

**Consecuencia.** El `@Transactional` de Spring desaparece de `application/service`: la aplicación depende de un puerto del dominio, como exige la convención del proyecto.

### D7. El estado de validación es `PENDING/VALID/OBSERVED`

**Decisión.** El enum pasa a `PENDING`, `VALID` y `OBSERVED`.

**Alternativa descartada.** (a) Mantener `PENDING/VALIDATED/FAILED`. La razón: `FAILED` **mezcla dos situaciones distintas** —"el hub informó de una discrepancia" y "el hub no respondió"— que tienen un tratamiento opuesto: la primera es un resultado que hay que mostrar al taller, la segunda es un intento que hay que reintentar. (b) Un booleano `validado`: perdería la distinción entre pendiente, válido y observado, que es la que el modelo documentado (`docs/doc-database-diagrams.md` §3) ya fija.

**Consecuencia.** Los estados coinciden con el modelo documentado y con el `validation_status` de las migraciones.

### D8. El circuito y la degradación viven en fleet, y la caché es de fleet

**Decisión.** fleet envuelve sus llamadas al hub con **Resilience4j** y **degrada a `PENDING`**; la caché de resultados válidos es de fleet, en **Redis**, con TTL de 30 días para el RUC y 7 días para la placa.

**Alternativa descartada.** Delegar el breaker en el hub, apoyándose en su caché local. La razón: el fallback es comportamiento de fleet, y un breaker dentro del hub solo puede negarse a responder, no comunicarle a quien llama "degrada". Además la caché del hub es **local a cada instancia** (su propio OpenAPI dice "30-day TTL local cache"), de modo que con varias réplicas cada una consulta a SUNAT y RENIEC por su cuenta y consume la cuota que el hub limita por taller. Con Redis en fleet, el consumo del proveedor externo cae a una consulta por taller y por clave.

**Consecuencia.** fleet depende de Redis, que pasa a ser requisito de despliegue y entra en la comprobación de disponibilidad.

### D9. El reintento lo hace un barrido programado, acotado por taller

**Decisión.** Una tarea programada revalida los registros `PENDING` cuya próxima validación venció, **como máximo K por taller y por ejecución**. El barrido recorre los talleres con el **rol de plataforma** —el mismo precedente que el relay de reintentos del hub— y escribe cada registro **en el contexto de su propio taller**.

**Alternativa descartada.** (a) **No reintentar automáticamente**, dejando el registro en `PENDING` hasta que un operador lo pida: incumple el "se reintenta" de la spec y convierte una caída del proveedor en trabajo manual. (b) **Reintentar sin acotar por taller**: la cuota de validación del hub se comparte entre talleres, y un taller con muchos pendientes agotaría la de otro.

**Consecuencia.** fleet necesita un rol de plataforma para el barrido, con credencial distinta de la de ejecución.

### D10. El hub concede a fleet un rol acotado, con clave propia

**Decisión.** El hub resuelve la identidad del servicio que llama a partir del nombre que declara y verifica **la clave de ese servicio**, no una clave compartida: `erp.security.internal-service.keys.<servicio>` y `erp.security.internal-service.roles.<servicio>=ROLE_FLEET`. Los endpoints de validación admiten `ROLE_FLEET`.

**Alternativa descartada.** (a) **Reutilizar `INTERNAL_SERVICE`**, el rol transversal actual: con ese rol, fleet alcanzaría también los endpoints administrativos del hub. (b) **Dar a fleet un rol de administrador**: es lo que P1 6.50 cerró.

**Por qué hay que tocar también la clave.** Hoy el hub tiene **una única clave** y el nombre del servicio llega en una cabecera. Con clave compartida, cualquiera que la posea puede **declararse** `erp-billing-service` y heredar sus roles: el rol acotado sería decorativo. Por eso el cambio sustituye la clave compartida por **una clave por servicio**, con una ventana de compatibilidad declarada.

**Consecuencia.** El hub cambia su configuración de seguridad interna y fleet debe presentar su clave y su nombre. Los llamadores internos que usen hoy la clave compartida dejan de funcionar al cerrarse la ventana: es un cambio **BREAKING** del hub, y por eso se coordina con el despliegue de fleet.

### D11. El resultado de la validación se guarda en columnas explícitas

**Decisión.** `customers` incorpora `razon_social`, `estado_ruc`, `condicion_ruc`, `direccion_fiscal` y `validated_at`; `vehicles` incorpora `numero_motor`, `color`, `combustible`, `validated_at`, `validation_attempts` y `next_validation_at`. Se añade el índice parcial sobre los registros `PENDING` para el barrido.

**Alternativa descartada.** Guardar la respuesta del proveedor en una columna JSONB única. La razón: el taller **consulta** por documento, placa, VIN y nombre; con el JSONB cada consulta necesita expresión JSON sobre texto, y el modelo deja de ser verificable por el ORM (`ddl-auto: validate`). Con columnas explícitas, además, la deriva entre entidad y migración la detecta la validación del esquema.

**Consecuencia.** `docs/doc-database-diagrams.md` §3 y la doc del servicio se actualizan con estas columnas.

### D12. fleet no emite eventos de dominio en esta versión

**Decisión.** No hay outbox ni relay en fleet.

**Alternativa descartada.** Publicar `VehicleRegistered` y `CustomerRegistered` por transactional outbox. La razón: **no hay consumidor**: el diagrama C4 publica a Kafka solo desde work-order y billing, y el servicio que los consumiría (analytics) no está construido. Un outbox sin consumidor es un relay que consume recursos sin propósito. Cuando exista el primer consumidor, el patrón se replica —y el puerto `UnitOfWork` ya está resuelto por D6.

### D13. Los tests corren contra PostgreSQL real, y el servicio se prueba de verdad

**Decisión.** El perfil de test usa **PostgreSQL embebido** con las migraciones de Flyway aplicadas y `ddl-auto: validate`, exactamente como los otros tres servicios. Se añade además el test de arquitectura de la demarcación transaccional, la suite cross-tenant y una suite de comportamiento que hoy **no existe** (el repo tiene un único test, `contextLoads`).

**Alternativa descartada.** (a) **Testcontainers**: el entorno **no tiene Docker**, de modo que la suite no podría ejecutarse. (b) **H2 con `create-drop`**, que es lo que hay hoy: el esquema lo derivaría de las entidades, de modo que una migración alterada nunca se detectaría, y **la RLS, los índices únicos y las políticas nunca se ejecutarían**. Es el mismo defecto que `fix-tenant-isolation` cerró en el tenant-service.

**Consecuencia.** La suite es más lenta, pero es la única que demuestra que el aislamiento funciona.

### D14. Se conserva el scaffold; no se rehace

**Decisión.** El cambio **no** reescribe el dominio, los value objects, los nombres de las excepciones ni la estructura de paquetes. Los cambios se concentran en lo que está mal: seguridad, aislamiento, demarcación, validación, contrato de API y delivery.

**Alternativa descartada.** Rehacer el servicio con el patrón de los otros tres, que es más limpio. La razón: el scaffold es **correcto en su forma** —Clean Architecture, dominio sin framework, casos de uso como interfaces, mappers separados, `ddl-auto: validate`—, y rehacerlo produciría un diff enorme que mezcla dos cosas: corregir lo que está mal y cambiar lo que está bien. El coste de revisar un cambio mezclado es mayor que el de revisar dos cambios separados, y el historial del repositorio recién creado no merece ese borrón.

**Consecuencia.** El diff de este change es legible y cada commit responde a un defecto concreto.

## Risks / Trade-offs

- **[La ventana de compatibilidad de las claves del hub]** → se declara en la configuración, con fecha de cierre explícita, y el despliegue de fleet se coordina con ella. Si no hay llamadores internos activos, la ventana es de cero.
- **[El barrido consume la cuota por taller del hub]** → K registros por taller y por ejecución, y caché de Redis para no repetir la consulta de una clave ya validada.
- **[Un `PENDING` que nadie reintenta si el barrido está caído]** → la disponibilidad expone el estado del barrido, y existe la operación de reintento a demanda para desbloquear un registro concreto.
- **[La caché de Redis es una segunda fuente sobre la validez de un RUC]** → TTL corto (30/7 días) y el resultado del hub **siempre** invalida la entrada local.
- **[La validación queda fuera de la transacción del alta]** → es deliberado (D4 del spec): el registro no depende del proveedor. La contrapartida es que `PENDING` es un estado de primera clase, visible y con reintento.
- **[Cambiar el prefijo de las rutas rompe a quien ya las usara]** → no hay consumidor posible hoy por el gateway (D1), y el servicio es nuevo; el resto del plataforma no lo invoca todavía.
- **[Spring Boot 4.1.1 y springdoc 2.6.0, como los otros cuatro repos]** → se mantiene la versión del plataforma; el `pom.xml` no se toca en ese punto.
- **[Sin Testcontainers, la suite depende de un PostgreSQL embebido]** → la misma restricción que ya afecta a los otros tres servicios, y no hay Docker en el entorno.

## Migration Plan

1. **Preparar el hub**: desplegar la clave por servicio y el rol `ROLE_FLEET`, con la ventana de compatibilidad declarada.
2. **Rama de trabajo**: `feature/add-fleet-service` apilada sobre `develop`, sin merges, con un commit por task.
3. **Esquema**: aplicar `V3__rls_y_roles_fleet.sql` y las columnas nuevas; verificar `relforcerowsecurity` a `true` y `baseline-on-migrate: false`.
4. **Seguridad y API**: activar la validación del token, los roles por endpoint y el prefijo `/api/v1/fleet`; comprobar en el gateway que la ruta `/api/v1/fleet/customers` devuelve `200` y no `404`.
5. **Validación**: activar el cliente del hub, la caché y el barrido, ya con la clave de fleet entregada.
6. **Rollback**: el servicio es nuevo, así que revertir es desactivarlo en la tabla de rutas del gateway. El cambio del hub es reversible volviendo a la clave compartida, que se conserva en la configuración hasta el cierre de la ventana. La migración del esquema es **aditiva** (columnas, índices y políticas nuevas): no se deshace al revertir, y no afecta a los datos ya escritos.

## Open Questions

- **¿Cuándo se publica `VehicleRegistered`?** Depende del primer consumidor, que es el servicio de analítica, todavía no construido. No cambia esta spec, este enfoque ni este desglose de tareas: cuando exista el consumidor, se abre un change que añada el outbox a fleet (D12).
- **¿La lectura de datos de vehículo desde la orden de trabajo será REST o gRPC?** §4.3 de la doc del servicio ya la describe por REST (`GET /api/v1/vehicles/{id}`, `GET /api/v1/customers/{id}`), y el diagrama C4 solo reserva gRPC para `work-order→inventory`. La decisión se toma en el change de `erp-work-order-service`, no aquí.
- **¿El barrido de revalidación necesita su propio rol de plataforma, o basta con el transversal?** Depende de si el hub habilita la cuota de validación al rol de plataforma. Se resuelve mirando la configuración efectiva del hub antes de activar el barrido (tarea 7.1), y no altera la spec.
