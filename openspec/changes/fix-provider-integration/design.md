# Design

## Context

El hub es el único componente que habla con SUNAT, con el proveedor de pagos y con los servicios de consulta de identidad. Su responsabilidad tiene dos mitades: **traducir** el modelo externo al interno, y **garantizar** que la operación con el externo ocurra una sola vez y que su resultado sea fiel. La primera mitad está razonablemente hecha: los cuatro adaptadores son clientes HTTP reales, traducen a modelos propios y el dominio no conoce la forma externa. La segunda mitad está roto en sus tres propiedades.

**La idempotencia es un `INSERT` disfrazado de `UPDATE`.** `IdempotencyRecord.of()` genera un identificador nuevo en cada llamada, la entidad no tiene control de concurrencia y el identificador es asignado. Spring Data resuelve por `merge()`, Hibernate emite un `INSERT`, y el UNIQUE `(tenant_id, scope, key_val)` —que está bien diseñado— bloquea la segunda escritura. Como el `merge` de un identificador inexistente produce un `INSERT`, **no existe ninguna forma de actualizar un registro de idempotencia**. La consecuencia más grave no es el error 500: es que el reintento de un comprobante que SUNAT ya aceptó falla al escribir la idempotencia, la excepción se confunde con un fallo del proveedor, y **el mismo comprobante se reenvía** hasta acabar en la cola de muertos con SUNAT habiendo aceptado cinco veces el mismo documento.

**El camino crítico tiene una ventana de carrera.** La secuencia es `findByTenantAndScopeAndKey` → llamar al proveedor → `save`. Como el hub no tiene demarcación transaccional en la capa de aplicación, son dos transacciones, y dos peticiones concurrentes con la misma clave pasan ambas la comprobación. Stripe queda a salvo porque su adapter propaga la clave al proveedor; **SUNAT no tiene esa protección**.

**El modo de prueba está dentro del camino de producción.** Los cuatro adaptadores tienen una rama que devuelve una respuesta fabricada cuando el token contiene "mock" o "sandbox" o el modo está activo. Con la configuración por defecto, esa rama se cumple en los cuatro. La respuesta fabricada del adaptador de SUNAT incluye un hash de CDR constante y dos URLs de documento que no existen; la del adaptador de identidad vehicular incluye un VIN constante. El sistema, por tanto, **afirma haber emitido comprobantes que no ha emitido** y **registra esos falsos éxitos en la bitácora de auditoría**.

Hay además un **desajuste de contrato entre el hub y la documentación**: el patrón de idempotencia del proyecto (`doc-design-patterns.md` §8) habla de "clave única por operación" con "respuesta guardada"; la implementación deriva la clave de los datos del cliente cuando falta la cabecera, lo que convierte una clave de idempotencia en una **huella de la operación**, y por tanto en una restricción artificial sobre qué pagos puede hacer un cliente.

## Goals / Non-Goals

**Goals:**

- Que una operación con clave de idempotencia se ejecute **a lo sumo una vez**, incluso con peticiones concurrentes.
- Que el registro de idempotencia se pueda **actualizar** por clave de negocio, de modo que el resultado final de una operación quede registrado.
- Que un fallo transitorio **no** se convierta en un resultado definitivo, y que el reintento reprogramado no reenvíe una operación ya aceptada.
- Que el sistema **no pueda afirmar** un resultado que el proveedor no dio, y que el arranque en un entorno no local con el modo de prueba activo **falle**.
- Que el estado de un comprobante distinga el rechazo **recuperable** del **definitivo** y conserve el código real del proveedor.
- Que la anulación siga el procedimiento que el proveedor exige.
- Que la numeración de comprobantes sea **única por taller** y que su asignación tenga un propietario explícito.
- Que la cola de muertos sea **consultable y reactivable**, y que la ventana de reintento alcance las 24 horas del diseño.
- Que el procesamiento de avisos sea **idempotente** y resista la reentrega, y que **todas** las rutas comprueben la ventana temporal.
- Que cada servicio interno tenga **identidad y privilegios acotados**.

**Non-Goals:**

- No se introduce el **transporte de eventos** (Kafka) ni el **circuit breaker** por adaptador: pertenecen a los changes de publicación de eventos y de resiliencia, y el primero es además anterior en la secuencia de despliegue.
- No se crea el **agregado de comprobantes** con su motor de estados y su tabla: el hub traduce fielmente lo que el proveedor informa, y el agregado pertenece al servicio de facturación, que aún no existe.
- No se migra a un **proveedor de pagos** distinto ni se implementa la **facturación electrónica con XML firmado**: el hub sigue hablando con los proveedores actuales.
- No se introduce la **selección de proveedor en caliente** con su caché y su invalidación: la configuración de proveedor se resuelve en el arranque.
- No se cambia el **formato del payload** de los avisos entrantes más allá de exigir el identificador de evento.

## Decisions

### D1 — La clave de idempotencia se reclama antes de contactar al proveedor

El flujo de una operación de cobro es: **insertar** la clave de idempotencia con estado "en curso", y sólo si la inserción prospera, contactar al proveedor; después, **actualizar** ese registro con el resultado. La inserción la arbitra la base de datos mediante la restricción de unicidad, de modo que la segunda petición concurrente recibe un conflicto y **no llama al proveedor**.

El puerto de repositorio gana una operación de **actualización por clave de negocio** —tenant, alcance y clave—, y la entidad incorpora control de concurrencia optimista. La generación de identificador por escritura desaparece: el identificador lo asigna la base de datos.

**Alternativas descartadas:**
- *Mantener el `check-then-act` con el UNIQUE como barrera y capturar la violación para devolver el resultado cacheado.* Se descartó porque la violación ocurre **después** del efecto: el proveedor ya fue contactado dos veces. Es la causa directa de la re-emisión de comprobantes.
- *Un bloqueo pesimista sobre la fila de la clave.* Se descartó porque la fila no existe en el camino que importa: se trata de una clave que aún no se ha escrito, de modo que no hay nada que bloquear. La inserción condicional resuelve el caso.
- *Generar el identificador con el patrón "insertar si no existe, y si no, leer".* Se descartó por ser equivalente a la alternativa descartada, con el coste añadido de una segunda vuelta.

### D2 — Un fallo transitorio libera la clave y reprograma la operación

Cuando el proveedor falla de forma transitoria, el registro de idempotencia **no** guarda ese fallo como resultado: la clave queda en estado "liberada para reintento" y la operación se reprograma en la cola de reintentos con su espera. Un reintento posterior con la misma clave puede volver a ejecutarla. Cuando el fallo es definitivo, el resultado **sí** se guarda, porque repetirlo no va a cambiar nada.

**Alternativas descartadas:**
- *Guardar siempre el resultado, sea éxito o fallo.* Se descarta porque es el comportamiento actual y convierte un fallo transitorio en un fallo permanente para esa clave.
- *No guardar nunca los fallos.* Se descarta porque el proveedor devuelve dos clases de fallo con significado distinto, y el sistema debe poder distinguir "rechazado por el proveedor" de "no se pudo contactar".

### D3 — La clave de idempotencia la proporciona el cliente y es obligatoria

La cabecera de clave de idempotencia es **obligatoria** en el cobro. La derivación automática a partir de tenant, tarjeta, importe y correo **se elimina**. El sistema rechaza la operación antes de contactar al proveedor cuando la clave no llega.

**Alternativas descartadas:**
- *Mantener la derivación como valor por defecto cuando falta la cabecera.* Se descarta porque es la causa de que un segundo pago legítimo del mismo cliente no se cobre: la clave derivada es una huella de la operación, y dos operaciones legítimas idénticas la comparten. Una clave de idempotencia identifica una **intención de pago**, no una **forma de pago**.
- *Generar la clave en el servidor y devolverla al cliente para que la reutilice.* Se descarta porque el servidor no tiene forma de saber que se trata de la misma intención.

### D4 — El modo de prueba es un adapter simulado por perfil

El modo de prueba de cada proveedor pasa de ser una rama dentro del adapter de producción a ser **un adapter simulado completo**, registrado por el perfil de entorno. El adapter de producción **no contiene ninguna rama de simulación**. El arranque en un perfil que no sea de desarrollo, con un adapter simulado registrado, **falla de forma explícita**.

**Alternativas descartadas:**
- *Mantener la rama condicional pero exigir una variable de entorno explícita.* Se descarta porque conserva el acoplamiento entre el adapter de producción y el modo de prueba, que es lo que permite que un valor de configuración por defecto active el camino falso sin que nadie lo advierta.
- *Eliminar el modo de prueba por completo.* Se descarta porque el desarrollo y las pruebas de integración necesitan poder ejecutar el hub sin proveedores externos, y el modo de prueba es la forma más barata de hacerlo.

### D5 — Los estados del comprobante son un conjunto explícito y el código real del proveedor se conserva

El estado del comprobante pasa de ser un texto libre a un conjunto explícito que incluye `ACEPTADO`, `OBSERVADO`, `RECHAZADO`, `PENDIENTE_SUNAT` y `ANULADO`. `OBSERVADO` —que en SUNAT significa "aceptado con observaciones, corregible"— **deja de colapsarse en `RECHAZADO`**. El código de respuesta que el proveedor informó se conserva en el resultado, en lugar de sustituirse por un literal.

**Alternativas descartadas:**
- *Mantener `OBSERVADO` mapeado a `RECHAZADO` y avisar sólo en el log.* Se descarta porque el efecto de este colapso es que el cliente **no reintenta** una emisión corregible, y la factura se pierde. La distinción no es cosmética: es la diferencia entre un rechazo recuperable y uno definitivo.
- *No distinguir los estados y exponer sólo "aceptado o no".* Se descarta por la misma razón, y porque obligaría al cliente a interpretar el texto del proveedor.

### D6 — La anulación sigue el procedimiento de dos pasos del proveedor

La anulación de un comprobante se realiza en el procedimiento que el proveedor exige: obtener el ticket de comunicación y referenciarlo en la solicitud de anulación. Una anulación rechazada de forma transitoria **se reprograma** en la cola de reintentos, igual que la emisión.

**Alternativas descartadas:**
- *Delegar la anulación en el servicio de facturación.* Se descarta porque el servicio de facturación no existe, y porque el hub es quien tiene la configuración del proveedor. La decisión de **quién es el dueño de la anulación** sí se registra como pregunta abierta.
- *Enviar la anulación directamente sin el ticket previo.* Se descarta porque el proveedor la rechaza, de modo que la función no existiría en la práctica.

### D7 — El hub verifica la unicidad de la numeración; el servicio de facturación la asigna

El hub **no** asigna la numeración. Comprueba que la combinación de taller, serie y número no exista antes de emitir, y rechaza la emisión si existe. La asignación de series y correlativos corresponde al servicio de facturación, que la construirá con su propia reserva de numeración.

**Alternativas descartadas:**
- *Introducir en el hub una tabla de series y correlativos con reserva.* Se descarta porque duplicaría la numeración en dos servicios y crearía dos fuentes de verdad sobre el mismo número, que es exactamente la clase de defecto que este change elimina en otros sitios. El hub verifica; quien asigna es el dueño del agregado.
- *No verificar la unicidad en el hub.* Se descarta porque el hub es quien contacta al proveedor, y un correlativo duplicado es un comprobante duplicado en la administración fiscal.

### D8 — La configuración de proveedor por base de datos se elimina

La tabla `provider_configs`, su modelo de dominio, su entidad, su repositorio, su adapter y su semilla **se eliminan**. La selección de proveedor pasa a resolverse en el **arranque**, por configuración de despliegue, con el conjunto de adapters detrás del puerto como punto de extensión.

**Alternativas descartadas:**
- *Implementar el resolutor por proveedor, con caché e invalidación.* Se descarta **para este change** porque es un mecanismo de configuración con estado que cachear e invalidar, y porque su único beneficio —cambiar de proveedor sin reiniciar— no corresponde a ningún requisito vigente: el requisito QA-7 habla de **cambiar el proveedor en el código en menos de dos días**, lo que el conjunto de adapters ya permite.
- *Mantener la tabla sin usar.* Se descarta porque es código muerto que induce a pensar que el cambio en caliente es posible.

### D9 — La ventana de reintento es de 24 horas y es configurable

La escalera de esperas se parametrizable y la ventana total alcanza el plazo de recuperación previsto por el proveedor. El número máximo de intentos deja de estar fijado en el código y pasa a ser configurable. El estado de **agotado** se representa en la tabla de intentos, de modo que la profundidad de la cola de muertos sea consultable desde la bitácora.

**Alternativas descartadas:**
- *Mantener la escalera fija de cinco entradas con máximo de cinco intentos.* Se descarta porque con esa escalera la cola de muertos se alcanza a los treinta y seis minutos, muy por debajo de la ventana de recuperación de una caída de proveedor de treinta minutos más su degradación, y porque el comentario del código afirma una ventana que la tabla no produce.
- *Reintentar con espera exponencial pura.* Se descarta porque las primeras esperas de la tabla actual son más conservadoras que una exponencial pura, lo que es adecuado para un proveedor con coste por llamada.

### D10 — El aviso se deduplica por identificador de evento y todas las rutas comprueban la ventana temporal

El hub registra los avisos procesados con un identificador de evento único, y rechaza los duplicados. La actualización de estado que aporta el aviso se escribe bajo la **misma clave de idempotencia** de la operación original. Todas las rutas de firma, no sólo la de Stripe, comprueban la marca de tiempo del aviso contra la ventana tolerada.

**Alternativas descartadas:**
- *Confiar en la idempotencia del proveedor para que no reenvíe.* Se descarta porque el contrato de entrega no es del proveedor: el hub es quien debe ser idempotente, porque un aviso puede reintentarse por la propia red del proveedor.
- *Rechazar todo aviso sin identificador de evento.* Se descarta porque algunos proveedores no lo envían, y en ese caso el identificador se deriva de la huella de la carga útil, lo que es suficiente para detectar la reentrega.

### D11 — El secreto de firma de avisos es propio del canal de avisos

El hub utiliza una clave de firma de avisos distinta de la credencial de acceso al proveedor, con su propia configuración. La credencial del proveedor **no** se acepta como secreto de firma.

**Alternativas descartadas:**
- *Reutilizar la credencial del proveedor porque ya es un secreto que el hub conoce.* Se descarta porque las dos credenciales tienen **ámbitos de exposición distintos**: la credencial del proveedor se transmite a un tercero en cada llamada, mientras que el secreto de firma sólo se usa para verificar. Que el tercero conozca la primera no debe darle la capacidad de fabricar avisos.

### D12 — Cada servicio interno tiene identidad y privilegios acotados

El hub deja de conceder privilegios de administrador de plataforma a la credencial compartida. Cada servicio interno tiene **identidad propia** y un **rol acotado** a las operaciones que necesita. El hub verifica el privilegio requerido para cada operación de administración, y registra quién la ejecutó.

**Alternativas descartadas:**
- *Mantener la credencial compartida con privilegios de administrador.* Se descarta porque convierte un secreto compartido en una credencial de superusuario de plataforma, sin identidad de emisor, sin rotación y sin atribución. Es el defecto que el reporte señala en el hub y que este change cierra.
- *Resolver la identidad por dirección de origen o por nombre de máquina.* Se descarta porque es una identidad de red, no de servicio, y no sobrevive a un despliegue en otro entorno.

### D13 — La huella de la carga útil se calcula con un algoritmo criptográfico y se verifica

La huella que acompaña al registro de idempotencia se calcula con un algoritmo criptográfico, no con el hash de 32 bits de un objeto. En la reproducción de una operación, el sistema **verifica** que la huella de la carga útil entrante coincide con la almacenada, y rechaza la petición cuando no coincide.

**Alternativas descartadas:**
- *No almacenar ni verificar la huella.* Se descarta porque una clave de idempotencia reutilizada con una carga útil distinta devolvería el resultado de otra operación, que es un error silencioso y grave.
- *Mantener el hash de 32 bits y no verificarlo.* Se descarta porque una huella de 32 bits tiene colisiones y, sin verificar, no aporta nada.

## Risks / Trade-offs

- **[La reclamación previa de la clave cambia el flujo de error de las operaciones repetidas]** → Mitigación: la segunda petición concurrente recibe un conflicto explícito que dice que la operación está en curso, en lugar de un error de integridad. El cliente puede reintentar y recibir el resultado almacenado.
- **[Reclamar la clave antes del proveedor deja claves "en curso" si el proceso muere]** → Mitigación: el registro lleva marca de tiempo, y una reclamation por antigüedad recupera las claves cuyo proceso no llegó a completarlas. La ventana de reclamation es configurable y superior a la duración máxima de una llamada al proveedor.
- **[Un adapter simulado por perfil exige reorganizar el registro de beans]** → Mitigación: los adapters simulados se registran con el mismo puerto y con la misma configuración, de modo que el resto del hub no distingue entre ellos. La suite de integración puede así ejercitar el hub completo sin proveedores externos.
- **[La unicidad de la numeración en el hub exige conocer los comprobantes ya emitidos]** → Mitigación: el hub mantiene su propio registro de emisiones, que ya necesita para la idempotencia, y lo usa para la verificación. La responsabilidad de asignar la numeración queda en un solo servicio.
- **[Fallo cerrado en la anulación y en la emisión prolonga la ventana de un comprobante pendiente]** → Mitigación: la cola de reintentos y la cola de muertos hacen visible el estado, y el estado `PENDIENTE_SUNAT` es un estado legítimo del proceso, no un error.
- **[El límite de peticiones de las consultas de identidad puede afectar a un taller legítimo con una carga alta]** → Mitigación: el límite es por taller y configurable, y el valor por defecto se calibra con el volumen real. El caché de respuestas ya absorbe la repetición de la misma consulta.
- **[La identidad por servicio requiere distribuir credenciales a los demás servicios]** → Mitigación: las credenciales se resuelven por variable de entorno o almacén de secretos, y el hub registra quién ejecuta cada operación de administración.

## Migration Plan

1. **Base de datos.** Migración que añade a la tabla de idempotencia la huella criptográfica de la carga útil y el control de concurrencia optimista, y que crea la tabla de avisos procesados con su identificador de evento único. La restricción de unicidad actual **se conserva**: es la que arbitra la carrera.
2. **Dominio.** Se añade el conjunto de estados del comprobante, la operación de actualización por clave de negocio en el puerto de idempotencia, y el registro de estado de la clave de idempotencia —en curso, completada, liberada para reintento, rechazada— en el modelo de dominio.
3. **Flujo de cobro.** Se exige la cabecera de clave de idempotencia, se elimina la derivación, se reclama la clave antes de contactar al proveedor y se actualiza al terminar. Un fallo transitorio libera la clave y reprograma la operación.
4. **Flujo de emisión.** Se exige la comprobación de unicidad de la numeración, se reclama la clave antes de contactar al proveedor, y el estado del resultado distingue `OBSERVADO` de `RECHAZADO` conservando el código real del proveedor.
5. **Adaptadores.** Se extraen las ramas de simulación de los cuatro adapters de producción a adapters simulados registrados por perfil, y el arranque falla fuera del entorno de desarrollo con un adapter simulado registrado. Se corrige la anulación para que siga el procedimiento de dos pasos y se reprograme ante un rechazo transitorio.
6. **Reintentos.** La escalera de esperas y el número máximo de intentos pasan a ser configurables y alcanzan la ventana de 24 horas. Se añade el registro del estado de agotado en la tabla de intentos, la consulta de la cola de muertos y el endpoint de reactivación.
7. **Avisos.** Se añade el registro de avisos procesados con su identificador de evento, la escritura del estado bajo la clave de idempotencia original, y la comprobación de la ventana temporal a todas las rutas de firma. El secreto de firma de avisos pasa a tener su propia configuración.
8. **Identidad interna.** Se elimina el privilegio de administrador de la credencial compartida, se incorpora la identidad por servicio con su rol acotado, y la verificación de privilegios pasa a aplicarse a las operaciones de administración, registrando quién las ejecuta.
9. **Límite de peticiones.** Los endpoints de validación de identidad aplican un límite por taller, con la cabecera que informa al cliente del tiempo de espera.
10. **Limpieza.** Se eliminan la tabla de configuración de proveedor, su modelo, su entidad, su repositorio, su adapter y su semilla, y se corrige la enumeración de proveedores del dominio para que refleje los que existen.
11. **Verificación.** Se activan los tests de idempotencia concurrente, de no-re-emisión tras un éxito del proveedor, de no-fabricación de respuestas, de rechazo del cobro sin clave, de ventana anti-replay de avisos y de ventana de reintentos.

**Reversión.** Las migraciones son aditivas y su reversión es un `DROP` de las columnas y tablas nuevas, sin pérdida de información relevante. La eliminación de la configuración de proveedor es reversible restaurando la tabla y su semilla, que están en el historial. La exigencia de la clave de idempotencia **no** es reversible sin romper clientes, de modo que se despliega con comunicación previa.

## Open Questions

- **¿Quién es el dueño de la anulación de comprobantes: el hub o el servicio de facturación?** La decisión actual es que el hub la ejecuta, porque tiene la configuración del proveedor y porque el servicio de facturación no existe. La pregunta queda abierta para cuando se construya, y su respuesta puede obligar a mover el procedimiento de dos pasos al servicio de facturación, con el hub como simple portador.
- **¿La numeración de comprobantes se reserva en el servicio de facturación o en un servicio de correlativos compartido?** La decisión actual es el servicio de facturación, y el hub verifica. Si el volumen de talleres justificara un servicio dedicado de correlativos, la decisión debería revisarse antes de construirlo.
- **¿Debe el hub ofrecer un modo de consulta del estado de un comprobante que no haya emitido, por ejemplo los emitidos por el servicio de facturación antes de la migración?** La decisión actual es que no, porque el hub sólo conoce lo que él ha emitido. Si el servicio de facturación emite por su cuenta, esa consulta pasa a ser necesaria y este change no la cubre.
