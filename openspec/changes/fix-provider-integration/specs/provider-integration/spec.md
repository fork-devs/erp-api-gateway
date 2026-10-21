# Spec Delta

## Purpose

Garantiza que toda operación con un proveedor externo se ejecuta a lo sumo una vez por clave de idempotencia, de que su resultado es veraz, de que un fallo transitorio no se convierte en un fallo definitivo, y de que el estado de un comprobante refleja con fidelidad lo que el proveedor informó, incluida la distinguibilidad entre un rechazo recuperable y uno definitivo.

## ADDED Requirements

### Requirement: La clave de idempotencia es obligatoria en las operaciones de cobro

El sistema SHALL exigir una clave de idempotencia explícita en toda operación de cobro, y MUST NOT derivarla de los datos de la operación. El sistema MUST rechazar la operación antes de contactar al proveedor cuando la clave no se reciba. El sistema MUST NOT almacenar la tarjeta ni ningún dato de instrumentación de pago.

#### Scenario: Cobro sin clave de idempotencia

- **WHEN** un cliente envía una solicitud de cobro sin la cabecera de clave de idempotencia
- **THEN** el sistema la rechaza antes de contactar al proveedor y no se crea ningún cobro

#### Scenario: Cobro con clave de idempotencia

- **WHEN** un cliente envía una solicitud de cobro con la cabecera de clave de idempotencia
- **THEN** el sistema cobra una sola vez y devuelve el resultado del cobro

### Requirement: La operación se ejecuta a lo sumo una vez por clave

El sistema SHALL reclamar la clave de idempotencia antes de contactar al proveedor, de modo que dos peticiones concurrentes con la misma clave no puedan ejecutar la operación dos veces. El sistema MUST NOT devolver un resultado de operación a una petición cuya clave ya está siendo ejecutada por otra.

#### Scenario: Dos peticiones concurrentes con la misma clave

- **WHEN** dos peticiones con la misma clave de idempotencia llegan a la vez
- **THEN** el proveedor recibe una sola llamada, y la segunda petición recibe el resultado de la primera una vez completada

#### Scenario: Repetición posterior de una operación ya ejecutada

- **WHEN** un cliente repite una operación con una clave de idempotencia ya utilizada
- **THEN** el sistema devuelve el resultado almacenado de la primera ejecución y no vuelve a contactar al proveedor

#### Scenario: Misma clave con carga útil distinta

- **WHEN** un cliente envía una operación con una clave de idempotencia ya utilizada pero con una carga útil distinta
- **THEN** el sistema rechaza la petición por conflicto de clave y no devuelve el resultado de la operación anterior

### Requirement: Un fallo transitorio no se convierte en un fallo definitivo

Cuando una operación con el proveedor falle de forma transitoria, el sistema MUST NOT almacenar ese fallo como resultado definitivo de la clave. El sistema SHALL reprogramar la operación para un reintento posterior, y SHALL liberar la clave de idempotencia para que un reintento con la misma clave pueda ejecutarse de nuevo.

#### Scenario: Fallo transitorio del proveedor

- **WHEN** el proveedor rechaza temporalmente una operación de cobro
- **THEN** el sistema reprograma la operación para un reintento posterior, y una petición posterior con la misma clave vuelve a intentar el cobro en lugar de devolver el fallo como resultado definitivo

#### Scenario: Fallo definitivo del proveedor

- **WHEN** el proveedor rechaza la operación por una causa que no admite reintento
- **THEN** el sistema devuelve el rechazo al cliente y no reprograma la operación

### Requirement: El resultado declarado es el resultado real

El sistema SHALL declarar una operación aceptada por el proveedor únicamente cuando el proveedor la haya aceptado. El sistema MUST NOT fabricar respuestas de proveedores en un entorno que no sea de desarrollo, y MUST NOT registrar un intento como correcto cuando no se haya producido una llamada al proveedor. El arranque en un entorno que no sea de desarrollo con el modo de prueba de un proveedor activo SHALL fallar de forma explícita.

#### Scenario: Modo de prueba activo fuera del entorno de desarrollo

- **WHEN** el servicio arranca en un entorno que no sea de desarrollo con el modo de prueba de un proveedor activo
- **THEN** el arranque falla de forma explícita y el servicio no acepta peticiones

#### Scenario: Operación aceptada por el proveedor

- **WHEN** el proveedor acepta una operación
- **THEN** el sistema declara el resultado aceptado y registra el intento con el número de intento y el estado reales

#### Scenario: Operación que falla antes de contactar al proveedor

- **WHEN** una operación falla antes de llegar al proveedor
- **THEN** el sistema no registra ningún intento correcto de esa operación

### Requirement: El estado del comprobante distingue el rechazo recuperable del definitivo

El sistema SHALL representar el estado de un comprobante con un conjunto de estados explícitos que distinga entre el rechazo recuperable y el rechazo definitivo. El sistema MUST NOT informar de un rechazo recuperable como si fuera definitivo, y MUST conservar el código de respuesta que el proveedor informó.

#### Scenario: Comprobante observado por el proveedor

- **WHEN** el proveedor informa de un comprobante en estado de observación
- **THEN** el sistema informa de ese estado, que es distinguible del rechazo definitivo, y conserva el código de respuesta del proveedor

#### Scenario: Comprobante rechazado definitivamente

- **WHEN** el proveedor informa de un rechazo definitivo
- **THEN** el sistema informa del rechazo definitivo con el código de respuesta del proveedor

### Requirement: La anulación sigue el procedimiento del proveedor

El sistema SHALL anular un comprobante mediante el procedimiento que el proveedor exige, obteniendo el ticket de comunicación previo cuando el proveedor lo requiere y referenciándolo en la anulación. El sistema MUST NOT enviar una anulación con una carga útil que el proveedor rechaza por no cumplir su procedimiento. Una anulación rechazada de forma transitoria por el proveedor SHALL reprogramarse para un reintento.

#### Scenario: Anulación de un comprobante

- **WHEN** el sistema anula un comprobante
- **THEN** obtiene el ticket de comunicación que el proveedor exige y lo referencia en la solicitud de anulación, y el proveedor acepta la operación

#### Scenario: Anulación rechazada de forma transitoria

- **WHEN** el proveedor rechaza de forma transitoria una anulación
- **THEN** el sistema reprograma la anulación para un reintento posterior en lugar de devolver el rechazo como resultado definitivo

### Requirement: La numeración de comprobantes es única por taller

El sistema MUST verificar que la combinación de taller, serie y número no haya sido utilizada antes de emitir un comprobante, y MUST rechazar la emisión cuando la combinación ya exista. La asignación de la numeración corresponde al servicio de facturación, y el hub actúa como verificador de esa unicidad.

#### Scenario: Emisión con numeración ya utilizada

- **WHEN** se emite un comprobante con una serie y un número que ya constan en el mismo taller
- **THEN** el sistema rechaza la emisión y no contacta al proveedor

#### Scenario: Emisión con numeración libre

- **WHEN** se emite un comprobante con una serie y un número que no constan en el taller
- **THEN** el sistema emite el comprobante y lo registra con esa numeración

### Requirement: El reintento alcanza la ventana de recuperación del proveedor

El sistema SHALL reprogramar una operación fallida con una espera creciente, y la ventana total de reintentos SHALL alcanzar el plazo de recuperación previsto por el proveedor. El número máximo de intentos y la ventana SHALL ser configurables. El sistema SHALL registrar el número de intento real de cada ejecución.

#### Scenario: Reintentos sucesivos

- **WHEN** una operación falla de forma repetida
- **THEN** las esperas entre reintentos crecen, la ventana total alcanza el plazo de recuperación previsto, y el número de intento de cada ejecución refleja la progresión real

#### Scenario: Número de intento registrado

- **WHEN** se consulta el histórico de intentos de una operación
- **THEN** cada ejecución aparece con su número de intento real y su estado

### Requirement: La cola de muertos es consultable y reactivable

El sistema SHALL mantener en una cola de muertos las operaciones que agotan sus intentos, con su último error y su número de intentos. El sistema SHALL permitir consultar esa cola y SHALL permitir reactivar una operación de ella, de modo que vuelva a programarse con su contador de intentos restablecido. El sistema MUST NOT descartar la operación agotada.

#### Scenario: Consulta de la cola de muertos

- **WHEN** se consulta la cola de operaciones agotadas
- **THEN** devuelve las operaciones agotadas con su último error y su número de intentos, y no incluye las que aún tienen intentos disponibles

#### Scenario: Reactivación de una operación agotada

- **WHEN** se reactiva una operación de la cola de muertos
- **THEN** el sistema la devuelve al estado pendiente, restablece su contador de intentos y vuelve a programarla

#### Scenario: Registro del agotamiento

- **WHEN** una operación agota sus intentos
- **THEN** el agotamiento queda registrado en el histórico de intentos con su estado correspondiente

### Requirement: El procesamiento de avisos es idempotente y resiste la reentrega

El sistema SHALL registrar cada aviso procesado con un identificador de evento único, y MUST NOT aplicar dos veces el efecto de un aviso ya procesado. El sistema SHALL escribir la actualización de estado bajo la misma clave de idempotencia de la operación original. El sistema SHALL comprobar la marca de tiempo del aviso en todas las rutas de firma, y MUST rechazar un aviso cuya marca de tiempo esté fuera de la ventana tolerada.

#### Scenario: Reentrega del mismo aviso

- **WHEN** el mismo aviso se entrega dos veces
- **THEN** el efecto se aplica una sola vez y la segunda entrega se detecta como duplicada

#### Scenario: Aviso fuera de la ventana temporal

- **WHEN** llega un aviso firmado cuya marca de tiempo está fuera de la ventana tolerada
- **THEN** el sistema lo rechaza y no aplica ningún efecto

#### Scenario: Aviso que actualiza el estado de una operación

- **WHEN** un aviso informa del resultado de un cobro o de una emisión
- **THEN** el estado queda escrito bajo la misma clave de idempotencia de la operación original, y la reproducción de esa operación devuelve el estado actualizado

### Requirement: El secreto de firma de avisos es propio y no es la credencial del proveedor

El sistema SHALL utilizar una clave de firma de avisos distinta de la credencial de acceso al proveedor, y MUST NOT derivar una de la otra. El sistema MUST NOT aceptar un aviso firmado con la credencial de acceso del proveedor.

#### Scenario: Aviso firmado con la credencial del proveedor

- **WHEN** un atacante firma un aviso con la credencial de acceso del proveedor en lugar de con el secreto de avisos
- **THEN** el sistema rechaza el aviso

### Requirement: El servicio interno tiene identidad y privilegios acotados

Cada servicio que consume el hub de manera interna SHALL tener **identidad propia**, y sus privilegios SHALL limitarse a las operaciones que necesita. El sistema MUST NOT conceder a un servicio interno privilegios de administrador de plataforma. El sistema MUST verificar que el servicio que llama tiene el privilegio requerido para la operación.

#### Scenario: Servicio interno sin privilegio de administrador

- **WHEN** un servicio interno autenticado invoca una operación de administración de plataforma
- **THEN** el sistema la rechaza por falta de privilegio, y ese servicio puede operar únicamente sobre las operaciones que su rol habilita

#### Scenario: Servicio interno con el privilegio requerido

- **WHEN** un servicio interno autenticado con el rol requerido invoca una operación de administración
- **THEN** el sistema la ejecuta y registra quién la ejecutó

### Requirement: Las consultas de identidad están limitadas en frecuencia

El sistema SHALL limitar la frecuencia de las consultas de identidad por taller, de modo que un taller no pueda agotar la cuota del proveedor con consultas repetidas. El sistema MUST informar al cliente cuándo puede reintentar cuando el límite se alcance.

#### Scenario: Consulta por encima del límite

- **WHEN** un taller supera el límite de consultas de identidad en su ventana
- **THEN** el sistema rechaza la consulta e informa al cliente del tiempo que debe esperar antes de reintentar

#### Scenario: Consulta dentro del límite

- **WHEN** un taller realiza una consulta de identidad dentro de su límite
- **THEN** el sistema la atiende con normalidad
