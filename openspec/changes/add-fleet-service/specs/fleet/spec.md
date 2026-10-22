# Spec Delta

## Purpose

Permite que un taller registre, consulte, actualice y dé de baja sus clientes y sus vehículos, con aislamiento estricto por taller derivado del token verificado, y que valide su identidad y sus vehículos contra el `integration-hub` de forma no bloqueante: la validación se sirve con caché e interruptor de circuito, y su caída degrada el registro a un estado pendiente de reintento en lugar de perderlo o de propagar el fallo al operador.

## ADDED Requirements

### Requirement: La API se publica bajo el prefijo que el gateway reenvía

El sistema SHALL publicar su API bajo el prefijo `/api/v1/fleet`, de modo que las peticiones que el gateway reenvía **sin quitar el prefijo** alcancen los endpoints del servicio. El sistema MUST NOT publicar endpoints de clientes o vehículos fuera de ese prefijo, y el gateway MUST NOT alcanzar una ruta del servicio que no exista.

#### Scenario: Alta de cliente a través del gateway

- **WHEN** una petición `POST /api/v1/fleet/customers` llega al servicio por el gateway
- **THEN** el sistema la atiende en ese endpoint y **no** devuelve `404`

#### Scenario: La ruta anterior deja de existir

- **WHEN** una petición `POST /api/v1/customers` llega al servicio
- **THEN** el sistema responde `404`, porque el prefijo publicado es `/api/v1/fleet`

### Requirement: El taller se deriva del token verificado

El sistema SHALL derivar el taller de toda operación **exclusivamente** del token verificado del principal autenticado, y MUST NOT aceptar el taller del cuerpo de la petición ni de una cabecera del cliente como autoridad. Cuando el cliente declare un taller por cabecera, el sistema MUST contrastarlo con el del token y MUST rechazar la petición si no coinciden. El sistema MUST NOT atender ninguna operación de clientes o vehículos sin un token verificado.

#### Scenario: Petición sin token

- **WHEN** una petición sin token llega a `POST /api/v1/fleet/customers`
- **THEN** el sistema la rechaza con `401 Unauthorized` y no registra ningún cliente

#### Scenario: Cabecera de taller que no coincide con el token

- **WHEN** el ADMIN_TALLER del taller A envía un alta con la cabecera `X-Tenant-ID` del taller B y su token del taller A
- **THEN** el sistema rechaza la petición y no escribe ninguna fila en ningún taller

#### Scenario: Cabecera de taller coincidente

- **WHEN** el ADMIN_TALLER del taller A envía un alta con la cabecera `X-Tenant-ID` del taller A y su token del taller A
- **THEN** el sistema registra el cliente en el taller A

### Requirement: Toda operación de clientes y vehículos está acotada al taller del principal

El sistema MUST NOT devolver datos de clientes o vehículos pertenecientes a otro taller, y MUST NOT permitir que una operación de escritura de un taller modifique datos de otro. El sistema MUST NOT ejecutar una operación que necesite un contexto de taller sin tener uno: en ese caso la rechaza de forma explícita en lugar de ejecutarla sin filtrar.

#### Scenario: Lectura de un cliente de otro taller

- **WHEN** el ADMIN_TALLER del taller A solicita el cliente con identificador `c-1` que pertenece al taller B
- **THEN** el sistema no le devuelve el cliente ni ningún dato de su ficha, y registra el intento de acceso cruzado

#### Scenario: Escritura con taller en el cuerpo de la petición

- **WHEN** el ADMIN_TALLER del taller A envía el registro de un cliente incluyendo en el cuerpo el identificador del taller B
- **THEN** el sistema ignora ese identificador, registra el cliente en el taller A y no escribe ninguna fila del taller B

#### Scenario: Consulta sin contexto de taller

- **WHEN** una operación autenticada sobre clientes o vehículos se ejecuta sin un contexto de taller establecido
- **THEN** el sistema la rechaza de forma explícita en lugar de ejecutarla sin filtrar

### Requirement: El aislamiento en base de datos es efectivo

El sistema SHALL aplicar la Row-Level Security sobre las tablas de clientes y vehículos, y SHALL **forzarla** para que se aplique también al propietario de las tablas. La conexión de la aplicación SHALL usar un rol que no sea propietario de las tablas, y el contexto de taller SHALL establecerse dentro de la transacción activa. El sistema MUST NOT ejecutar la migración de esquema saltándose las migraciones ya existentes.

#### Scenario: La aplicación conecta como propietario de las tablas

- **WHEN** la aplicación consulta las filas de clientes sin contexto de taller
- **THEN** el sistema no devuelve ninguna fila, porque la política se aplica también al propietario

#### Scenario: Base de datos con esquema previo

- **WHEN** el servicio arranca contra una base de datos que ya tiene tablas pero no tiene el historial de migraciones
- **THEN** el sistema aplica las migraciones pendientes y las políticas de aislamiento quedan creadas

### Requirement: El documento y la placa son únicos dentro de un taller

El sistema SHALL impedir que un taller registre dos clientes con el mismo tipo y número de documento, y SHALL impedir que registre dos vehículos con la misma placa. La unicidad SHALL evaluarse siempre dentro del taller, de modo que el mismo documento en otro taller sea un registro distinto y admisible.

#### Scenario: Documento repetido en el mismo taller

- **WHEN** el ADMIN_TALLER del taller A registra un cliente con un documento que ya existe en el taller A
- **THEN** el sistema rechaza el registro por conflicto de documento y no crea un segundo cliente

#### Scenario: Mismo documento en otro taller

- **WHEN** el ADMIN_TALLER del taller B registra un cliente con el mismo documento que ya existe en el taller A
- **THEN** el sistema admite el registro, porque la unicidad es por taller y no global

#### Scenario: Placa repetida en el mismo taller

- **WHEN** el ADMIN_TALLER del taller A registra un vehículo con una placa que ya existe en el taller A
- **THEN** el sistema rechaza el registro por conflicto de placa y no crea un segundo vehículo

### Requirement: El registro del cliente y del vehículo no depende de la validación externa

El sistema SHALL confirmar el registro de un cliente y de un vehículo con estado de validación pendiente, y SHALL devolverlo al operador como registrado. El sistema MUST NOT bloquear el registro mientras la validación externa no se haya completado, y MUST NOT descartar el registro ni devolver un error al operador cuando la validación externa no esté disponible.

#### Scenario: Registro con el proveedor externo caído

- **WHEN** el ADMIN_TALLER del taller A registra un cliente y el `integration-hub` no responde
- **THEN** el sistema confirma el registro con estado de validación pendiente, responde con éxito y no pierde el cliente

#### Scenario: Registro con el proveedor externo disponible

- **WHEN** el ADMIN_TALLER del taller A registra un cliente y el `integration-hub` responde
- **THEN** el sistema confirma el registro con estado de validación pendiente y solicita la validación después de confirmar, sin bloquear la respuesta

### Requirement: La validación de identidad se consulta al hub y su resultado se refleja en el registro

El sistema SHALL consultar la validación de identidad del cliente al `integration-hub` y SHALL reflejar su resultado en el estado de validación del registro: válido cuando el hub lo declare válido, y observado cuando el hub informe una discrepancia. El sistema MUST NOT declarar un cliente válido sin que el hub lo haya declarado válido, y MUST NOT diferenciar el estado de validación por el resultado de un **intento** fallido.

#### Scenario: RUC declarado válido por el hub

- **WHEN** el `integration-hub` declara válido el RUC consultado del cliente
- **THEN** el sistema actualiza el estado de validación del cliente a válido y guarda los datos de identidad devueltos

#### Scenario: RUC observado por el hub

- **WHEN** el `integration-hub` informa una discrepancia en el RUC consultado del cliente
- **THEN** el sistema actualiza el estado de validación del cliente a observado, conservando el registro original

### Requirement: La validación de vehículo se consulta al hub y su resultado se refleja en el registro

El sistema SHALL consultar la validación de vehículo al `integration-hub` y SHALL reflejar su resultado en el estado de validación del vehículo, incluyendo el VIN y las características del vehículo cuando el hub las informe. El sistema MUST NOT declarar un vehículo válido sin que el hub lo haya declarado válido.

#### Scenario: Placa declarada válida por el hub

- **WHEN** el `integration-hub` declara válida la placa consultada del vehículo
- **THEN** el sistema actualiza el estado de validación del vehículo a válido y guarda el VIN y las características devueltas

#### Scenario: Vehículo sin datos de placa verificables

- **WHEN** el `integration-hub` no reconoce la placa consultada del vehículo
- **THEN** el sistema marca el vehículo como observado y no lo declara válido

### Requirement: La caída del hub degrada la validación a pendiente y se reintenta

El sistema SHALL cachear el resultado de una validación correcta: un RUC durante treinta días y una placa durante siete días. El sistema SHALL mantener un interruptor de circuito frente al `integration-hub`, y cuando el circuito esté abierto o la consulta falle, SHALL dejar el registro en estado de validación pendiente, SHALL reprogramar la validación para un reintento posterior y MUST NOT propagar el fallo al operador.

#### Scenario: Circuito abierto por fallos repetidos

- **WHEN** las consultas al `integration-hub` han fallado suficientes veces como para abrir el circuito y un operador registra un vehículo
- **THEN** el sistema registra el vehículo en estado pendiente, no llama al hub, no devuelve error al operador y programa el reintento

#### Scenario: Validación repetida dentro de la ventana de caché

- **WHEN** se consulta dos veces el mismo RUC dentro de la ventana de caché y el hub no es invocado
- **THEN** el sistema devuelve el resultado cacheado en ambas consultas

#### Scenario: Reintento programado por taller

- **WHEN** hay varios registros pendientes de un taller y el barrido de revalidación se ejecuta
- **THEN** el sistema revalida un número acotado de registros de ese taller, sin consumir la cuota de validación de los demás talleres

### Requirement: La validación pendiente se puede reintentar a demanda

El sistema SHALL ofrecer una operación para reintentar la validación de un registro pendiente. El sistema MUST NOT reenviar al hub una validación que ya esté en estado válido u observado, y MUST NOT registrar un intento de validación como correcto si el hub no ha sido contactado.

#### Scenario: Reintento de un registro pendiente

- **WHEN** un operador solicita el reintento de la validación de un cliente en estado pendiente
- **THEN** el sistema consulta al hub y actualiza el estado del cliente con el resultado obtenido

#### Scenario: Reintento de un registro ya validado

- **WHEN** un operador solicita el reintento de la validación de un cliente que ya está en estado válido
- **THEN** el sistema no vuelve a contactar al hub y responde que no hay nada pendiente de validar

### Requirement: Cada endpoint declara el rol que exige

El sistema SHALL declarar en cada endpoint el rol que exige, y SHALL rechazar con `403 Forbidden` a un principal autenticado cuyo rol no esté declarado en ese endpoint. La escritura de clientes y vehículos SHALL exigir un rol de taller con capacidad de escritura, y la consulta SHALL admitir también el rol de solo lectura.

#### Scenario: Escritura con rol de solo lectura

- **WHEN** el MECANICO del taller A intenta registrar un vehículo
- **THEN** el sistema responde `403 Forbidden` y no registra el vehículo

#### Scenario: Consulta con rol autorizado

- **WHEN** el MECANICO del taller A consulta los vehículos de su taller
- **THEN** el sistema devuelve los vehículos del taller A

#### Scenario: Escritura con rol autorizado

- **WHEN** el ADMIN_TALLER del taller A registra un vehículo
- **THEN** el sistema lo registra y responde con éxito

### Requirement: Los datos del cliente y del vehículo se actualizan y se dan de baja sin perderlos

El sistema SHALL permitir actualizar los datos de contacto de un cliente y los datos descriptivos de un vehículo, y SHALL permitir dar de baja un cliente de forma **lógica**, conservando el registro. El sistema MUST NOT dar de baja un cliente que tenga vehículos asociados. La baja de un cliente SHALL quedar visible en las consultas del taller.

#### Scenario: Actualización de los datos de contacto

- **WHEN** el ADMIN_TALLER del taller A actualiza el teléfono de un cliente de su taller
- **THEN** el sistema guarda el nuevo teléfono y el cliente queda consultable con él

#### Scenario: Baja lógica de un cliente sin vehículos

- **WHEN** el ADMIN_TALLER del taller A da de baja un cliente de su taller que no tiene vehículos asociados
- **THEN** el cliente queda marcado como inactivo y su registro **no** se borra

#### Scenario: Baja de un cliente con vehículos

- **WHEN** el ADMIN_TALLER del taller A intenta dar de baja un cliente que tiene vehículos asociados
- **THEN** el sistema rechaza la baja y el cliente sigue activo

### Requirement: Los errores llevan un contrato estable y el taller del error

El sistema SHALL responder con `ProblemDetail` (RFC 9457) y SHALL incluir un código de error estable por familia, legible por máquina. El error SHALL incluir el identificador del taller para que el frontend pueda mostrarlo sin adivinar, y MUST NOT incluir secretos, tokens ni las credenciales del cliente validado. El sistema SHALL registrar la excepción capturada, con su traza, en lugar de imprimirla por la salida estándar.

#### Scenario: Conflicto de documento

- **WHEN** el ADMIN_TALLER del taller A registra un cliente con un documento ya existente en su taller
- **THEN** el sistema responde con un error de conflicto que incluye el código estable de la familia y el identificador del taller

#### Scenario: Error interno

- **WHEN** una operación falla con una excepción no prevista
- **THEN** el sistema registra la excepción con su traza y responde con un error genérico que no expone la clase de la excepción ni su mensaje

### Requirement: La salud y la disponibilidad se comprueban de verdad

El sistema SHALL exponer su estado de salud y de disponibilidad, y la comprobación de disponibilidad SHALL verificar el acceso real a su base de datos y a su caché, ejecutando una consulta y cerrando la conexión. El sistema MUST NOT informar de disponibilidad cuando una comprobación real falle.

#### Scenario: Base de datos inaccesible

- **WHEN** la base de datos no responde y se consulta la disponibilidad del servicio
- **THEN** el servicio informa de que no está disponible, con el detalle de la comprobación fallida

### Requirement: Los eventos de seguridad se registran con esquema uniforme y sin datos sensibles

El sistema SHALL registrar los eventos de seguridad de su dominio con un esquema uniforme que incluya evento, taller, usuario, traza, resultado y marca de tiempo. El esquema MUST NOT admitir contraseñas, tokens ni secretos, de modo que no puedan colarse por descuido, y el sistema SHALL registrar los accesos denegados por rol y por taller.

#### Scenario: Acceso denegado por rol

- **WHEN** el MECANICO del taller A intenta una escritura y el sistema se lo deniega
- **THEN** el sistema registra el evento con el rol que invoca, el taller y el resultado denegado, sin incluir credenciales
