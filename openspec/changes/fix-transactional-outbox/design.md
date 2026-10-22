# Design

## Context

Tres servicios comparten el mismo defecto estructural: **la demarcación transaccional está en la capa equivocada**. En los tres, el `@Transactional` aparece únicamente en los adaptadores de persistencia, donde cada llamada a un repositorio abre y cierra su propia transacción. La consecuencia no es una transacción mal configurada: es que **no existe ningún límite transaccional que abarque más de una escritura**, y por tanto ninguna operación de negocio del proyecto puede ser atómica.

El caso del outbox es el más grave porque el patrón se eligió precisamente por su garantía. `doc-design-patterns.md` §6 lo describe así: *"guarda el cambio de negocio y el evento en la misma transacción local de PostgreSQL, y un relevador los publica después al bus, garantizando entrega 'al menos una vez' sin transacciones distribuidas"*. La implementación actual no cumple ninguna de las dos mitades: el cambio y el evento se escriben en transacciones separadas, y el relevador no tiene ninguna de las propiedades que hacen que la entrega sea fiable (reintento, cola de muertos, registro de errores, reclamación entre réplicas).

Hay un matiz que conviene dejar explícito porque condiciona la solución: **un bean registrado con `@Bean` que devuelve `new MiServicio()` sí es proxied por Spring y sí honra `@Transactional`**. Se verificó empíricamente sobre Spring Boot 4.1.1 (el método annotated se ejecuta con `TransactionSynchronizationManager.isActualTransactionActive() == true` y el bean es un proxy CGLIB). Por tanto el obstáculo para anotar los casos de uso **no es Spring, sino la convención del proyecto** de que la capa `application/` no importe frameworks. La solución debe respetar esa convención, no sortearla.

## Goals / Non-Goals

**Goals:**

- Que exista **una** abstracción de demarcación transaccional, propia del dominio, y que la usen los tres servicios por igual.
- Que el cambio de negocio y su evento de dominio se confirmen juntos, y que el fallo de cualquiera de los dos revierte ambos.
- Que la cola de salida de eventos sea **fiable**: reintento con espera creciente, registro de errores, cola de muertos consultable y recuperable, y ningún mensaje bloquea a los demás.
- Que el evento sea **identificable** por su consumidor, de modo que la entrega "al menos una vez" sea safely deduplicable.
- Que varias réplicas del relay puedan convivir sin publicar dos veces el mismo mensaje.
- Que la migración de la demarcación sea **mecánica y verificable**: el mismo patrón en los tres servicios.

**Non-Goals:**

- No se introduce el transporte de publicación (Kafka). El `EventPublisherPort` conserva su implementación actual; lo que este change garantiza es que la fila del outbox ya es correcta, reclamada y reintentable cuando el transporte se conecte.
- No se implementa la **semántica de idempotencia** del hub (reclamar la clave antes de llamar al proveedor, actualizarla después, no cachear los fallos): pertenece al change de integración con proveedores, y depende de la demarcación que aquí se introduce.
- No se cambia el **formato del payload** de los eventos: el contrato con los consumidores se mantiene. Lo que se añade es una columna con el identificador del evento, que ya viaja dentro del payload.
- No se introduce el patrón de **saga coreografiada** ni sus compensaciones: no hay todavía servicios que la ejecuten.
- No se toca el modelo de dominio de los agregados, ni sus invariantes.

## Decisions

### D1 — La demarcación transaccional se expresa con un puerto `UnitOfWork` del dominio

En los tres servicios se incorpora `domain/port/UnitOfWork.java`, con la forma `void runInTransaction(Runnable work)` y `<T> T callInTransaction(Supplier<T> work)`. La implementación, en `infrastructure/config/UnitOfWorkAdapter.java`, se apoya en `TransactionTemplate`. Los casos de uso la reciben por constructor y la usan para envolver sus operaciones multi-escritura.

**Alternativas descartadas:**
- *Anotar los casos de uso con `@Transactional`.* Se descartó aunque funciona técnicamente (verificado), pero importa `org.springframework.transaction.annotation` en `application/`, lo que rompe la regla de que la capa de aplicación no dependa de frameworks y hace que el límite transaccional dependa de un proxy de Spring en vez de una abstracción del dominio. Además, con anotación el límite es implícito y no se ve en la firma del caso de uso.
- *Inyectar `TransactionTemplate` directamente en los casos de uso.* Se descartó por la misma razón: introduce `org.springframework` en `application/`.
- *Mover la demarcación a los adapters, con `PROPAGATION_REQUIRED` y un servicio transaccional que envuelva a los demás.* Se descartó porque es un proxy de servicios, no una demarcación: los adapters seguirían pudiendo invocarse fuera de ella sin que nada lo indique, que es exactamente el defecto que se quiere eliminar.

### D2 — Los adaptadores de persistencia se unen a la transacción abierta y fallan si no hay ninguna

Se elimina el `@Transactional` de los métodos de escritura de los adaptadores de persistencia de los tres servicios. Para que la garantía sea real y no una convención, cada adaptador comprueba al entrar que hay una transacción activa (`TransactionSynchronizationManager.isActualTransactionActive()`) y **lanza una excepción de infraestructura si no la hay**, en lugar de dejar que el repositorio de Spring Data abra una transacción implícita.

**Alternativas descartadas:**
- *Dejar que Spring Data abra una transacción implícita cuando no hay ninguna.* Se descartó porque reproduce el defecto actual de forma silenciosa: la operación se confirma en su propia transacción y el llamante cree que estaba dentro de un límite mayor. Es el modo de fallo que el reporte señala en §5.3.
- *Propagar la excepción al llamante sin más contexto.* Se descartó porque el mensaje de error debe indicar que la causa es la ausencia de demarcación, no un fallo de la consulta, para que el diagnóstico en producción no lleve a la hipótesis equivocada.

### D3 — El relay continúa tras un fallo y reprograma el mensaje con espera creciente

`OutboxProcessorService.processUnpublishedMessages` sustituye el `break` por un `continue`: un mensaje fallido no detiene el lote. Cada fallo se registra con su mensaje de error, incrementa `attempts` y calcula la espera siguiente a partir de una tabla de intervalos creciente acotada por un máximo configurable. El mensaje se guarda con su nueva fecha de reintento y su último error, y el ciclo continúa con el siguiente.

**Alternativas descartadas:**
- *Mantener el `break`.* Se descartó: es el defecto actual (P1 6.8) y convierte un mensaje envenenado en un bloqueo permanente de la cola, porque los mensajes se leen ordenados por fecha de creación y el fallido siempre es el primero.
- *Reintentar de inmediato sin espera.* Se descarta porque reproduce el patrón de martilleo que satura al bus y al proveedor, y porque el propio patrón de reintento del proyecto (`doc-design-patterns.md` §9) especifica espera creciente.
- *Borrar el mensaje agotado.* Se descartar porque destruye evidencia de un evento que el sistema afirma haber producido.

### D4 — El agotamiento de intentos lleva a un estado de cola de muertos consultable y reactivable

El estado del mensaje en la bandeja de salida pasa a ser explícito: pendiente, publicado, o agotado. Al alcanzar el máximo de intentos, el mensaje queda en estado agotado con su último error y deja de reprogramarse. Un caso de uso de consulta devuelve los mensajes agotados, y otro los reactiva: restablece el contador de intentos y vuelve a programarlos.

**Alternativas descartadas:**
- *Marcar el agotado con un booleano y reutilizar `published`.* Se descartó porque `published` significa "entregado", y reutilizarlo para "abandonado" confunde la semántica de la tabla y de las consultas que la leen.
- *Dejar el mensaje agotado en estado pendiente con `next_attempt_at` en el pasado, para que siga reintentándose.* Se descartó porque produce un bucle infinito de reintentos sobre un mensaje que ya se sabe irresoluble, que es precisamente lo que la cola de muertos existe para evitar.

### D5 — La tabla de bandeja de salida incorpora las columnas que el relay necesita

`tenant_outbox` pasa a tener `event_id`, `attempts`, `next_attempt_at`, `last_error` y `status`, con `status` sustituyendo al booleano `published`. `event_id` es único por registro y se genera una sola vez, en la creación del mensaje, de modo que el mismo identificador acompaña a todos los reintentos de publicación.

**Alternativas descartadas:**
- *Mantener `published` como booleano y deducir el estado de la combinación de `attempts` y `next_attempt_at`.* Se descartó porque hace el estado del mensaje implícito y obliga a cada consulta a replicar las reglas del relay.
- *Almacenar el identificador del evento sólo dentro del payload.* Se descartó porque un consumidor que deduplique necesita consultarlo, y obligarle a parsear el JSON para obtener una clave de deduplicación es una fuente de inconsistencias entre implementaciones.

### D6 — El relay reclama los mensajes de forma exclusiva

La lectura de mensajes pendientes pasa a reclamar la fila con `SELECT ... FOR UPDATE SKIP LOCKED`, de modo que dos instancias concurrentes del relay obtienen conjuntos disjuntos. La marca de publicado y el incremento de intentos se escriben en la misma transacción que la publicación.

**Alternativas descartadas:**
- *Marcar el mensaje como "en curso" antes de publicarlo, con un `UPDATE` previo.* Se descartó porque introduce una ventana en la que el mensaje queda en un estado intermediate sin dueño si la instancia muere, lo que exige un mecanismo de recuperación de mensajes abandonados. `SKIP LOCKED` delega ese problema en la base de datos, que ya lo resuelve con el rollback de la transacción.

### D7 — La publicación se ejecuta en una fase separada de la transacción de negocio

El relay abre su propia transacción para tomar y actualizar el registro de la bandeja de salida, y la publicación al bus ocurre con esa transacción cerrada o, en cualquier caso, fuera de la transacción que confirmó el cambio de negocio. La publicación nunca se ejecuta dentro de la transacción que originó el evento.

**Alternativas descartadas:**
- *Publicar dentro de la transacción de negocio, confiando en que el commit salga bien.* Se descartó porque es exactamente el patrón que el outbox evita: si el commit falla después de publicar, el bus tiene un evento que la base de datos no tiene.

### D8 — El identificador del evento se genera en el dominio y se propaga en el payload

`TenantOutboxMessage` recibe el identificador del evento en su construcción, y el serializado del payload lo incluye junto con el resto de campos. La generación ocurre una sola vez, en el caso de uso que crea el evento.

**Alternativas descartadas:**
- *Generar el identificador dentro del serializador del payload.* Se descartó porque entonces el registro de la tabla y el payload podrían acabar con identificadores distintos, y el consumidor no podría fiarse de ninguno de los dos.
- *Usar el identificador del propio registro de la bandeja de salida como identificador del evento.* Se descartó porque el registro de la bandeja de salida es un artefacto de la implementación del patrón, no la identidad del hecho de dominio: cuando el outbox se sustituya por otro transporte, la identidad del evento debe seguir siendo la misma.

## Risks / Trade-offs

- **[El nuevo puerto `UnitOfWork` toca muchos ficheros de los tres servicios]** → Mitigación: el cambio es puramente estructural y verificable de forma automática. Se añade una comprobación en la suite que verifica que ningún adapter de persistencia abre su propia transacción y que ningún caso de uso escribe en más de un repositorio sin envolver la operación en `UnitOfWork`.
- **[La comprobación de transacción activa en los adapters añade una dependencia de Spring en la capa de infraestructura]** → Mitigación: la comprobación se centraliza en una única clase auxiliar de infraestructura, y los adapters de dominio no la conocen.
- **[La tabla de bandeja de salida cambia de forma]** → Mitigación: la migración es aditiva y conserva los datos; el `published` booleano se migra a `status` en la misma migración. La columna `published` se conserva durante un ciclo de despliegue para no romper a un lector antiguo, y se elimina en una migración posterior.
- **[El `continue` en lugar del `break` aumenta el trabajo por ciclo cuando hay mensajes fallidos]** → Mitigación: el número de intentos está acotado y la espera es creciente, de modo que un mensaje problemático consume una fracción del ciclo. El tamaño del lote sigue siendo configurable.
- **[La calidad de la serialización del payload]** → Mitigación: el formato del payload no cambia en este change. La sustitución del serializador manual por un serializador de biblioteca, junto con la comprobación de que el payload es JSON válido, pertenece al change de publicación de eventos de dominio.
- **[El relay sin transporte real no puede exercised el camino de publicación real]** → Mitigación: la suite usa un `EventPublisherPort` de prueba que falla de forma controlada, lo que permite verificar el no-bloqueo, el registro de errores, la espera creciente y la cola de muertos sin depender de un bus.

## Migration Plan

1. **Base de datos.** Migración que añade a `tenant_outbox` las columnas `event_id`, `attempts`, `next_attempt_at`, `last_error` y `status`, con backfill de `status` a partir del booleano `published` existente, y que conserva `published` durante un ciclo. Los valores iniciales son `attempts = 0`, `next_attempt_at = created_at` y `status` derivado de `published`.
2. **Modelo y persistencia.** `TenantOutboxMessage` incorpora el identificador del evento, el contador de intentos, la fecha de reintento, el último error y el estado. La entidad JPA, el mapper y el repositorio se actualizan en consecuencia, y el repositorio incorpora la consulta de pendientes con reclamación exclusiva.
3. **Demarcación transaccional.** Se introduce el puerto `UnitOfWork` y su implementación en los tres servicios, y se retira el `@Transactional` de los métodos de escritura de los adaptadores de persistencia.
4. **Casos de uso.** Los casos de uso que escriben en más de un repositorio pasan a usar `UnitOfWork`. Esto incluye el alta de taller, el cambio de plan y la suspensión en el servicio de tenants, y la rotación de refresh token, el reset de contraseña y el login en el servicio de autenticación.
5. **Relay.** El relay deja de bloquear el lote, registra los errores, reprograma con espera creciente, mueve a cola de muertos al agotar los intentos y añade los casos de uso de consulta y reactivación de la cola de muertos.
6. **Verificación.** Se activan los tests de atomicidad del outbox, de no-bloqueo de la cola, de espera creciente, de cola de muertos y de reclamación entre réplicas.

**Reversión.** Las migraciones son aditivas y el `status` es derivable del `published` preexistente, de modo que la reversión de la parte de base de datos es un `ALTER TABLE ... DROP COLUMN` sin pérdida de información. La reversión de la parte de código es el despliegue de la versión anterior, que sigue funcionando porque `published` se conserva durante un ciclo.

## Open Questions

- ¿El contador de intentos y la espera creciente deben ser comunes a todos los tipos de evento, o cada tipo de evento puede tener su propia política de reintento? La decisión actual es una política única y configurable por servicio. No cambia el comportamiento exigido por las specs de este change, y por tanto no bloquea su implementación.
- ¿La cola de muertos debe tener una verticale propia, o basta con un estado en la misma tabla? La decisión actual es un estado en la misma tabla. Una verticale propia tiene sentido cuando los mensajes agotados se archivan con retención distinta, lo que todavía no está justificado por ningún requisito.
- ¿El identificador de evento debe ser un UUID aleatorio o un identificador derivado del agregado y del tipo de evento, que permitiría detectar de forma determinista dos eventos de dominio idénticos? La decisión actual es un UUID, coherente con el resto de identificadores del proyecto. La alternativa se descarta por ahora, y se deja anotada como pregunta para el change de publicación de eventos de dominio.
