# Tasks

## 1. Andamiaje, decisión previa y configuración

- [x] 1.1 Crear la rama `feature/add-inventory-service` **apilada sobre `develop`**, sin merges, y verificar con `git log --merges develop..feature/add-inventory-service` que **no devuelve ninguna línea**
- [x] 1.2 Inicializar OpenSpec en el repositorio (`openspec init`) y copiar el change `add-inventory-service` con `proposal.md`, `design.md`, `tasks.md` y `specs/inventory/spec.md`; copiar el mismo change en `erp-api-gateway`, que también lo implementa; verificar en los dos con `openspec validate --all --strict`
- [x] 1.3 Redactar `docs/adr/ADR-004-costeo-fifo-por-lotes.md` en el repositorio, con la decisión del **costeo FIFO por lotes**, la del **modelo de costes** (`unit_cost` de referencia y coste real por entrada) y la del **contrato completo de la saga** (`ReserveStock`/`CommitStock`/`ReleaseStock`); verificar que el ADR cita §3.5 y §7 de `doc-microservices.md` y que deja escrito el cambio incompatible del contrato de la saga
- [x] 1.4 Copiar el ADR a `docs/adr/README.md` de la raíz del workspace, añadirlo al índice y marcar su copia canónica en el repositorio; verificar con `grep ADR-004 docs/adr/README.md` y con el diff entre ambas copias
- [x] 1.5 Añadir a `pom.xml` las dependencias que el change necesita y que faltan: `spring-boot-starter-actuator`, `jacoco-maven-plugin` y, en test, `io.zonky.test:embedded-postgres`; **no** añadir `data-redis` ni `resilience4j`: el inventario no llama a ningún proveedor externo y no los usa; verificar con `mvn -o -q compile` que el módulo compila
- [x] 1.6 Quitar de `src/main/resources/application.yml` la contraseña por defecto `inventory_secret` y dejarla como variable de entorno **sin valor por defecto**; corregir `erp.auth.jwks-uri` a la ruta real `/.well-known/jwks.json` y exigir `https` salvo en `dev`/`local`; verificar con `grep -n "inventory_secret\|oauth2/jwks" src/main/resources/application.yml` que no queda ninguno de los dos
- [x] 1.7 Poner `baseline-on-migrate: false` y `ddl-auto: validate` en `application.yml`, y crear `application-prod.yml` con lo mismo, los secretos por variable de entorno y el OpenAPI desactivado; verificar con un arranque contra una base limpia que las migraciones se aplican y que el esquema validado coincide con las entidades
- [x] 1.8 Revisar `.env.example`, `Dockerfile` y `compose.yaml` para que **no contengan secretos** y para que la base se cree con el rol de propietario y la aplicación use otro; verificar con `grep -rniE "secret|password" .env.example Dockerfile` que no hay valores de secreto

## 2. Esquema: aislamiento efectivo, roles y lotes

- [x] 2.1 Escribir `src/main/resources/db/migration/V2__rls_y_roles_inventory.sql` con `ENABLE` **y** `FORCE ROW LEVEL SECURITY` sobre `branches`, `spare_parts`, `stock_movements` y `stock_lots`, la política `USING (tenant_id = current_setting('app.tenant_id')::uuid)` **sin** el flag `missing_ok`, el rol de ejecución `erp_inventory_app` sin privilegios de propietario y sin `BYPASSRLS`, y los permisos correspondientes; verificar con una consulta a `pg_class` que `relrowsecurity` y `relforcerowsecurity` están a `true` en las cuatro tablas
- [x] 2.2 Escribir `src/main/resources/db/migration/V3__lots_y_coste.sql` con la tabla `stock_lots` (id, taller, repuesto, sucursal, cantidad, cantidad reservada, coste unitario, fecha de entrada), la columna `spare_parts.unit_cost` y la columna `stock_movements.unit_cost`, con el índice de consumo FIFO sobre `(tenant_id, spare_part_id, branch_id, entry_date)`; verificar que la migración se aplica y que `ddl-auto: validate` no detecta deriva
- [x] 2.3 Comprobar en la migración que **ninguna** tabla de negocio queda sin `tenant_id` ni con la política de aislamiento, y que el estado de validación de los movimientos no admite valores fuera del conjunto; verificar con la suite de integración que la migración se aplica y las comprobaciones pasan
- [x] 2.4 Actualizar `docs/doc-database-diagrams.md` §5 con la tabla de lotes y las columnas de coste, y `docs/doc-microservices.md` §3.5 con lo implementado; verificar con un diff que el DBML del doc y el esquema de las migraciones coinciden

## 3. Autenticación, roles y contexto de taller

- [x] 3.1 Implementar `infrastructure/security/JwksKeyManager.java` y `JwksUrlPolicy.java` copiando los del gateway y el hub, y extender `docs/verificar-equivalencia-jwks.py` para que la comprobación de equivalencia incluya la copia del inventario; verificar con `python3 docs/verificar-equivalencia-jwks.py` que las tres copias son equivalentes y que el comando **falla** cuando una deja de comportarse como las otras
- [x] 3.2 Implementar `infrastructure/security/JwtAuthenticationFilter.java`: valida el token contra el JWKS, comprueba la expiración, exige el claim `tenant_id` y **contrasta** la cabecera `X-Tenant-ID` con el del token, rechazando la petición cuando no coinciden; verificar con un test que un token sin firma verificable, expirado o sin taller se rechaza
- [x] 3.3 Implementar `infrastructure/persistence/rls/TenantContextHolder.java` e `interfaces/rest/support/TenantContextScope.java`, con el contexto establecido **solo** desde el principal autenticado y restaurado al terminar; verificar con un test que el contexto no sobrevive a la petición
- [x] 3.4 Implementar `infrastructure/persistence/rls/PostgresTenantRlsAspect.java` con el `SET LOCAL` **dentro** de la transacción y el orden de advice declarado (`infrastructure/config/PersistenceConfig.java`), y el **fail-closed**: sin contexto la operación se rechaza; verificar con un test de integración que la fila de otro taller no se ve y que la consulta sin contexto falla en lugar de devolver cero filas
- [x] 3.5 Corregir `infrastructure/config/SecurityConfig.java`: **`/api/v1/**` sale de `permitAll`**, la API exige token verificado y el CORS deja de admitir cualquier origen con cualquier cabecera; verificar con un test que una petición sin token a `POST /api/v1/inventory/stock/reserve` responde `401` y no registra ningún movimiento
- [x] 3.6 Declarar el rol en cada endpoint de `BranchController`, `SparePartController` y `StockController`: escritura para `SUPERADMIN`, `ADMIN_TALLER` y `ASESOR`, lectura también para `MECANICO`; verificar con tests que un `MECANICO` recibe `403` al reservar y `200` al consultar

## 4. Demarcación transaccional

- [x] 4.1 Declarar `domain/port/UnitOfWork.java` e implementarlo en `infrastructure/config/UnitOfWorkAdapter.java` sobre `TransactionTemplate`, con la propagación que **se une** a la transacción abierta; verificar con un test que una operación que escribe dos tablas se confirma en una sola transacción
- [x] 4.2 Migrar `application/service/BranchService.java`, `SparePartService.java` y `StockOperationService.java` al puerto `UnitOfWork`, **quitando** los `@Transactional` de Spring de la capa de aplicación; verificar con un test que la demarcación sigue siendo atómica
- [x] 4.3 Quitar el `@Transactional` de `infrastructure/persistence/adapter/{Branch,SparePart,StockMovement}PersistenceAdapter.java` y hacer que **comprueben al entrar** que hay una transacción activa, fallando de forma explícita si no la hay; verificar con un test que invocar un adapter fuera del límite transaccional produce un error explícito
- [x] 4.4 Portar el test de arquitectura `src/test/java/com/erp/inventory/architecture/TransactionDemarcationArchitectureTest.java` que **falla** si un adapter de persistencia reintroduce `@Transactional` no `readOnly`, y añadir la comprobación de que la capa de aplicación **no importa** la infraestructura; verificar que pasa con el código actual y **falla** al reintroducir la anotación

## 5. Idempotencia y concurrencia

- [x] 5.1 Implementar el **reclamo** de la clave de idempotencia en `infrastructure/persistence/adapter/StockMovementPersistenceAdapter.java`: una inserción que la unicidad `(tenant_id, idempotency_key)` arbitra, de modo que dos peticiones concurrentes con la misma clave no puedan registrar dos movimientos; verificar con un test de **hilos concurrentes** que se registra un solo movimiento y que las dos peticiones reciben el mismo resultado
- [x] 5.2 Hacer que la clave se **libere** cuando la operación falla de forma transitoria y **no** se libere cuando el movimiento queda registrado, de modo que un fallo no se cachee como resultado definitivo; verificar con un test que un fallo transitorio deja la clave disponible para un reintento
- [x] 5.3 Bloquear los lotes que la reserva va a consumir (`SELECT ... FOR UPDATE`, en orden FIFO) dentro de la misma transacción, y calcular el disponible con esos lotes ya bloqueados; verificar con un test de **hilos concurrentes** que dos reservas simultáneas por encima del disponible **no** dejan el disponible en negativo
- [x] 5.4 Rechazar la reserva o la salida que exceda el disponible con un error explícito de stock insuficiente, y verificar con un test que el saldo no se altera cuando la operación se rechaza

## 6. Costeo FIFO por lotes

- [x] 6.1 Implementar `domain/model/StockLot.java` con cantidad, cantidad reservada, coste unitario y fecha de entrada, y `domain/port/StockLotRepositoryPort.java` con la lectura de los lotes de un repuesto y una sucursal **en orden FIFO** y el bloqueo de los que se van a consumir; verificar con un test unitario que el orden de consumo es el de entrada
- [x] 6.2 Implementar en `application/service/StockOperationService.java` la **apertura de lote** en cada entrada (`IN`), con su coste unitario real, y el **consumo FIFO** en las salidas y en las reservas, que consume primero el lote más antiguo con disponible; verificar con un test que, con dos lotes de 5 unidades a 10 y 20 soles, consumir 7 deja 3 unidades a 10 y toma 4 del segundo
- [x] 6.3 Calcular el **valor del stock** en `StockBalanceResult` a partir de los lotes que quedan, y **no** como la suma de los movimientos; verificar con un test que el valor de 10 unidades a 25 soles es 250, y que el saldo disponible descuenta lo reservado
- [x] 6.4 Registrar el `unit_cost` real en el movimiento, de modo que la historia del coste de cada unidad quede guardada; verificar con un test que un movimiento con entrada conserva el coste con el que se compró
- [x] 6.5 Comprobar en el dominio que la **reserva** consume lotes y no solo quantities globales, de modo que el valor reservado sea el de los lotes reservados; verificar con un test que el valor del stock reservado es la suma de la cantidad reservada por lote por su coste

## 7. La saga completa y los eventos

- [x] 7.1 Implementar `application/usecase/StockOperationUseCase.java` con la operación de **confirmación** (`CommitStock`), idempotente por su propia clave, que consume el stock reservado y deja la reserva sin efecto; verificar con un test que confirmar consume el stock y que repetir la confirmación con la misma clave no descuenta dos veces
- [x] 7.2 Rechazar la confirmación de una reserva que **no pertenece al taller** de la petición, y la confirmación de una reserva ya confirmada con una clave distinta; verificar con un test que el stock de otro taller no se toca
- [x] 7.3 Implementar `domain/event/{StockReserved,StockReleased,StockLow}.java` y `domain/port/EventPublisherPort.java`, y la **bandeja de salida** en `infrastructure/messaging/outbox/` replicando el patrón del tenant-service: el evento se persiste **junto al cambio de stock** en la misma transacción; verificar con un test que la bandeja y el movimiento se confirman juntos y que un fallo del movimiento no deja el evento publicado
- [x] 7.4 Implementar el **relay** de la bandeja, con reintentos, espera creciente y cola de muertos consultable y reactivable, y la publicación **fuera** de la transacción de negocio; verificar con un test que un evento fallido no detiene el lote y que la cola de muertos es reactivable
- [x] 7.5 Emitir `StockLow` cuando el disponible de un repuesto quede por debajo de `min_stock`, con el repuesto, la sucursal y el nivel, y **no** emitirlos cuando siga por encima; verificar con un test los dos casos
- [x] 7.6 Publicar `StockReserved` y `StockReleased` con el identificador del taller y del movimiento, y comprobar que el consumidor puede **deduplicar** por el identificador del evento; verificar con un test que dos eventos con el mismo identificador se deduplican

## 8. API, rutas y gateway

- [x] 8.1 Mover las rutas de `BranchController`, `SparePartController` y `StockController` a `/api/v1/inventory/...`, y añadir el endpoint de confirmación `POST /api/v1/inventory/stock/commit`; verificar con un test de contrato que `POST /api/v1/inventory/stock/reserve` responde y que `POST /api/v1/stock/reserve` responde `404`
- [x] 8.2 Sustituir el cuerpo de error por **`ProblemDetail`** (RFC 9457) con un **código de error estable por familia** y el `tenant_id`, y dejar de imprimir la excepción por la salida estándar para **registrarla con su traza**; verificar con un test que el conflicto de SKU responde con su código y que el error interno no expone la clase de la excepción
- [x] 8.3 En `erp-api-gateway`, corregir la ruta `inventory-service` al **8084** (donde escucha el servicio) y `work-order-service` al **8088**, con el plazo de **500 ms** en ambas, en la configuración y en el perfil de test; verificar con el test de la tabla de rutas que el destino, el puerto y el plazo son los correctos
- [x] 8.4 Cubrir el contrato de la API con tests: reserva correcta, reserva sin clave, clave repetida, stock insuficiente, confirmación, liberación, SKU repetido, rol insuficiente, token ausente y ruta antigua; verificar con `mvn -o test` que la suite de API pasa

## 9. Observabilidad y pruebas

- [x] 9.1 Implementar `application/logging/SecurityEventLogger.java` con el **mismo esquema** que el del servicio de autenticación, sin parámetros para credenciales, y registrar los eventos de stock y los accesos denegados; verificar con un test que el esquema no admite tokens ni contraseñas
- [x] 9.2 Implementar `infrastructure/health/{DatabaseHealthIndicator,ReadinessIndicator}.java` con comprobación **real** de PostgreSQL, y exponer `/health` y `/ready`; verificar con un test que, con la base de datos inaccesible, la disponibilidad informa del fallo y responde 503
- [x] 9.3 Sustituir el perfil de test **H2** por **PostgreSQL embebido** con las migraciones aplicadas y `ddl-auto: validate`, en `src/test/resources/application.yml`; verificar con `mvn -o test` que las migraciones se ejecutan de verdad en la suite
- [x] 9.4 Escribir la suite de **aislamiento entre talleres**: lectura, escritura, reserva, confirmación y liberación de un taller sobre los datos de otro, con el atacante y la víctima nombrados; verificar que todos los casos se rechazan
- [x] 9.5 Escribir la suite de **concurrencia** con hilos de verdad: dos reservas simultáneas por encima del disponible, y dos peticiones concurrentes con la misma clave de idempotencia; verificar que el stock nunca queda en negativo y que se registra un solo movimiento
- [x] 9.6 Escribir la suite de **costeo FIFO**: entrada con coste, consumo por orden de llegada, valor del stock disponible y valor del stock reservado; verificar con `mvn -o test` que la suite pasa
- [x] 9.7 Medir la cobertura con JaCoCo y exigir el **80 %** en `domain/`, con la regla verificada que **falla** cuando la cobertura baja del umbral; verificar con `mvn -o verify` que la build falla si la cobertura baja
- [x] 9.8 Regenerar `docs/matriz-roles-endpoints.md` con `docs/verificar-matriz-roles.py` para que incluya los endpoints del inventario, y verificar que el comando **falla** si algún endpoint de escritura queda sin rol declarado

## 10. Delivery, documentación y cierre

- [x] 10.1 Ajustar `Dockerfile` y `compose.yaml` al cambio: la base se crea con el rol de propietario, la aplicación conecta con `erp_inventory_app` y las migraciones con el propietario; verificar con un arranque local que el servicio levanta y responde en `/api/v1/inventory/stock/balance/...` con `401` sin token
- [x] 10.2 Actualizar `erp-inventory-service/doc/arquitectura-y-conexiones.md`: la matriz de endpoints con el prefijo y los roles, la saga completa con las tres operaciones, el aislamiento real y el modelo de lotes; verificar con `grep -n "api/v1/inventory" doc/arquitectura-y-conexiones.md` que no queda ninguna ruta antigua
- [x] 10.3 Actualizar `docs/doc-estado-implementacion.md`: la fila 7 de §2 deja de decir "⚫ No existe" y pasa al estado real, con los hallazgos que este change cierra en este servicio, y el Sprint 4+ recoge el servicio construido
- [x] 10.4 Validar el change con `openspec validate --all --strict` en el repositorio y en la raíz del workspace, y comprobar que `git log --merges develop..feature/add-inventory-service` **no devuelve ninguna línea**
- [x] 10.5 Publicar la rama `feature/add-inventory-service` en `fork-devs/erp-inventory-service`, **sin merges**, con un commit por task y los mensajes en inglés
