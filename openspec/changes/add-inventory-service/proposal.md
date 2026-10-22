# Proposal

## Why

`erp-inventory-service` **existe como scaffold** (78 ficheros, Clean Architecture completa, dominio con valor, API REST, migración con la Row-Level Security declarada) pero **no cumple su diseño ni es seguro**. El reporte de estado lo tenía por no construido, y la razón era doble: **el costeo FIFO por lotes no está implementado** —que es el requisito central del servicio (§7)—, y **el servicio está abierto a cualquiera**.

**El aislamiento por taller es decorativo.** El comodín `/api/v1/**` está en `permitAll` (`SecurityConfig.java:23-26`): sin token, cualquiera registra sucursales, crea repuestos, registra movimientos de stock, **reserva** stock y consulta saldos de **cualquier** taller. Y el taller se toma de la cabecera `X-Tenant-ID` (`BranchController.java:32`, `SparePartController.java:32`, `StockController.java:36`), que pone el cliente: **no hay filtro de JWT en el servicio**, y la URL del conjunto de claves está configurada y no se usa (`erp.auth.jwks-uri`, además con una ruta inexistente). Es el mismo defecto que `fix-tenant-isolation` cerró en el tenant-service (P0 5.1, P0 5.2, P0 5.4) y que `add-fleet-service` acaba de cerrar en el servicio de fleet.

**La Row-Level Security no aísla nada.** La migración declara `ENABLE ROW LEVEL SECURITY` **sin `FORCE`** (`V1__init_inventory_schema.sql:9`), y la aplicación conecta con `inventory_user`, que es el **propietario** de las tablas: en PostgreSQL el propietario elude su propia política. Además la política lee el contexto con el flag `missing_ok` (`current_setting('app.tenant_id', TRUE)`), de modo que sin contexto la comparación resuelve a `NULL` y la consulta se ejecuta **sin filtrar y sin avisar**.

**La saga de stock está a medias.** El diseño exige `Reserve/Commit/Release` **idempotentes** (`doc-microservices.md` §3.5). El servicio implementa `reserve` y `release`, pero **no existe `commit`**: una reserva confirmada por la orden de trabajo nunca se convierte en una salida real, y el stock queda **reservado para siempre**. Y la idempotencia es un `check-then-act` sobre `findByIdempotencyKey`: dos peticiones concurrentes con la misma clave **pasan ambas** la comprobación y registran dos movimientos —el mismo defecto que `fix-provider-integration` cerró en el hub (P1 6.3)—. La comprobación de saldo tiene la misma carrera: dos reservas simultáneas pueden **sobre-reservar** stock que no existe.

**El costeo FIFO por lotes no existe.** El saldo es la suma aritmética de los movimientos (`StockOperationService.getBalance`), sin lotes ni costes: el valor del stock consumido es **desconocido**, y `spare_parts` ni siquiera tiene columna de coste. Es el requisito que §7 y el reporte de estado marcan como *"costeo FIFO estricto por lotes, requisito central aún sin tocar"*.

**Y las alertas y los eventos no existen.** `spare_parts.min_stock` está ahí, pero **nadie lo comprueba** (no hay `StockLow`), y no hay bandeja de salida: el diseño pide emitir `StockLow` y `StockReserved` por outbox, y sin eventos el servicio de analítica y el de taller no tienen de qué quedarse.

**Además, el servicio no es alcanzable.** Publica sus rutas en `/api/v1/branches`, `/api/v1/spare-parts` y `/api/v1/stock/**`, pero el gateway reenvía `/api/v1/inventory/**` **sin quitar el prefijo**: toda petición por el gateway terminaría en 404. Y el propio servicio escucha en el **8084**, que es el puerto que el gateway tiene asignado a `work-order-service`, mientras que la ruta de inventory del gateway apunta al **8085**, que es el puerto del hub: hoy, `/api/v1/inventory` en el gateway **no alcanza al inventario**.

## What Changes

- **La API exige un token verificado y cada endpoint declara su rol** — el comodín `/api/v1/**` sale de `permitAll`, se valida el JWT contra el JWKS del emisor (`/.well-known/jwks.json`, con `https` salvo `dev`/`local`), y la escritura de stock exige `SUPERADMIN`, `ADMIN_TALLER` o `ASESOR`; la lectura admite también `MECANICO`. Cierra P0 5.4 y P1 6.19 en este servicio. El CORS deja de admitir cualquier origen.
- **El taller se deriva del token verificado** — la cabecera `X-Tenant-ID` pasa a ser una **pista** que se contrasta con el token, y la petición se rechaza si no coinciden. **BREAKING** para quien hoy envía la cabecera sin token válido, que es exactamente lo que debe pasar (P0 5.1, P0 5.2).
- **El aislamiento en base de datos pasa a ser efectivo** — la RLS se **habilita y se fuerza**, la aplicación conecta con un rol de ejecución que **no** es propietario de las tablas, el contexto de taller se establece con `SET LOCAL` **dentro** de la transacción y con el orden de advice declarado, y sin contexto la operación **se rechaza** en lugar de ejecutarse sin filtrar. Cierra P1 6.6 y P1 6.14 en este servicio.
- **`baseline-on-migrate` pasa a `false`** — el servicio es dueño de su esquema: sobre una base ya existente, Flyway dejaba de aplicar las migraciones, con lo que la política de RLS no llegaba a crearse.
- **La demarcación transaccional se replica** — los adapters de persistencia **dejan** de declarar demarcación propia, la demarcación pasa al **puerto `UnitOfWork`** del dominio implementado con `TransactionTemplate`, y se añade el test de arquitectura que falla si un adapter reintroduce la anotación. Cierra P1 6.2 en este servicio.
- **La idempotencia de la saga se reclama en la base de datos** — la clave de idempotencia se **reclama antes** de registrar el movimiento, mediante una inserción que la unicidad arbitra, de modo que dos peticiones concurrentes con la misma clave **no puedan registrar dos movimientos**; el resultado se escribe por la clave de negocio, sobre la fila ya reclamada. El mismo patrón que `fix-provider-integration` aplicó al hub (P1 6.3).
- **La comprobación de saldo deja de ser una carrera** — la reserva se calcula y se reclama en la **misma transacción**, de modo que dos reservas simultáneas no pueden sobre-reservar.
- **`commit` completa la saga** — se añade la operación `CommitStock`, idempotente, que convierte una reserva en una salida real. Sin ella, el stock reservado nunca se consume. **BREAKING** para el contrato de la saga: `erp-work-order-service` tendrá que llamarla.
- **El saldo se calcula por lotes, en orden FIFO** — cada entrada genera un **lote** con su coste unitario y una fecha; las salidas y las reservas consumen los lotes **más antiguos primero**, y el saldo informa del coste restante. Es el requisito central de §7. Supone **añadir el modelo de costes** al modelo documentado: `spare_parts.unit_cost`, `stock_lots` y `stock_movements.unit_cost`, y actualizar `docs/doc-database-diagrams.md` §5.
- **Los eventos de dominio se publican por bandeja de salida** — `StockReserved`, `StockReleased` y `StockLow` se persisten **junto al cambio de stock** en la misma transacción y los publica un relay, con reintentos y cola de muertos. El transporte sigue siendo el `EventPublisherPort`: **Kafka todavía no existe** en la plataforma, y el patrón de la bandeja ya está resuelto por `fix-transactional-outbox`.
- **La alerta de stock bajo se emite** — cuando el disponible de un repuesto cae por debajo de `min_stock`, se emite `StockLow` con la sucursal y el nivel.
- **Las rutas se publican bajo el prefijo que el gateway reenvía** — `/api/v1/inventory/branches`, `/api/v1/inventory/spare-parts` y `/api/v1/inventory/stock/**`. **BREAKING**: hoy no son alcanzables desde el gateway.
- **Se corrige la ruta del gateway** — la ruta `inventory-service` pasa a apuntar al **8084**, que es donde escucha el servicio, y `work-order-service` pasa al **8088**, un puerto libre para un servicio que todavía no existe. El hub conserva el 8085: es un servicio interno, lo llaman los servicios entre sí, y el gateway no lo enruta.
- **Los secretos salen de la configuración versionada** — `inventory_secret` desaparece del `application.yml` y del `compose.yaml`, y la contraseña del rol de propietario llega por variable de entorno. Cierra la acción 42 del reporte.
- **Los tests corren contra PostgreSQL real** — el perfil de test deja H2 con `create-drop` y Flyway desactivado (con lo que la RLS, los índices y las migraciones **nunca se ejecutaban**) y pasa a PostgreSQL embebido con las migraciones aplicadas y `ddl-auto: validate`, con la suite de aislamiento entre talleres, la de idempotencia concurrente y la de costeo FIFO. Cierra las acciones 38 y 39.
- **Observabilidad y entrega** — logging estructurado con el mismo esquema de eventos de seguridad que el resto de la plataforma, actuator con `/health` y `/ready` que comprueban de verdad PostgreSQL y Redis, JaCoCo con umbral del 80 % en `domain/`, `Dockerfile` y `compose.yaml` con la base creada por el rol de propietario y Redis disponible.
- **ADR-004** — registra la decisión del **costeo FIFO por lotes** y la del **modelo de costes**, y por qué el saldo no puede ser la suma de los movimientos: sin coste, el taller no sabe cuánto vale su stock ni cuánto le cuesta lo que consume.

**No hay cambios `BREAKING` fuera del servicio**, salvo la ruta del gateway hacia inventory, que hoy apunta al puerto equivocado.

## Capabilities

### New Capabilities

- `inventory`: Catálogo de repuestos, stock por sucursal y **operaciones de reserva, confirmación y liberación idempotentes** con un saldo calculado **por lotes en orden FIFO**, con aislamiento estricto por taller derivado del token verificado, publicación de los eventos de stock por bandeja de salida y aviso de stock bajo.

### Modified Capabilities

Ninguna. `openspec/specs/` sigue sin specs principales: este es el séptimo change del workspace y todos sus requisitos son nuevos.

## Impact

**Microservicio `erp-inventory-service` (existente, se completa y se asegura)**

| Contenido | Ficheros principales | Trazabilidad |
|:---|:---|:---|
| Autenticación, roles y contexto de taller | `infrastructure/config/SecurityConfig.java`, nuevo filtro de JWT y `TenantContextHolder`/`TenantContextScope`, `infrastructure/persistence/rls/PostgresTenantRlsAspect.java` | P0 5.1, 5.2, 5.4; P1 6.6, 6.14, 6.19, 6.53 |
| Esquema, RLS efectiva y roles | `db/migration/V1__init_inventory_schema.sql`, nueva `V2__rls_y_roles_inventory.sql`, nueva `V3__lots_y_coste.sql`, `application.yml` | P1 6.6; §7 (costeo FIFO) |
| Demarcación transaccional | `domain/port/UnitOfWork.java`, `infrastructure/config/UnitOfWorkAdapter.java`, `infrastructure/persistence/adapter/*`, `application/service/*` | P1 6.2 |
| Idempotencia y saga | `domain/model/StockMovement.java`, `domain/port/StockMovementRepositoryPort.java`, `application/service/StockOperationService.java`, `application/usecase/StockOperationUseCase.java` | §3.5 (`Reserve/Commit/Release`); P1 6.3 |
| Costeo FIFO | `domain/model/StockLot.java`, `domain/model/StockMovement.java`, `domain/port/StockLotRepositoryPort.java`, `application/service/StockOperationService.java` | §7, Sprint 4+ |
| Eventos y bandeja de salida | `domain/event/*`, `domain/port/EventPublisherPort.java`, `infrastructure/messaging/outbox/*` | §3.5; P1 6.8 |
| API y contrato de errores | `interfaces/rest/controller/*`, `interfaces/rest/advice/GlobalRestExceptionHandler.java` | convención de API del proyecto |
| Pruebas | `src/test/resources/application.yml`, `src/test/java/com/erp/inventory/**` | acciones 38 y 39 |
| Delivery y secretos | `pom.xml`, `application.yml`, `application-prod.yml`, `Dockerfile`, `compose.yaml`, `.env.example` | acciones 41 y 42 |

**Microservicios existentes**

| Servicio | Cambio | Trazabilidad |
|:---|:---|:---|
| `erp-api-gateway` | La ruta `inventory-service` apunta al **8084** y `work-order-service` al **8088** | §2 del reporte de estado ("Ruta del gateway apunta a `:8085`") |
| `erp-integration-hub` | El hub ya expone los roles por servicio; el inventario no necesita llamarlo, y su rol `ROLE_FLEET` no aplica | — |

**APIs** (todas bajo `/api/v1/inventory`, con `tenant_id` derivado del token)

- `POST/GET /api/v1/inventory/branches`, `GET /api/v1/inventory/branches/{id}` — **BREAKING**: las rutas cambian de prefijo.
- `POST/GET /api/v1/inventory/spare-parts`, `GET /api/v1/inventory/spare-parts/{id}` — **BREAKING**.
- `POST /api/v1/inventory/stock/movements` — entrada (`IN`) o salida (`OUT`) manual, con su coste unitario. **BREAKING**.
- `POST /api/v1/inventory/stock/reserve` — reserva idempotente para una orden de trabajo. **BREAKING**.
- `POST /api/v1/inventory/stock/commit` — **nuevo**: convierte la reserva en una salida consumiendo los lotes por orden FIFO. Es la tercera pata de la saga que el diseño exige.
- `POST /api/v1/inventory/stock/release` — libera la reserva (flujo compensatorio de la saga). **BREAKING**.
- `GET /api/v1/inventory/stock/balance/{partId}/branch/{branchId}` — saldo: entradas, salidas, reservado, disponible y **valor del stock por lotes**. **BREAKING**: la respuesta incorpora el coste.

**Documentación**

- `docs/doc-estado-implementacion.md`: la fila 7 de §2 deja de decir "⚫ No existe", y el Sprint 4+ recoge el servicio construido y el requisito del costeo ya cubierto.
- `docs/doc-microservices.md` §3.5: se actualiza con lo implementado.
- `docs/doc-database-diagrams.md` §5: se actualiza con las tablas de lotes y las columnas de coste.
- `erp-inventory-service/doc/arquitectura-y-conexiones.md`: se corrigen la matriz de endpoints, la saga y el aislamiento.
- `docs/adr/ADR-004-*.md` y `docs/adr/README.md`: nuevo ADR del costeo FIFO y del modelo de costes, con la copia canónica en el repositorio `erp-inventory-service`.
