# Proposal

## Why

El Transactional Outbox —el patrón que el proyecto eligió para no perder eventos de dominio y para no exigir transacciones distribuidas— **está construido pero no funciona**. Sus dos propiedades esenciales están rotas, y una de ellas tiene consecuencias fiscales y de integridad a largo plazo.

**La atomicidad no existe.** En `erp-tenant-service`, `TenantProvisioningService.provisionTenant()` (`application/service/TenantProvisioningService.java:56-72`) escribe el tenant y luego escribe su evento en el outbox, en dos llamadas separadas a dos repositorios. El único `@Transactional` del servicio está en los adapters de persistencia (`TenantPersistenceAdapter.java:27`, `TenantOutboxPersistenceAdapter.java:26`), de modo que **cada escritura abre y cierra su propia transacción**. El comentario del código —*"3. Guardar en Transactional Outbox (misma transacción local)"*— describe una garantía que el código no cumple. Un fallo entre ambas escrituras deja un taller **creado y sin su evento `TenantProvisioned`**, y fleet, inventory y billing nunca lo provisionan: una inconsistencia silenciosa y permanente, exactamente el riesgo que `doc-microservices.md` §3.2 rechazó al elegir este patrón. El mismo defecto afecta a los caminos de cambio de plan y de suspensión (`TenantManagementService.java:65`/`:92` y `:107`/`:115`) (P0 5.3).

**La causa de fondo es transversal.** En los tres servicios, la demarcación transaccional vive exclusivamente en la capa de persistencia, y **ninguna operación de negocio tiene un límite transaccional que abarque más de una escritura** (P0 5.8). `erp-integration-hub` es el caso extremo: **cero anotaciones `@Transactional` en todo el código de producción**, de modo que ni siquiera las escrituras simples están agrupadas (P1 6.2). En `erp-auth-service` la misma causa impide que la rotación de un refresh token sea atómica (§6.5) y que un reset de contraseña lo sea (§6.21).

**El relay es frágil.** `OutboxProcessorService.processUnpublishedMessages()` (`application/service/OutboxProcessorService.java:30-38`) captura cualquier excepción y ejecuta `break`, con lo que **un único mensaje envenenado bloquea indefinidamente todos los mensajes posteriores** de la cola, que se leen ordenados por `created_at`. La excepción se **traga sin registrar**, de modo que el fallo es invisible; no hay contador de intentos, ni espera entre reintentos, ni estado de cola de muertos, ni forma de recuperar un mensaje descartado. La tabla `tenant_outbox` sólo tiene `published` (P1 6.8).

**Y el evento no es deduplicable.** El identificador del evento existe sólo dentro del JSON del payload, no en una columna propia (`V1__create_tenant_tables.sql:33-41`), de modo que un consumidor no puede deduplicar por evento aunque el contrato de entrega sea "al menos una vez" (P1 7.21).

## What Changes

- **P0 5.8** — Los tres servicios incorporan un puerto `UnitOfWork` en `domain/port/`, implementado en `infrastructure/` sobre `TransactionTemplate`, y sus casos de uso lo emplean para demarcar las operaciones que abarcan más de una escritura. Los adapters de persistencia **dejan de abrir su propia transacción** y se unen a la transacción abierta por el caso de uso. La demarcación transaccional deja de estar en la capa equivocada.
- **P0 5.3** — `erp-tenant-service`: el alta de un taller, el cambio de su plan y su suspensión escriben el agregado y su evento en el outbox **dentro de la misma transacción**, de modo que el evento existe si y sólo si el cambio de negocio se confirma. El fallo de la escritura del evento aborta el cambio de negocio, y no al contrario.
- **P1 6.2** — `erp-integration-hub`: las operaciones que escriben en más de una tabla quedan dentro de una única transacción, que hoy es inexistente. Esto prepara el terreno para la semántica de idempotencia del change de integración con proveedores, que necesita esa demarcación para ser atómica.
- **P1 6.8** — `erp-tenant-service`: el relay deja de bloquear la cola ante un mensaje fallido. Cada intento fallido se **registra** con su error, se incrementa un contador de intentos y se reprograma con espera creciente; tras superar el máximo de intentos el mensaje pasa a un estado de cola de muertos, deja de reintentarse solo, **queda consultable** y puede reactivarse. Ningún mensaje envenenado impide el despacho de los demás.
- **P1 7.21** — `erp-tenant-service`: la tabla `tenant_outbox` incorpora el identificador del evento en una columna propia, además de `attempts`, `next_attempt_at` y `last_error`, de modo que un consumidor pueda deduplicar por evento y el relay pueda programar el reintento siguiente.
- **Concurrencia del relay** — La lectura de mensajes pendientes pasa a reclamar la fila (`FOR UPDATE SKIP LOCKED`), de modo que varias réplicas del servicio no publiquen el mismo mensaje, y la marca de publicado y el incremento de intentos se escriben de forma consistente con la publicación.
- **Verificación** — Cada corrección se acompaña del test que la demuestra: la atomicidad del outbox se prueba forzando el fallo de la escritura del evento y comprobando que el taller no queda creado; el no-bloqueo de la cola se prueba con un mensaje envenenado seguido de uno válido.

## Capabilities

### New Capabilities
- `transactional-outbox`: Garantía de que un cambio de negocio y el evento de dominio que lo describe se confirman o se descartan juntos, y de que la publicación de ese evento al bus nunca se pierde, nunca se duplica por un fallo de infraestructura y nunca bloquea el despacho de otros mensajes.

### Modified Capabilities
Ninguna. `openspec/specs/` sigue sin specs principales: es el segundo change del repositorio y todos sus requisitos son nuevos.

## Impact

**Microservicios afectados**

| Servicio | Archivos principales | Hallazgos cerrados |
|:---|:---|:---|
| `erp-tenant-service` | `application/service/TenantProvisioningService.java`, `application/service/TenantManagementService.java`, `application/service/OutboxProcessorService.java`, `domain/model/TenantOutboxMessage.java`, `domain/port/TenantOutboxRepositoryPort.java`, `domain/port/UnitOfWork.java` (nuevo), `infrastructure/persistence/adapter/TenantPersistenceAdapter.java`, `infrastructure/persistence/adapter/TenantOutboxPersistenceAdapter.java`, `infrastructure/persistence/entity/TenantOutboxJpaEntity.java`, `infrastructure/persistence/mapper/TenantOutboxPersistenceMapper.java`, `infrastructure/persistence/repository/SpringDataTenantOutboxRepository.java`, `infrastructure/messaging/outbox/TenantOutboxRelayScheduler.java`, `src/main/resources/db/migration/` (migración nueva) | P0 5.3, P0 5.8, P1 6.8, P1 7.21 |
| `erp-auth-service` | `domain/port/UnitOfWork.java` (nuevo), `infrastructure/config/UnitOfWorkAdapter.java` (nuevo), `application/service/RefreshTokenService.java`, `application/service/PasswordResetService.java`, `application/service/AuthenticateUserService.java`, `infrastructure/persistence/adapter/UserPersistenceAdapter.java`, `infrastructure/persistence/adapter/RefreshTokenPersistenceAdapter.java`, `infrastructure/persistence/adapter/PasswordResetTokenPersistenceAdapter.java` | P0 5.8 |
| `erp-integration-hub` | `domain/port/UnitOfWork.java` (nuevo), `infrastructure/config/UnitOfWorkAdapter.java` (nuevo), `application/service/PaymentIntegrationService.java`, `application/service/ElectronicInvoicingService.java`, `application/service/RetrySchedulerService.java`, `application/service/WebhookProcessingService.java`, `infrastructure/persistence/adapter/IdempotencyPersistenceAdapter.java`, `infrastructure/persistence/adapter/IntegrationAttemptPersistenceAdapter.java`, `infrastructure/persistence/adapter/RetryQueuePersistenceAdapter.java` | P0 5.8, P1 6.2 |

**Contratos y tablas**

- `tenant_outbox` incorpora `event_id`, `attempts`, `next_attempt_at` y `last_error`, y un estado que distingue pendiente, publicado y en cola de muertos. **BREAKING** para cualquier lector directo de la tabla; el contrato con los consumidores no cambia, porque el formato del payload se mantiene.
- Los ports de repositorio de los tres servicios pasan a esperar que el llamante haya abierto la transacción: los adapters dejan de ser `@Transactional` por sí mismos. **BREAKING** interno para cualquier código que invoque un repositorio fuera de un caso de uso, que es exactamente lo que esta corrección elimina.
- Nuevo port `UnitOfWork` en `domain/port/` de los tres servicios, con implementación en `infrastructure/`.

**Dependencias y configuración** — sin dependencias de librerías nuevas: `TransactionTemplate` y `DataSourceTransactionManager` pertenecen a `spring-tx` y `spring-jdbc`, ya presentes. El comportamiento del relay se configura con las propiedades existentes `erp.tenant.outbox.interval-ms` y `erp.tenant.outbox.batch-size`, más las nuevas de máximo de intentos y espera máxima.

**Documentación** — `docs/doc-estado-implementacion.md` se actualiza al archivar para reflejar qué hallazgos quedan cerrados. `docs/doc-design-patterns.md` §6 (patrón Outbox) y `docs/doc-microservices.md` §4 (comunicación y consistencia) describen el comportamiento deseado y no requieren cambio de texto: este change los lleva a la práctica.

**Fuera de alcance** — el **transporte** de la publicación (Kafka) no se introduce aquí: el `EventPublisherPort` mantiene su implementación actual y el change de publicación de eventos de dominio lo sustituye. Lo que este change garantiza es que, cuando el transporte se conecte, la fila del outbox ya es correcta, está reclamada y es reintentable. Tampoco se implementa aquí la **semántica de idempotencia** del hub —reclamar la clave antes de llamar al proveedor, actualizarla después y no cachear los fallos— que pertenece al change de integración con proveedores y que **depende** de la demarcación transaccional que este change introduce.
