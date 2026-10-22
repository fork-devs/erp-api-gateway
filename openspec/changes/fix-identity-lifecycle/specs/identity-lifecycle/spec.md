# Spec Delta

## Purpose

Garantiza que las credenciales del sistema se emiten con un contrato estable y verificable, sobreviven a un reinicio del emisor sin invalidar a los usuarios legítimos, se rotan de forma que los tokens en vuelo siguen siendo válidos durante la ventana de solapamiento, y se revocan de forma efectiva cuando el usuario cierra sesión.

## ADDED Requirements

### Requirement: La clave de firma sobrevive a un reinicio del emisor

El sistema SHALL cargar la clave de firma desde una fuente persistente en el arranque, y MUST NOT generar una clave nueva en cada arranque. El sistema MUST NOT emitir tokens firmados con una clave distinta de la que figure en el conjunto de claves publicado.

#### Scenario: Reinicio del emisor

- **WHEN** el servicio de autenticación se reinicia y vuelve a emitir un token de acceso
- **THEN** el token se firma con la misma clave que estaba en uso antes del reinicio y sigue siendo validable por los servicios que verifican tokens

#### Scenario: El material de la clave configurado está ausente

- **WHEN** el servicio de autenticación arranca sin material de clave configurado
- **THEN** el arranque falla de forma explícita y el servicio no emite ningún token

### Requirement: Cada clave activa tiene un identificador distinto y verificable

El sistema SHALL derivar el identificador de la clave (`kid`) del material de la clave, de modo que dos claves distintas nunca compartan identificador. El sistema SHALL publicar en el conjunto de claves **todas** las claves activas, y MUST NOT publicar el identificador de una clave con material distinto del que esa clave tiene.

#### Scenario: Rotación de la clave de firma

- **WHEN** la clave de firma se sustituye por otra
- **THEN** el conjunto de claves publicado incluye ambas, con identificadores distintos, y el identificador de cada una corresponde a su material

#### Scenario: Claves publicadas sin solapamiento

- **WHEN** no hay más de una clave activa
- **THEN** el conjunto de claves publicado contiene exactamente esa clave, con su identificador derivado de su material

### Requirement: Los tokens en vuelo siguen siendo válidos durante la ventana de solapamiento

Un servicio que verifica tokens SHALL aceptar un token firmado con cualquiera de las claves publicadas, siempre que el token no haya expirado y no haya sido revocado. El servicio MUST NOT rechazar un token por el hecho de que su clave ya no sea la vigente, mientras esa clave siga publicada.

#### Scenario: Token firmado con la clave anterior a la rotación

- **WHEN** el emisor rota su clave y un cliente presenta un token de acceso emitido antes de la rotación
- **THEN** el servicio verificador acepta el token mientras la clave anterior siga publicada

#### Scenario: Token firmado con una clave que ya no se publica

- **WHEN** un cliente presenta un token firmado con una clave que ya no figura en el conjunto publicado
- **THEN** el servicio verificador rechaza el token por clave desconocida

### Requirement: El cliente del conjunto de claves refresca según su intervalo y falla cerrado

Un servicio que verifica tokens SHALL refrescar el conjunto de claves al agotarse su intervalo de validez, con independencia de si la clave solicitada ya está en la caché. Cuando la clave solicitada no figure en el conjunto publicado tras el refresco, el servicio MUST rechazar el token y MUST NOT verificar su firma contra ninguna otra clave.

#### Scenario: Clave conocida tras agotarse el intervalo

- **WHEN** se valida un token cuyo identificador de clave ya está en la caché, después de que la caché haya agotado su intervalo
- **THEN** el servicio refresca el conjunto de claves y valida el token contra la clave actualizada

#### Scenario: Identificador de clave desconocido

- **WHEN** se valida un token cuyo identificador de clave no figura en el conjunto publicado, ni antes ni después de refrescarlo
- **THEN** el servicio rechaza el token y no lo verifica contra ninguna otra clave

#### Scenario: Verificación repetida con el mismo identificador

- **WHEN** se validan dos tokens con el mismo identificador de clave dentro del intervalo de validez de la caché
- **THEN** el servicio no descarga el conjunto de claves más de una vez en ese intervalo

### Requirement: El conjunto de claves se descarga con tiempo de espera y sin vaciar la caché

Un servicio que verifica tokens SHALL aplicar un tiempo de espera a la descarga del conjunto de claves, tanto de conexión como de lectura. Un fallo de descarga MUST NOT eliminar las claves ya cargadas, MUST NOT provocar una descarga por cada petición entrante, y MUST quedar registrado.

#### Scenario: El emisor del conjunto de claves no responde

- **WHEN** el emisor del conjunto de claves no responde y un servicio verifica un token
- **THEN** la descarga termina por tiempo de espera en un tiempo acotado, el servicio conserva las claves que ya tenía cargadas y el fallo queda registrado

#### Scenario: Peticiones sostenidas durante la indisponibilidad

- **WHEN** llegan peticiones de forma sostenida mientras el emisor del conjunto de claves está indisponible
- **THEN** el número de descargas por unidad de tiempo está acotado por el intervalo de reintento y no crece con el volumen de peticiones

### Requirement: El conjunto de claves se transporta cifrado fuera del entorno local

El sistema SHALL utilizar un esquema cifrado para el transporte del conjunto de claves fuera del entorno local de desarrollo, y MUST rechazar el arranque cuando la URL configurada no utilize ese esquema. El sistema MUST NOT usar como configuración por defecto una URL en claro como configuración por defecto.

#### Scenario: Arranque con una URL del conjunto de claves en claro

- **WHEN** un servicio arranca fuera del entorno local con la URL del conjunto de claves en un esquema sin cifrar
- **THEN** el arranque falla de forma explícita y el servicio no acepta peticiones

#### Scenario: Arranque en el entorno local

- **WHEN** un servicio arranca en el entorno local con una URL del conjunto de claves en claro
- **THEN** el arranque es correcto y el servicio funciona con normalidad

### Requirement: El contrato de claims del token es único y verificado

El token de acceso SHALL transportar el conjunto de roles como un array bajo el identificador `roles`. El sistema MUST NOT asumir un rol por defecto cuando el token no declara roles, y MUST rechazar el token. Los servicios que verifican tokens SHALL validar el emisor y la audiencia del token contra los valores esperados.

#### Scenario: Token sin claim de roles

- **WHEN** un cliente presenta un token de acceso sin el claim `roles`
- **THEN** el servicio verificador rechaza el token y no le asigna ningún rol por defecto

#### Scenario: Token con varios roles

- **WHEN** un cliente presenta un token de acceso con el claim `roles` conteniendo tres roles
- **THEN** el servicio verificador obtiene los tres roles del token, sin perder ninguno ni convertirlo en un único rol

#### Scenario: Token emitido por otro emisor

- **WHEN** un cliente presenta un token de acceso correctamente firmado cuyo emisor no es el esperado
- **THEN** el servicio verificador rechaza el token

#### Scenario: Token emitido para otra audiencia

- **WHEN** un cliente presenta un token de acceso correctamente firmado dirigido a una audiencia distinta de la esperada
- **THEN** el servicio verificador rechaza el token

### Requirement: El token de acceso es revocable de forma efectiva

El token de acceso SHALL incorporar un identificador único por token. El sistema SHALL mantener el registro de los tokens revocados y MUST consultarlo al verificar un token, de modo que la revocación surta efecto antes de que el token expire. El registro de tokens revocados MUST NOT conservar entradas de tokens ya expirados.

#### Scenario: Cierre de sesión

- **WHEN** un usuario cierra sesión y a continuación presenta su token de acceso en una petición
- **THEN** el servicio verificador rechaza el token por revocado, aunque su tiempo de expiración no haya vencido

#### Scenario: Token no revocado

- **WHEN** un usuario autenticado presenta un token de acceso que no ha revocado
- **THEN** el servicio verificador lo acepta

#### Scenario: Limpieza de tokens expirados

- **WHEN** un token revocado alcanza su fecha de expiración
- **THEN** el sistema elimina su registro de la lista de revocados

### Requirement: El restablecimiento de contraseña entrega su token al usuario

El sistema SHALL entregar el token de restablecimiento de contraseña al destinatario previsto por un canal de notificación, y MUST NOT incluir el token en claro en la respuesta de la API que lo solicita. El sistema SHALL mantener la respuesta de la API idéntica exista o no el destinatario, de modo que no permita enumerar cuentas.

#### Scenario: Solicitud de restablecimiento con cuenta existente

- **WHEN** un usuario solicita el restablecimiento de contraseña para una cuenta existente
- **THEN** el sistema envía el token al canal de notificación del usuario y la respuesta de la API no contiene el token

#### Scenario: Solicitud de restablecimiento con cuenta inexistente

- **WHEN** un usuario solicita el restablecimiento de contraseña para una cuenta que no existe
- **THEN** la respuesta de la API es idéntica a la de una cuenta existente y no se envía ninguna notificación

#### Scenario: Uso del token entregado

- **WHEN** el usuario recibe el token por el canal de notificación y lo usa para restablecer su contraseña
- **THEN** el restablecimiento se completa y el token queda invalidado para un segundo uso

### Requirement: La gestión de usuarios es alcanzable por el borde

El borde de entrada SHALL enrutar las peticiones de gestión de usuarios al servicio de autenticación. El borde MUST NOT mantener rutas públicas que apunten a operaciones inexistentes.

#### Scenario: Petición de gestión de usuarios a través del borde

- **WHEN** un cliente autenticado envía una petición de gestión de usuarios al borde
- **THEN** la petición se enruta al servicio de autenticación y recibe su respuesta

#### Scenario: Ruta pública inexistente

- **WHEN** el borde recibe una petición a una ruta declarada como pública en su configuración
- **THEN** esa ruta corresponde a una operación real del servicio de destino
