# Spec Delta

## Purpose

Garantiza que sólo quien tiene el rol y ha completado los factores de autenticación exigidos accede a cada operación, de que el estado de una cuenta no puede revelar su existencia a un tercero, y de que todos los eventos de autenticación quedan registrados de forma auditable.

## ADDED Requirements

### Requirement: El segundo factor se puede habilitar y exige un código en el login

Un usuario SHALL poder habilitar el segundo factor de autenticación, y el sistema SHALL registrar en su cuenta que lo tiene habilitado. Cuando el inicio de sesión de un usuario con el segundo factor habilitado no incluye un código válido, el sistema MUST NOT emitir credenciales. El sistema SHALL entregar al usuario el secreto de enrolamiento y un conjunto de códigos de recuperación.

#### Scenario: Habilitación del segundo factor

- **WHEN** un usuario autenticado solicita habilitar el segundo factor de su cuenta
- **THEN** el sistema registra que la cuenta lo tiene habilitado y le entrega un secreto de enrolamiento y un conjunto de códigos de recuperación

#### Scenario: Login sin el código de un segundo factor

- **WHEN** un usuario con el segundo factor habilitado intenta iniciar sesión sin aportar ningún código
- **THEN** el sistema no emite credenciales y responde indicando que se requiere el segundo factor

#### Scenario: Login con un código válido

- **WHEN** un usuario con el segundo factor habilitado inicia sesión aportando un código válido
- **THEN** el sistema emite las credenciales y registra el inicio de sesión

### Requirement: El código de un segundo factor no puede reutilizarse

El sistema SHALL validar el código de un segundo factor con una tolerancia de una ventana temporal, y MUST NOT aceptar dos veces el mismo código dentro de esa ventana. El sistema SHALL invalidar los códigos de recuperación en el momento de su uso.

#### Scenario: Reutilización del mismo código

- **WHEN** un usuario inicia sesión con un código de un segundo factor que ya utilizó en el intervalo tolerated
- **THEN** el sistema rechaza el código y no emite credenciales

#### Scenario: Uso de un código en la ventana siguiente

- **WHEN** un usuario inicia sesión con un código de un segundo factor que ya utilizó, transcurrida la ventana de tolerancia
- **THEN** el sistema acepta el código y emite las credenciales

#### Scenario: Uso de un código de recuperación

- **WHEN** un usuario utiliza uno de sus códigos de recuperación
- **THEN** el sistema acepta el código, lo marca como consumido y emite las credenciales

#### Scenario: Reutilización de un código de recuperación

- **WHEN** un usuario intenta usar de nuevo un código de recuperación ya consumido
- **THEN** el sistema lo rechaza y no emite credenciales

### Requirement: Las operaciones declaran el rol que las habilita

Cada operación sensible SHALL declarar qué roles la habilitan, y el sistema MUST rechazar la operación cuando el principal no ostente ninguno de esos roles. El sistema MUST NOT autorizar una operación de administración de taller a un principal que sólo ostente el rol de mecánico, y MUST NOT autorizar una operación de administración de plataforma a un principal de taller.

#### Scenario: Mecánico que intenta administrar usuarios del taller

- **WHEN** el actor `MECANICO` del taller A invoca el alta de un usuario en el taller A
- **THEN** el sistema rechaza la operación por falta de privilegios y no crea ningún usuario

#### Scenario: Asesor que intenta suspender un taller

- **WHEN** el actor `ASESOR` del taller A invoca la suspensión de un taller
- **THEN** el sistema rechaza la operación por falta de privilegios y el taller conserva su estado

#### Scenario: Administrador de taller que sí tiene el rol

- **WHEN** el actor `ADMIN_TALLER` del taller A invoca el alta de un usuario en el taller A
- **THEN** el sistema ejecuta la operación y devuelve el usuario creado

#### Scenario: El borde comprueba el rol de la ruta

- **WHEN** un principal con un rol insuficiente invoca por el borde una ruta que exige otro rol
- **THEN** el borde rechaza la petición antes de reenviarla al servicio de destino

### Requirement: El correo electrónico es único dentro del taller, no en la plataforma

El sistema SHALL considerar dos cuentas del mismo correo electrónico como distintas si pertenecen a talleres distintos. El sistema MUST NOT impedir el registro de un correo que ya existe en otro taller. El sistema MUST NOT revelar, al responder a un registro, si el correo ya está en uso en otro taller.

#### Scenario: Mismo correo en dos talleres

- **WHEN** un usuario se registra con un correo que ya existe en el taller B, desde el taller A
- **THEN** el registro en el taller A se completa y la cuenta del taller B no se ve afectada

#### Scenario: Registro con correo ya existente en el mismo taller

- **WHEN** un usuario se registra con un correo que ya existe en su propio taller
- **THEN** el sistema rechaza el registro por duplicado en el taller

#### Scenario: El registro no revela la existencia del correo en otro taller

- **WHEN** un usuario intenta registrarse con un correo que ya existe en otro taller
- **THEN** la respuesta del sistema no distingue ese caso del de un correo inexistente

### Requirement: El inicio de sesión exige el taller

El sistema SHALL exigir el identificador del taller en el inicio de sesión, y MUST NOT resolver una cuenta sin él. El sistema MUST NOT emitir un token cuyo taller no coincida con el de la cuenta autenticada.

#### Scenario: Login sin taller

- **WHEN** un usuario intenta iniciar sesión sin indicar el taller
- **THEN** el sistema rechaza la petición y no emite credenciales

#### Scenario: Login con taller ajeno a la cuenta

- **WHEN** un usuario del taller A intenta iniciar sesión indicando el taller B
- **THEN** el sistema rechaza la petición y no emite credenciales

### Requirement: El bloqueo por intentos fallidos es fiable

El sistema SHALL contar los intentos de inicio de sesión fallidos de forma que los intentos concurrentes no se pierdan, y SHALL bloquear la cuenta cuando el número de intentos alcance el umbral configurado. El sistema MUST NOT permitir que intentos concurrentes dejen el contador por debajo del umbral.

#### Scenario: Intentos concurrentes sobre la misma cuenta

- **WHEN** diez intentos de inicio de sesión fallidos llegan a la vez contra la misma cuenta
- **THEN** el contador refleja los diez intentos y la cuenta queda bloqueada

#### Scenario: Un inicio de sesión correcto restablece el contador

- **WHEN** un usuario inicia sesión correctamente tras dos intentos fallidos
- **THEN** el contador de intentos fallidos de esa cuenta vuelve a cero

### Requirement: El inicio de sesión no revela si la cuenta existe

El sistema SHALL responder al inicio de sesión con el mismo código y un tiempo de respuesta indistinguible con independencia de que la cuenta exista, de que la contraseña sea incorrecta o de que la cuenta esté bloqueada. El sistema MUST NOT devolver un mensaje que distinga esas situaciones.

#### Scenario: Cuenta inexistente

- **WHEN** un cliente intenta iniciar sesión con un correo que no está registrado
- **THEN** la respuesta tiene el mismo código y una duración comparable a la de una cuenta existente con contraseña incorrecta

#### Scenario: Cuenta existente con contraseña incorrecta

- **WHEN** un cliente intenta iniciar sesión con un correo registrado y una contraseña incorrecta
- **THEN** la respuesta tiene el mismo código y una duración comparable a la de una cuenta inexistente

### Requirement: La contraseña cumple una política mínima

El sistema SHALL rechazar una contraseña que no alcance la longitud mínima configurada, y SHALL rechazar las contraseñas más usadas. El sistema MUST NOT almacenar la contraseña ni ningún valor derivado reversible de ella.

#### Scenario: Contraseña demasiado corta

- **WHEN** un usuario intenta registrarse con una contraseña por debajo de la longitud mínima
- **THEN** el sistema rechaza el registro indicando el requisito de longitud

#### Scenario: Contraseña más usada

- **WHEN** un usuario intenta registrarse con una contraseña incluida en la lista de contraseñas más usadas
- **THEN** el sistema rechaza el registro

### Requirement: Los eventos de autenticación quedan registrados

El sistema SHALL registrar cada intento de inicio de sesión, con su resultado, cada bloqueo de cuenta, cada emisión de credenciales, cada rotación y revocación de token, y cada solicitud de restablecimiento de contraseña. El registro MUST NOT contener contraseñas, tokens ni secretos, y MUST NOT exponerlos al cliente. El manejador global de errores MUST registrar la excepción que captura.

#### Scenario: Intento de inicio de sesión fallido

- **WHEN** un cliente intenta iniciar sesión con credenciales incorrectas
- **THEN** el sistema registra el intento con su resultado, sin incluir la contraseña ni el token

#### Scenario: Bloqueo de cuenta

- **WHEN** una cuenta alcanza el umbral de intentos fallidos
- **THEN** el sistema registra el bloqueo con su marca de tiempo

#### Scenario: Error interno no controlado

- **WHEN** una petición provoca una excepción que no corresponde a ningún caso previsto
- **THEN** el sistema registra la excepción con su traza, y la respuesta al cliente no incluye ni el mensaje ni la traza de la excepción
