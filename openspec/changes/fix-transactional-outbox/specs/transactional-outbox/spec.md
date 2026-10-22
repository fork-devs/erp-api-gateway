# Spec Delta

## Purpose

Garantiza que un cambio de negocio y el evento de dominio que lo describe se confirman o se descartan juntos, y que la publicación de ese evento al bus nunca se pierde, nunca se duplica por un fallo de infraestructura y nunca bloquea el despacho de otros mensajes.

## ADDED Requirements

### Requirement: El cambio de negocio y su evento se confirman juntos

Cuando una operación de negocio produzca un evento de dominio, el sistema SHALL escribir el cambio de negocio y el registro del evento en la misma transacción de base de datos. El sistema MUST NOT confirmar el cambio de negocio si la escritura del evento falla, y MUST NOT dejar un registro de evento sin el cambio de negocio que lo justifica.

#### Scenario: El alta de un taller con su evento de dominio

- **WHEN** se da de alta un taller en el servicio de tenants
- **THEN** el registro del taller y el registro de su evento de dominio se confirman en la misma transacción, de modo que tras la operación existe exactamente un taller y exactamente un evento pendiente de publicar

#### Scenario: Fallo al escribir el evento de dominio

- **WHEN** la escritura del registro del evento de dominio falla durante el alta de un taller
- **THEN** la operación se revierte por completo y no queda ningún taller creado ni ningún registro de evento huérfano

#### Scenario: El cambio de plan y su evento de dominio

- **WHEN** se cambia el plan de un taller
- **THEN** la actualización del plan y el registro de su evento de dominio se confirman en la misma transacción

#### Scenario: La suspensión y su evento de dominio

- **WHEN** se suspende un taller
- **THEN** el cambio de estado y el registro de su evento de dominio se confirman en la misma transacción

### Requirement: La demarcación transaccional reside en la capa de aplicación

El sistema SHALL expresar el límite transaccional de una operación de negocio en la capa de aplicación, mediante una abstracción propia del dominio, y los adaptadores de persistencia MUST NOT abrir su propia transacción. Los adaptadores MUST participar de la transacción abierta por la capa de aplicación. El sistema MUST NOT depender de que un adaptador se abra en una transacción propia cuando la operación requiere atomicidad.

#### Scenario: Una operación que escribe en dos tablas

- **WHEN** una operación de negocio escribe en dos tablas de persistencia
- **THEN** ambas escrituras se confirman o se revierten juntas, y el adaptador de persistencia no abre una transacción independiente

#### Scenario: Un adaptador invocado sin transacción abierta

- **WHEN** se invoca un adaptador de persistencia fuera de una demarcación transaccional
- **THEN** la operación falla de forma explícita en lugar de confirmarse en una transacción implícita

### Requirement: Un mensaje fallido no bloquea el despacho de los demás

El sistema SHALL continuar despachando el resto de la cola cuando la publicación de un mensaje falle. El sistema MUST NOT detener el procesamiento del lote por el fallo de un único mensaje.

#### Scenario: Mensaje envenenado seguido de un mensaje válido

- **WHEN** el primer mensaje de un lote falla al publicarse y el segundo es válido
- **THEN** el segundo mensaje se publica en el mismo ciclo de despacho y el primero queda reprogramado para un reintento posterior

#### Scenario: Todos los mensajes del lote fallan

- **WHEN** todos los mensajes de un lote fallan al publicarse
- **THEN** cada uno queda reprogramado con su propio contador de intentos y su propia espera, y ninguno se pierde

### Requirement: Los intentos fallidos de publicación quedan registrados

El sistema SHALL registrar cada intento fallido de publicación con su error, su número de intento y la marca de tiempo, y SHALL exponer esa información para su consulta. El sistema MUST NOT descartar el error de publicación sin registrarlo.

#### Scenario: Publicación fallida

- **WHEN** la publicación de un mensaje de la bandeja de salida falla
- **THEN** el sistema registra el error, incrementa el contador de intentos del mensaje y reprograma su publicación futura

#### Scenario: Consulta del histórico de intentos

- **WHEN** se consulta el estado de un mensaje que ya ha fallado al publicarse
- **THEN** la consulta devuelve el número de intentos realizados, el último error registrado y la marca de tiempo del último intento

### Requirement: El reintento se.programa con espera creciente

El sistema SHALL reprogramar la publicación de un mensaje fallido con una espera que crezca con el número de intento, hasta alcanzar un máximo configurable. El sistema MUST NOT reintentar de forma inmediata e ilimitada.

#### Scenario: Primer fallo

- **WHEN** un mensaje falla al publicarse por primera vez
- **THEN** su próxima publicación se programa con la espera del primer intervalo

#### Scenario: Intentos sucesivos

- **WHEN** un mensaje lleva varios intentos fallidos registrados
- **THEN** la espera hasta su próxima publicación es mayor que la del intento anterior, y no supera el máximo configurado

### Requirement: El mensaje agotado queda en cola de muertos y es recuperable

Cuando un mensaje haya agotado el número máximo de intentos, el sistema SHALL pasarlo a un estado de cola de muertos y MUST NOT reintentarlo automáticamente. El sistema SHALL permitir consultar los mensajes en cola de muertos y SHALL permitir reactivarlos para un nuevo intento. El sistema MUST NOT descartar ni eliminar el mensaje agotado.

#### Scenario: Agotamiento de intentos

- **WHEN** un mensaje alcanza el número máximo de intentos sin publicarse
- **THEN** el sistema lo marca como agotado, deja de reprogramarlo y lo conserva en la cola

#### Scenario: Consulta de la cola de muertos

- **WHEN** se consulta la cola de mensajes agotados
- **THEN** devuelve los mensajes agotados con su número de intentos y su último error, y no incluye los mensajes que aún tienen intentos disponibles

#### Scenario: Reactivación de un mensaje agotado

- **WHEN** se reactiva un mensaje de la cola de muertos
- **THEN** el sistema lo devuelve al estado pendiente, restablece su contador de intentos y vuelve a programarlo para publicación

### Requirement: El evento es identificable e idempotente por su consumidor

El sistema SHALL almacenar el identificador del evento en un campo propio del registro de la bandeja de salida, además de dentro del contenido del mensaje, de modo que un consumidor pueda deduplicar por evento. El sistema MUST NOT generar un identificador de evento nuevo en cada reintento de publicación del mismo mensaje.

#### Scenario: Reintento de publicación del mismo mensaje

- **WHEN** un mensaje se republica tras un fallo
- **THEN** el identificador del evento que acompaña al mensaje es el mismo que llevaba en sus intentos anteriores

#### Scenario: Deduplicación por el consumidor

- **WHEN** un consumidor recibe dos veces el mismo evento
- **THEN** el identificador del evento le permite detectar la duplicación y no aplicar el efecto dos veces

### Requirement: La publicación no se duplica entre réplicas del relay

El sistema SHALL reclamar de forma exclusiva cada mensaje pendiente de publicación, de modo que dos instancias del relay que se ejecuten a la vez no publiquen el mismo mensaje.

#### Scenario: Dos instancias del relay concurrentes

- **WHEN** dos instancias del relay procesan la cola pendiente de publicación al mismo tiempo
- **THEN** cada mensaje se publica exactamente una vez, y ninguna instancia publica un mensaje ya reclamado por la otra

### Requirement: La publicación ocurre fuera de la transacción que origina el evento

El sistema SHALL publicar los eventos de dominio desde una fase separada de la transacción que confirma el cambio de negocio, de modo que la publicación nunca forme parte de la transacción de escritura. El sistema MUST NOT mantener abierta la transacción de negocio mientras se publica en el bus.

#### Scenario: Confirmación y publicación son fases distintas

- **WHEN** un cambio de negocio se confirma y su evento se publica después
- **THEN** la publicación se ejecuta con la transacción del cambio de negocio ya cerrada, y su fallo no altera el resultado de la transacción de negocio
