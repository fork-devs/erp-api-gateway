# Spec Delta

## Purpose

Permite que un taller gestione su catálogo de repuestos, su stock por sucursal y las reservas que consume la saga de órdenes de trabajo, con aislamiento estricto por taller y con un saldo calculado **por lotes, en orden FIFO**, de modo que el taller sabe cuánto stock tiene y cuánto vale.

## ADDED Requirements

### Requirement: La API exige un token verificado y cada endpoint declara el rol que exige

El sistema SHALL rechazar con `401` toda petición de clientes, repuestos o stock que no traiga un token de acceso verificado. Cada endpoint SHALL declarar el rol que exige, y SHALL rechazar con `403` a un principal autenticado cuyo rol no esté declarado en él. La escritura de stock SHALL exigir un rol con capacidad de escritura, y la consulta SHALL admitir también el rol de solo lectura.

#### Scenario: Petición sin token

- **WHEN** el ADMIN_TALLER del taller A envía una petición sin token de acceso
- **THEN** el sistema la rechaza con `401 Unauthorized` y no registra ningún movimiento de stock

#### Scenario: Rol sin permiso de escritura

- **WHEN** el MECANICO del taller A intenta reservar stock
- **THEN** el sistema responde `403 Forbidden` y no reserva nada

#### Scenario: Rol con permiso de escritura

- **WHEN** el ADMIN_TALLER del taller A reserva stock con una clave de idempotencia
- **THEN** el sistema reserva el stock y responde con éxito

### Requirement: El taller se deriva del token verificado

El sistema SHALL derivar el taller de toda operación **exclusivamente** del claim `tenant_id` de un token verificado, y MUST NOT aceptar el taller del cuerpo de la petición ni de una cabecera del cliente como autoridad. Cuando el cliente declare un taller por cabecera, el sistema MUST contrastarlo con el del token y MUST rechazar la petición si no coinciden.

#### Scenario: Cabecera que no coincide con el token

- **WHEN** el ADMIN_TALLER del taller A envía una reserva con la cabecera `X-Tenant-ID` del taller B
- **THEN** el sistema rechaza la petición y no registra ningún movimiento

#### Scenario: Cabecera que coincide con el token

- **WHEN** el ADMIN_TALLER del taller A envía una reserva con la cabecera `X-Tenant-ID` del taller A
- **THEN** el sistema atiende la reserva

### Requirement: Toda operación de stock está acotada al taller del principal

El sistema MUST NOT devolver datos de stock, repuestos ni sucursales pertenecientes a otro taller, y MUST NOT permitir que una escritura de un taller modifique datos de otro. El sistema MUST NOT ejecutar una operación que necesite un contexto de taller sin tener uno: en ese caso la rechaza de forma explícita en lugar de ejecutarla sin filtrar.

#### Scenario: Lectura del saldo de otro taller

- **WHEN** el ADMIN_TALLER del taller A consulta el saldo de un repuesto del taller B
- **THEN** el sistema no le devuelve ningún dato del taller B y registra el intento de acceso cruzado

#### Scenario: Consulta sin contexto de taller

- **WHEN** una operación autenticada sobre stock se ejecuta sin un contexto de taller establecido
- **THEN** el sistema la rechaza de forma explícita en lugar de ejecutarla sin filtrar

### Requirement: El aislamiento en base de datos es efectivo

El sistema SHALL aplicar la Row-Level Security sobre las tablas de sucursales, repuestos, movimientos y lotes, y SHALL **forzarla** para que se aplique también al propietario de las tablas. La conexión de la aplicación SHALL usar un rol que no sea propietario de las tablas, y el contexto de taller SHALL establecerse dentro de la transacción activa. El sistema MUST NOT ejecutar la migración de esquema saltándose las migraciones ya existentes.

#### Scenario: La aplicación conecta como propietario de las tablas

- **WHEN** la aplicación consulta los movimientos de stock sin contexto de taller
- **THEN** el sistema no devuelve ninguna fila, porque la política se aplica también al propietario

#### Scenario: Base de datos con esquema previo

- **WHEN** el servicio arranca contra una base de datos que ya tiene tablas pero no tiene el historial de migraciones
- **THEN** el sistema aplica las migraciones pendientes y las políticas de aislamiento quedan creadas

### Requirement: El catálogo de repuestos es único por taller

El sistema SHALL impedir que un taller registre dos repuestos con el mismo SKU. La unicidad SHALL evaluarse dentro del taller, de modo que el mismo SKU en otro taller sea un repuesto distinto y admisible. El sistema SHALL permitir al taller registrar sus sucursales, y cada movimiento de stock SHALL pertenecer a una sucursal de ese mismo taller.

#### Scenario: SKU repetido en el mismo taller

- **WHEN** el ADMIN_TALLER del taller A registra un repuesto con un SKU que ya existe en el taller A
- **THEN** el sistema rechaza el registro por conflicto de SKU y no crea un segundo repuesto

#### Scenario: Mismo SKU en otro taller

- **WHEN** el ADMIN_TALLER del taller B registra un repuesto con el mismo SKU que ya existe en el taller A
- **THEN** el sistema admite el registro, porque la unicidad es por taller

### Requirement: La reserva se reclama antes de registrar el movimiento

El sistema SHALL reclamar la clave de idempotencia **antes** de registrar el movimiento de reserva, de modo que dos peticiones concurrentes con la misma clave no puedan registrar dos movimientos. El sistema MUST NOT liberar la clave cuando el movimiento queda registrado, y MUST NOT devolver un resultado de una reserva ajena.

#### Scenario: Dos peticiones concurrentes con la misma clave

- **WHEN** dos peticiones con la misma clave de idempotencia llegan a la vez
- **THEN** el sistema registra **un solo** movimiento de reserva y las dos peticiones reciben el mismo resultado

#### Scenario: Repetición posterior de una reserva ya hecha

- **WHEN** una petición repite una reserva con una clave ya utilizada
- **THEN** el sistema devuelve el resultado de la reserva original y no registra un segundo movimiento

### Requirement: La confirmación convierte la reserva en una salida

El sistema SHALL ofrecer una operación que confirme una reserva, y SHALL consumir el stock reservado en la **misma** operación, dejando la reserva sin efecto. La confirmación SHALL ser idempotente: repetirla con la misma clave no puede descontar el stock dos veces. El sistema MUST NOT confirmar una reserva que no pertenece al taller de la petición.

#### Scenario: Confirmación de una reserva pendiente

- **WHEN** el ADMIN_TALLER del taller A confirma una reserva de su taller
- **THEN** el sistema consume el stock reservado y la reserva deja de estar activa

#### Scenario: Confirmación repetida con la misma clave

- **WHEN** el ADMIN_TALLER del taller A repite la confirmación con la misma clave de idempotencia
- **THEN** el sistema no descuenta el stock dos veces y devuelve el resultado de la primera confirmación

#### Scenario: Confirmación de una reserva de otro taller

- **WHEN** el ADMIN_TALLER del taller A confirma una reserva del taller B
- **THEN** el sistema rechaza la operación y no toca el stock del taller B

### Requirement: La liberación devuelve la reserva sin duplicar el efecto

El sistema SHALL ofrecer una operación que libere una reserva, y SHALL devolver el stock reservado al disponible. La liberación SHALL ser idempotente: repetirla con la misma clave no puede aumentar el stock disponible dos veces.

#### Scenario: Liberación de una reserva pendiente

- **WHEN** el ADMIN_TALLER del taller A libera una reserva de su taller
- **THEN** el stock disponible aumenta en la cantidad liberada y la reserva deja de estar activa

#### Scenario: Liberación repetida con la misma clave

- **WHEN** el ADMIN_TALLER del taller A repite la liberación con la misma clave
- **THEN** el stock disponible no aumenta dos veces

### Requirement: El stock disponible nunca puede quedar en negativo

El sistema SHALL calcular el disponible y registrar la reserva **dentro de la misma transacción**, de modo que dos reservas simultáneas no puedan reservar más stock del que existe. El sistema MUST NOT registrar una reserva que deje el disponible en negativo, y MUST NOT confirmar una salida que exceda el stock disponible.

#### Scenario: Dos reservas simultáneas por encima del disponible

- **WHEN** dos peticiones concurrentes intentan reservar en total más unidades de las que hay
- **THEN** el sistema admite como máximo el stock disponible y rechaza el resto, sin dejar el disponible en negativo

#### Scenario: Salida mayor que el disponible

- **WHEN** se registra una salida de una cantidad mayor que el disponible
- **THEN** el sistema rechaza el movimiento y no altera el stock

### Requirement: El saldo se calcula por lotes, en orden FIFO

El sistema SHALL registrar cada entrada de stock como un **lote** con su cantidad, su coste unitario y su fecha, y SHALL consumir los lotes **más antiguos primero** cuando se registra una salida o una reserva. El saldo SHALL informar del coste unitario restante y del **valor total del stock disponible**, y SHALL NOT calcularlo como la suma de los movimientos, que no dice nada del coste de lo consumido.

#### Scenario: Entrada con coste unitario

- **WHEN** el ADMIN_TALLER del taller A registra una entrada de 10 unidades a 25 soles cada una
- **THEN** el sistema abre un lote de 10 unidades con ese coste, y el valor del stock disponible suma 250

#### Scenario: Consumo en orden de llegada

- **WHEN** hay dos lotes, uno de 5 unidades a 10 soles y otro de 5 unidades a 20 soles, y se consumen 7 unidades
- **THEN** el sistema consume primero el lote más antiguo, deja 3 unidades a 10 soles y toma 4 unidades del segundo

#### Scenario: El valor informa del coste que queda

- **WHEN** el ADMIN_TALLER del taller A consulta el saldo de un repuesto
- **THEN** la respuesta incluye el valor del stock disponible, calculado con el coste de los lotes que quedan

### Requirement: La alerta de stock bajo se emite

El sistema SHALL comparar el disponible de cada repuesto con su umbral `min_stock` tras cada movimiento, y SHALL emitir un aviso de stock bajo cuando el disponible quede por debajo del umbral. El aviso SHALL informar del repuesto, la sucursal y el nivel, y MUST NOT emitirse cuando el disponible siga por encima del umbral.

#### Scenario: El disponible cae por debajo del umbral

- **WHEN** una reserva deja el disponible de un repuesto por debajo de su umbral
- **THEN** el sistema emite el aviso de stock bajo con el repuesto, la sucursal y el nivel

#### Scenario: El disponible sigue por encima del umbral

- **WHEN** una reserva deja el disponible por encima del umbral del repuesto
- **THEN** el sistema no emite ningún aviso de stock bajo

### Requirement: Los eventos de stock se publican por bandeja de salida

El sistema SHALL persistir el evento de dominio **junto al cambio de stock** en la misma transacción local, y un relay lo publicará despues, con reintentos y cola de muertos. El sistema SHALL emitir un evento de reserva liberada, de stock consumido y de stock bajo, y el evento SHALL llevar el identificador del taller y el del movimiento. La publicación MUST NOT ocurrir dentro de la transacción que confirma el cambio de stock.

#### Scenario: El evento viaja con el cambio

- **WHEN** el sistema registra una reserva
- **THEN** el evento de reserva queda persistido en la misma transaccion, y se publica despues, no durante

#### Scenario: El relay publica lo pendiente

- **WHEN** hay un evento pendiente de publicacion
- **THEN** el relay lo publica y lo marca como publicado, y un fallo no impide que se publiquen los siguientes

### Requirement: Los errores llevan un contrato estable

El sistema SHALL responder con `ProblemDetail` (RFC 9457) y SHALL incluir un codigo de error estable por familia, legible por maquina. El error SHALL incluir el identificador del taller para que el frontend pueda mostrarlo sin adivinar, y MUST NOT incluir secretos ni las credenciales del cliente. El sistema SHALL registrar la excepcion capturada, con su traza, en lugar de imprimirla por la salida estandar.

#### Scenario: Conflicto de SKU

- **WHEN** el ADMIN_TALLER del taller A registra un repuesto con un SKU ya existente en su taller
- **THEN** el sistema responde con un error de conflicto que incluye el codigo estable de la familia y el identificador del taller

#### Scenario: Error interno

- **WHEN** una operacion falla con una excepcion no prevista
- **THEN** el sistema registra la excepcion con su traza y responde con un error generico que no expone la clase de la excepcion ni su mensaje
