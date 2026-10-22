# Design

## Context

Estado actual, verificado sobre el código clonado (`erp-inventory-service`, rama `develop`, 78 ficheros). La motivación está en `proposal.md` — Why.

**Lo que está construido y se conserva**

- Clean Architecture de cuatro capas con los nombres de paquete de la plataforma (`com.erp.inventory.{domain,application,infrastructure,interfaces}`), dominio sin framework, value objects con validación (`TenantId`, `BranchId`, `SparePartId`, `StockMovementId`, `IdempotencyKey`, `Quantity`, `Sku`), puertos de dominio, casos de uso como interfaces en `application/usecase`, mappers separados de los adapters, `ddl-auto: validate`, Flyway como dueño del esquema.
- El modelo de negocio: sucursales, catálogo de repuestos con umbral de stock bajo, movimientos de tipo `IN`/`OUT`/`RESERVE`/`RELEASE`, saldo con entradas, salidas, reservado y disponible, y **reserva y liberación idempotentes por clave**.
- El `pom.xml` **compila** y no arrastra dependencias inexistentes.

**Lo que está mal y este change corrige**

| # | Hecho verificado | Dónde |
|:---|:---|:---|
| 1 | `/api/v1/**` en `permitAll`; el taller de la cabecera `X-Tenant-ID`; **ningún filtro de JWT** | `SecurityConfig.java:23-26`, los tres controladores |
| 2 | RLS con `ENABLE` **sin `FORCE`**, con la aplicación conectando como propietario y el contexto leído con `missing_ok` | `V1__init_inventory_schema.sql:9,11` |
| 3 | `baseline-on-migrate: true`; `inventory_secret` en el `application.yml` versionado y en el `compose.yaml` | `application.yml`, `compose.yaml` |
| 4 | Los adapters y los servicios declaran su **propia** demarcación; no hay puerto `UnitOfWork` | `application/service/*`, `infrastructure/persistence/adapter/*` |
| 5 | Perfil de test en **H2** con `create-drop` y Flyway desactivado; **un solo test** | `src/test/resources/application.yml` |
| 6 | Sin `commit`: la reserva nunca se consume | `MovementType` (IN/OUT/RESERVE/RELEASE) |
| 7 | La idempotencia y el saldo son **check-then-act**: dos peticiones concurrentes con la misma clave registran dos movimientos, y dos reservas simultáneas pueden sobre-reservar | `StockOperationService.reserve` y `getBalance` |
| 8 | Sin lotes ni costes: el saldo es la suma de los movimientos | `StockOperationService.getBalance` |
| 9 | Sin bandeja de salida, sin `StockReserved`/`StockLow`, y `min_stock` no lo comprueba nadie | — |
| 10 | Sin actuator, Redis, JaCoCo, `resilience4j` ni `openspec` | `pom.xml` |
| 11 | Las rutas no están bajo el prefijo que el gateway reenvía, y el servicio escucha en el 8084, que es el puerto de `work-order` | `interfaces/rest/controller/*`, `application.yml` |

**Restricciones del entorno**: no hay Docker, de modo que los tests de integración corren contra **PostgreSQL embebido real**, como en los otros cuatro servicios. **Kafka todavía no existe** en la plataforma: el transporte de eventos sigue siendo el `EventPublisherPort`, con el patrón de la bandeja ya resuelto por `fix-transactional-outbox`.

**Decisiones ya tomadas por el equipo**: el inventario se queda en el **8084** y se corrige la ruta del gateway (inventory → 8084, work-order → 8088); el **costeo FIFO por lotes entra en este change**, con su modelo de costes.

## Goals / Non-Goals

**Goals:**

- Que el servicio sea **alcanzable y seguro**: token verificado, roles por endpoint, taller derivado del token, y aislamiento por taller **efectivo** en la base de datos.
- Que la **saga esté completa**: `Reserve`/`Commit`/`Release` idempotentes, con la reserva reclamada en la base de datos y sin sobre-reservar.
- Que el **saldo sea un saldo con coste**: lotes, consumo FIFO y valor del stock, que es el requisito central de §7.
- Que el stock **avise**: eventos por bandeja de salida y alerta de stock bajo.
- Cerrar los patrones que los otros cuatro servicios ya tienen cerrados, **sin reescribir** el scaffold que funciona.

**Non-Goals:**

- **No** se implementa `erp-work-order-service`: el inventario expone la reserva y la confirmación; quien las orquesta es la orden de trabajo, que todavía no existe.
- **No** se conecta a Kafka: el transporte sigue siendo el `EventPublisherPort` y el `KafkaTemplate` llega con el change que introduzca el bus.
- **No** se toca el hub: el inventario no valida RUC ni placas, así que no necesita el rol `ROLE_FLEET` ni clave de servicio propia.
- **No** se rehace el dominio: las sucursales, los repuestos y los movimientos se conservan tal cual, y el costeo se **añade** al modelo.
- **No** se migra el gateway a gRPC ni se le añade una segunda fuente de datos.

## Decisions

### D1. El saldo se calcula con lotes, y no con un saldo acumulado

**Decisión.** Cada entrada de stock abre un **lote**: cantidad, coste unitario, fecha de entrada y cantidad reservada. El disponible de un repuesto en una sucursal es la suma del disponible de sus lotes; el **valor** del stock es la suma de `disponible × coste_unitario` de los lotes que quedan. Las salidas y las reservas **consumen los lotes más antiguos primero** (FIFO).

**Alternativa descartada.** (a) **Un campo `stock` acumulado** en la tabla de repuestos, que es lo que hacen la mayoría de los almacenes simples. La razón: con un saldo acumulado se sabe **cuántas** unidades hay, pero **no cuánto valen**: el coste de lo que se consumió se pierde en el momento en que se consume, y el taller no puede saber el valor de su stock ni el coste de lo que ha vendido. El requisito de §7 es "costeo FIFO estricto por lotes", no "cantidad disponible". (b) **Un saldo con el coste medio ponderado** (el método del promedio ponderado): mantiene un único valor unitario, pero **mezcla** el coste de unas compras antiguas con el de unas recientes, de modo que el valor del stock deja de corresponder a los lotes que realmente quedan. En un taller con repuestos de comprazpaced y de compra antigua, la diferencia es real.

**Consecuencia.** El modelo documentado **crece**: `spare_parts.unit_cost` (el coste de referencia), `stock_lots` (id, taller, repuesto, sucursal, cantidad, cantidad reservada, coste unitario, fecha de entrada) y `stock_movements.unit_cost` (el coste real con el que se movió esa unidad). `docs/doc-database-diagrams.md` §5 se actualiza.

**Por qué FIFO y no LIFO.** LIFO dejaría los lotes antiguos sin tocar y congelaría su coste en el inventario, que es justo lo que el taller no quiere: el repuesto que lleva dos años en el estante es el que hay que mover, y su precio de compra antiguo es el que interesa conocer. FIFO consume primero lo que primero entró, de modo que el stock que queda refleja las compras más recientes.

### D2. La reserva se consume de los lotes más antiguos, y la reserva se marca sobre el lote

**Decisión.** La reserva no guarda "un lote" en abstracto: **marca la cantidad reservada sobre los lotes que consume**, empezando por el más antiguo con disponible. La confirmación consume lo reservado, y la liberación lo devuelve.

**Alternativa descartada.** Guardar la reserva en una tabla aparte (`reservations` con `spare_part_id`, `branch_id`, `quantity`, `status`), sin tocar los lotes. La razón: para valuing el stock hay que saber **qué lotes** están reservados, y si la reserva vive aparte hay que reconstruirlo por comparación de fechas, con ambigüedad cuando dos reservas se cruzan. Marcando la cantidad reservada sobre el lote, el valor del stock reservado sale de la misma fila.

**Consecuencia.** Un lote tiene `reserved_quantity`: su disponible es `quantity - reserved_quantity`, y su valor reservado es `reserved_quantity × unit_cost`.

### D3. La clave de idempotencia se reclama en la base de datos, antes de registrar el movimiento

**Decisión.** La reserva **reclama** la clave con una inserción que la unicidad `(tenant_id, idempotency_key)` arbitra. Dos peticiones concurrentes con la misma clave no pueden registrar dos movimientos: una gana el reclamo y la otra recibe el resultado ya registrado. La clave **se libera** si la operación falla de forma transitoria, y **no se libera** cuando el movimiento queda registrado: un fallo no puede cachearse como resultado definitivo.

**Alternativa descartada.** El `check-then-act` que hay hoy —consultar si la clave existe y, si no, registrar el movimiento—. La razón: entre la consulta y la escritura hay una ventana en la que otra petición con la misma clave pasa la comprobación. Dos reservas simultáneas registran **dos** movimientos, y la segunda reserva no ve el descuento de la primera: el stock se sobre-reserva. Es el mismo defecto que `fix-provider-integration` cerró en el hub (P1 6.3).

**Consecuencia.** El reclamo es una escritura más dentro de la transacción de la reserva, de modo que **la clave y el movimiento se confirman juntos**: si la transacción falla, no queda una clave reclamada que bloquee el reintento.

### D4. El saldo y la reserva se calculan en la misma transacción, con los lotes bloqueados

**Decisión.** La reserva **bloquea** los lotes que va a consumir (`SELECT ... FOR UPDATE`, en el orden FIFO) y calcula el disponible con esos lotes ya bloqueados. Con los lotes bloqueados, la segunda reserva concurrente **espera** y, cuando obtiene el bloqueo, ve el stock ya descontado.

**Alternativa descartada.** Calcular el saldo y luego insertar, sin bloquear. La razón: es la carrera que permite sobre-reservar. Bloquear los lotes afectados es lo que convierte la comprobación en una garantía, y no hay alternativa con el mismo efecto: un bloqueo de tabla bloquearía a todos los talleres (el `tenant_id` está en la misma tabla).

**Consecuencia.** El bloqueo es **por lote y por orden FIFO**, de modo que las reservas concurrentes de un mismo repuesto se serializan sin bloquear las de otros repuestos. El tiempo de espera de la transacción es el que se canaliza a la **reserva idempotente** de la saga, que ya tiene su propio plazo.

### D5. `CommitStock` completa la saga, y es idempotente por la misma clave

**Decisión.** Se añade `CommitStock`: consume la reserva y la convierte en una salida real, con su propia clave de idempotencia. Repetirla con la misma clave devuelve el resultado de la primera y no consume dos veces.

**Alternativa descartada.** (a) **No tener `commit`** y consumir la reserva con el `OUT` de la orden de trabajo: la reserva y el consumo quedarían en dos operaciones distintas, y entre ellas el stock está reservado sin estar consumido, lo que rompe el balance del taller y deja la reserva viva para siempre. (b) **Que el inventario confirme solo**, pasado un tiempo: la orden de trabajo no sabe si la reserva se confirmó, y un temporizador en el inventario es una segunda fuente de verdad sobre el estado de la orden.

**Consecuencia.** El contrato de la saga queda `ReserveStock` / `CommitStock` / `ReleaseStock`, tal como lo exige `doc-microservices.md` §3.5. `erp-work-order-service` tendrá que llamar a `CommitStock` cuando la orden se approve: es un cambio **BREAKING** para el contrato de la saga, y por eso el ADR-004 lo deja escrito.

### D6. Los eventos de stock se publican por bandeja de salida, con el transporte que hay

**Decisión.** `StockReserved`, `StockReleased` y `StockLow` se persisten en la bandeja **junto al cambio de stock**, en la misma transacción, y los publica un relay con reintentos y cola de muertos. El transporte es el `EventPublisherPort`, que hoy tiene una implementación de registro.

**Alternativa descartada.** (a) **Publicar los eventos directamente desde el caso de uso**, sin bandeja: el bus puede recibir un evento que la base de datos no llegó a confirmar, y con un fallo del bus se pierde el evento sin que el stock se revierta. Es exactamente el defecto que `fix-transactional-outbox` cerró en el tenant-service y el hub. (b) **Esperar a Kafka para hacerlo bien**: Kafka no existe en la plataforma, y la bandeja no depende del transporte: se construye con el mismo patrón ya resuelto y el cambio a `KafkaTemplate` es otro change.

**Consecuencia.** El `EventPublisherPort` y el relay se replican del tenant-service, con la diferencia de que aquí los eventos son de **stock** y no de **taller**.

### D7. La alerta de stock bajo se compara en el mismo cambio que deja el stock bajo

**Decisión.** Tras cada movimiento que reduzca el disponible, el servicio compara el disponible con `min_stock` del repuesto y, si lo rebasa, emite `StockLow` con el repuesto, la sucursal y el nivel.

**Alternativa descartada.** (a) **Una tarea programada que recorra el catálogo** buscando los repuestos por debajo del umbral: con miles de repuestos yWorkshop, el barrido es caro, y el aviso llega tarde: el repuesto ya se agotó. (b) **Que el propio cliente consulte el umbral**: el aviso deja de ser un evento del dominio y pasa a ser una comprobación de la interfaz, y el taller no se entera si nadie mira.

**Consecuencia.** `min_stock` deja de ser un campo decorativo, que es lo que es hoy.

### D8. El taller se deriva del token verificado, y el `X-Tenant-ID` es una pista

**Decisión.** El servicio valida el JWT contra el JWKS del emisor (`/.well-known/jwks.json`, con `https` fuera de `dev`/`local`), toma el `tenant_id` del token verificado y **contrasta** la cabecera `X-Tenant-ID` si viene: si no coinciden, rechaza la petición.

**Alternativa descartada.** Seguir leyendo el taller de la cabecera. La razón: la cabecera la pone el cliente, y el gateway la inyecta, pero **nadie verifica que quien llama sea el gateway**. Con la cabecera como autoridad, cualquiera que alcance el servicio elige el taller y lee o escribe su stock. Es el defecto que `fix-tenant-isolation` cerró en el tenant-service (P0 5.1, P0 5.2).

**Consecuencia.** El servicio deja de confiar en la red interna: valida el token como los demás.

### D9. La Row-Level Security se hace efectiva, y el contexto se establece dentro de la transacción

**Decisión.** Se replica el patrón de `fix-tenant-isolation`: RLS con `ENABLE` **y** `FORCE`, un rol de ejecución que no es propietario de las tablas, contexto de taller con `SET LOCAL` **dentro** de la transacción y con el **orden de advice declarado** (el interceptor transaccional en el orden 0 y el aspecto de RLS en el 1), y **fail-closed**: sin contexto, la operación se rechaza.

**Alternativa descartada.** Confiar en el filtrado de cada consulta, como hacen hoy los repositorios de Spring Data. La razón: es un filtro que depende de que cada consulta lo recuerde, y con `ENABLE` sin `FORCE` la política **no aporta nada**: el propietario la elude.

**Consecuencia.** El SQL de los repositorios puede seguir filtrando por `tenant_id` —como segunda barrera—, pero la garantía la da la política.

### D10. La demarcación transaccional se replica con el puerto `UnitOfWork`

**Decisión.** `domain/port/UnitOfWork.java`, implementado en infraestructura con `TransactionTemplate`; los casos de uso dependen del puerto y **no** de anotaciones de Spring; los adapters **dejan** de declarar demarcación propia y comprueban al entrar que hay una transacción activa.

**Alternativa descartada.** Dejar `@Transactional` en los adapters y en los servicios. La razón: cada llamada a un repositorio abría su propia transacción, de modo que una operación con dos escrituras —una reserva y su movimiento— no podía ser atómica, que es justo lo que la reserva necesita.

**Consecuencia.** El test de arquitectura que falla si un adapter reintroduce la anotación es el mismo que ya tienen el tenant-service y el hub.

### D11. La API se publica bajo `/api/v1/inventory`, y el gateway se corrige

**Decisión.** Las rutas del servicio pasan a `/api/v1/inventory/branches`, `/api/v1/inventory/spare-parts` y `/api/v1/inventory/stock/**`, y la ruta `inventory-service` del gateway pasa a apuntar al **8084**, donde escucha el servicio. `work-order-service` pasa al **8088**, un puerto libre para un servicio que todavía no existe.

**Alternativa descartada.** (a) **Que el gateway quite el prefijo**: rompe la convención de la plataforma, donde el prefijo del gateway *es* la ruta del servicio, y dejaría las rutas del inventario sin espacio de nombres frente a las de los demás servicios. (b) **Mover el inventario al 8085**, que es lo que espera el gateway, y el hub al 8088: el hub ya está desplegado y su puerto aparece en la configuración del fleet y en su documentación, mientras que el 8084 es el puerto del propio inventario, con su `compose.yaml` y su `Dockerfile`.

**Consecuencia.** Es un cambio **BREAKING** de la URL pública del servicio, y del puerto de la ruta del gateway. El hub conserva el 8085: es un servicio interno, lo llaman los servicios entre sí, y **el gateway no lo enruta**.

### D12. Los roles de la API: escribir stock es de taller, leerlo también

**Decisión.** La escritura de stock (movimientos, reservas, confirmaciones y liberaciones) exige `SUPERADMIN`, `ADMIN_TALLER` o `ASESOR`; la lectura admite también `MECANICO`. El catálogo y las sucursales siguen la misma regla.

**Alternativa descartada.** Declarar en el **borde** el rol que exige la ruta. La razón: el prefijo `/api/v1/inventory` mezcla lecturas y escrituras, y el rol depende de la operación: `MECANICO` puede consultar el stock pero no reservarlo. Una sola regla en el borde sería o demasiado permisiva o demasiado restrictiva.

**Consecuencia.** El filtro fino lo aplica el servicio de destino, que es quien distingue las operaciones. La matriz de roles y endpoints se genera desde el código.

### D13. Los tests corren contra PostgreSQL real, con las reglas de concurrencia verificadas

**Decisión.** El perfil de test usa **PostgreSQL embebido** con las migraciones aplicadas y `ddl-auto: validate`, como en los otros cuatro servicios. Las reglas que hay que demostrar —la idempotencia concurrentemente, el no sobre-reservar y el consumo FIFO— se prueban con **hilos de verdad**, porque una prueba secuencial no las demuestra.

**Alternativa descartada.** (a) **Testcontainers**: el entorno **no tiene Docker**, de modo que la suite no podría ejecutarse. (b) **H2 con `create-drop`**, que es lo que hay hoy: el esquema lo derivaría de las entidades, la RLS no se ejecutaría nunca y la concurrencia se probaría sobre un motor que no es el de producción.

**Consecuencia.** La suite es más lenta, pero es la única que demuestra que dos reservas simultáneas no pueden sobre-reservar.

### D14. Se conserva el scaffold; no se rehace

**Decisión.** El change **no** reescribe el dominio ni las rutas existentes: los cambios se concentran en lo que está mal —seguridad, aislamiento, demarcación, idempotencia, saga, costeo, eventos y delivery— y en lo que falta.

**Alternativa descartada.** Rehacer el servicio con el patrón de los otros cuatro. La razón: el scaffold es correcto en su forma, y un diff que mezcla "corregir lo que está mal" con "cambiar lo que está bien" es más difícil de revisar que dos changes separados. Además, el dominio de stock **no** es una copia del de fleet: tiene su propio vocabulario (`IN`/`OUT`/`RESERVE`/`RELEASE`, `Sku`, `IdempotencyKey`, `Quantity`) y conservarlo es parte de la coherencia del dominio.

**Consecuencia.** El diff es legible y cada commit responde a un defecto concreto.

### D15. El coste unitario lo introduce el taller, y el repuesto guarda el de referencia

**Decisión.** El repuesto tiene un `unit_cost` de referencia, que es el precio esperado. Cada **entrada** lleva el coste **real** con el que se compró ese lote, que puede diferir del de referencia. El saldo informa del coste real de los lotes que quedan, y el coste de referencia se usa para **avisar**: si el coste real de una entrada se aparta del de referencia en más de un umbral, se emite `StockCostDeviation`.

**Alternativa descartada.** (a) **Un único coste por repuesto**, tomado del catálogo, y todos los lotes a ese coste. La razón: el precio de compra cambia, y con un único coste el valor del stock miente justo cuando importa —una compra de emergencia cara o un lote viejo barato—. (b) **Pedir siempre el coste real** y quitar el de referencia: se pierde la capacidad de detectar que una compra se salió de rango.

**Consecuencia.** El modelo tiene dos costes con papeles distintos, y ambos se documentan.

## Risks / Trade-offs

- **[El modelo de lotes crece el esquema y la complejidad]** → es el precio del requisito de §7, y el modelo queda documentado en `docs/doc-database-diagrams.md` §5 y en el ADR-004. Un almacén que no valoriza el stock no necesita lotes, pero un taller que compra repuestos necesita saber cuánto vale lo que tiene.
- **[El bloqueo de lotes puede encolarser con reservas concurrentes del mismo repuesto]** → el bloqueo es por lote y en orden FIFO, así que las reservas de **otros** repuestos no se bloquean entre sí; y la espera tiene un plazo, tras el cual la reserva falla con un error explícito en lugar de colgar la petición.
- **[La saga queda con un contrato nuevo (`CommitStock`) que `erp-work-order-service` tendrá que conocer]** → es un cambio **BREAKING** del contrato de la saga, y por eso el ADR-004 lo deja escrito antes de construir el otro lado. Es preferible a la alternativa de que la reserva nunca se consuma.
- **[El aviso de stock bajo puede repetirse en movimientos consecutivos]** → el aviso lleva la clave del movimiento y el consumidor deduplica por identificador de evento, que es el patrón que ya usan el tenant-service y el hub. Además, el aviso se emite **por movimiento**, no por comparación de estado, de modo que dos reservas que dejan el stock bajo producen dos avisos: el segundo dice que sigue bajo.
- **[La bandeja de salida sin Kafka se acumula si el relay no encuentra destino]** → es el mismo riesgo que ya tienen el tenant-service y el hub, con la misma mitigación: la cola de muertos es consultable y reactivable.
- **[El `min_stock` se compara en cada movimiento y el repuesto puede cambiar de umbral]** → el aviso lleva el nivel en el momento del movimiento, de modo que un cambio de umbral no reescribe la historia.
- **[La cache y el circuit breaker no aplican aquí]** → el inventario no llama a ningún proveedor externo: el ACL es el hub y no participa. No hay ni `Redis` ni `resilience4j` en el `pom.xml` del inventario, y esa es la decisión correcta: no se añade una dependencia que no se usa.
- **[El costeo FIFO exige migrar el stock existente al cambiar el modelo]** → el servicio **no se ha desplegado con datos reales**: la tabla de movimientos está vacía, de modo que la migración de lotes no tiene que backfill. Es la ventana más barata para hacerlo.

## Migration Plan

1. **Rama de trabajo**: `feature/add-inventory-service` apilada sobre `develop`, con un commit por task y **ningún merge** hasta el cierre del change.
2. **Preparar el gateway** (sin inventario todavía): corregir la ruta `inventory-service` al 8084 y `work-order-service` al 8088, con un plazo de 500 ms en ambas.
3. **Esquema**: aplicar `V2__rls_y_roles_inventory.sql` (RLS forzada, roles, permisos) y `V3__lots_y_coste.sql` (tabla de lotes y costes); verificar `relforcerowsecurity` a `true` y `baseline-on-migrate: false`.
4. **Seguridad y aislamiento**: activar la validación del token, los roles por endpoint y el contexto de taller; comprobar que una petición sin token recibe 401 y que el taller de la cabecera no puede cambiar el del token.
5. **Saga y costeo**: publicar `CommitStock`, el consumo FIFO y la alerta de stock bajo; verificar con la suite concurrente que dos reservas simultáneas no sobre-reservan y que la clave de idempotencia arbitra.
6. **Eventos**: activar la bandeja de salida y el relay.
7. **Rollback**: el servicio no está desplegado con datos reales, de modo que revertir es volver a la rama anterior. La migración de lotes es **aditiva** y no rompe en caliente: la tabla nueva no la usa nadie hasta que la activar el consumo FIFO. La corrección del gateway es reversible por configuración.

## Open Questions

- **¿Quién llama a `CommitStock`?** La orden de trabajo, cuando la orden se aprueba; el inventario no lo llama solo. La decisión formal es de `erp-work-order-service`, cuyo change tendrá que declarar el contrato de la saga con las tres operaciones. No cambia esta spec ni este enfoque: el inventario expone las tres operaciones, y quién las orquesta es del otro lado.
- **¿El umbral de desviación de coste se configura por taller o es global?** Se ha asumido un valor global por defecto, configurable por entorno, porque el modelo de `spare_parts` no tiene una columna de umbral por taller. Si los talleres necesitan umbrales distintos, hace falta una tabla de preferencias: es una decisión de la siguiente iteración, y no cambia el comportamiento observable del saldo.
- **¿La valoración del stock reservado se informa por separado o solo el disponible?** La spec exige el valor del stock **disponible**; el valor reservado se puede informar en el mismo detalle. Se ha implementado el disponible, que es el que decide si hay que reponer, y el reservado es un dato derivado. Si el taller necesita el valor total incluyendo lo reservado, es un campo más de la respuesta, no un cambio de modelo.
