# Spec Delta

## Purpose

Garantiza que ningún dato de negocio de un taller es legible ni modificable desde otro taller, en los tres servicios que participan del contrato de tenant, con defensa en profundidad: derivación del tenant desde el token verificado, autorización explícita por principal y Row-Level Security efectiva como capa de último recurso.

## ADDED Requirements

### Requirement: El tenant efectivo se deriva del token verificado

El sistema SHALL determinar el tenant efectivo de una petición únicamente a partir del claim `tenant_id` de un token JWT cuya firma, algororitmo y expiración se hayan validado. El sistema MUST NOT aceptar el tenant desde una cabecera HTTP suministrado por el cliente ni desde un campo del cuerpo de la petición. Cuando no exista un token válido con claim `tenant_id`, el sistema MUST rechazar la operación en lugar de operar con un tenant por defecto.

#### Scenario: Cabecera de tenant ajena con token válido del tenant atacante

- **WHEN** el actor `ASESOR` del taller A envía `X-Tenant-ID: <uuid-del-taller-B>` junto a un token JWT válido y emitido para el taller A
- **THEN** el sistema opera sobre el taller A, ignora el valor de la cabecera y no lee ni escribe ninguna fila del taller B

#### Scenario: Cabecera de tenant sin token de acceso

- **WHEN** un cliente anónimo envía `X-Tenant-ID: <uuid-del-taller-B>` sin cabecera `Authorization`
- **THEN** el sistema responde con error de autorización y no ejecuta ninguna operación de negocio

#### Scenario: Cabecera de tenant con token expirado

- **WHEN** el actor `MECANICO` del taller A envía un token expirado junto a `X-Tenant-ID: <uuid-del-taller-B>`
- **THEN** el sistema rechaza la petición por token inválido y no ejecuta ninguna operación de negocio

### Requirement: La creación de usuario no acepta tenant ni roles del cliente

El sistema SHALL crear un usuario exclusivamente dentro del tenant del principal autenticado y SHALL derivar el conjunto de roles asignables de los roles del propio actor. El sistema MUST NOT crear un usuario en un tenant distinto al del principal. El sistema MUST NOT asignar un rol que sea de mayor privilegio que el del actor, y MUST rechazarla con error de autorización.

#### Scenario: Actor de un taller intenta crear un usuario en otro taller

- **WHEN** el actor `ASESOR` del taller A envía `POST /api/v1/users` con el campo `tenantId` del taller B en el cuerpo
- **THEN** el sistema crea el usuario dentro del taller A o rechaza la petición por tenant no autorizado, y en ningún caso existe un usuario del taller B creado por esta llamada

#### Scenario: Actor de bajo privilegio intenta conceder un rol superior

- **WHEN** el actor `ASESOR` del taller A envía `POST /api/v1/users` con `roles: ["SUPERADMIN"]`
- **THEN** el sistema responde con error de autorización y no se crea ningún usuario con ese rol

#### Scenario: Actor de alto privilegio concede un rol dentro de su alcance

- **WHEN** el actor `ADMIN_TALLER` del taller A envía `POST /api/v1/users` con `roles: ["ASESOR", "MECANICO"]` y sin `tenantId`
- **THEN** el sistema crea el usuario en el taller A con esos roles y responde `201`

### Requirement: Los endpoints de gestión de tenants exigen credenciales y autorización por tenant

El sistema SHALL exigir un principal autenticado en todos los endpoints de gestión de tenants del servicio de tenants, y SHALL exigir un principal de servicio a servicio en sus endpoints internos. El sistema MUST verificar que el tenant de la recurso solicitado coincide con el tenant del principal autenticado, y MUST responder con error de autorización cuando no coincida. El sistema MUST NOT exponer ningún endpoint de gestión de tenants de forma anónima.

#### Scenario: Cliente anónimo intenta crear un taller

- **WHEN** un cliente anónimo envía `POST /api/v1/tenants`
- **THEN** el sistema responde con error de autenticación y no crea ningún taller

#### Scenario: Administrador de un taller intenta suspender otro taller

- **WHEN** el actor `ADMIN_TALLER` del taller A envía `PATCH /api/v1/tenants/{id-del-taller-B}/status` con estado `SUSPENDED`
- **THEN** el sistema responde con error de autorización y el taller B conserva su estado activo

#### Scenario: Cliente anónimo consulta los límites internos de un taller

- **WHEN** un cliente anónimo envía `GET /api/v1/internal/tenants/{id}/limits`
- **THEN** el sistema responde con error de autenticación y no devuelve el plan ni el estado del taller

#### Scenario: Servicio interno consulta los límites de un taller

- **WHEN** un principal de servicio a servicio autenticado envía `GET /api/v1/internal/tenants/{id}/limits`
- **THEN** el sistema devuelve el plan y los límites del taller solicitado y registra la consulta

### Requirement: La Row-Level Security está activa en toda tabla de negocio con tenant_id

El sistema SHALL activar de forma forzosa la Row-Level Security en toda tabla de negocio que tenga columna `tenant_id`, de modo que la política se aplique al rol con el que la aplicación conecta.

#### Scenario: La política se aplica al rol de la aplicación

- **WHEN** una sesión con el rol de la aplicación consulta una tabla de negocio con columna `tenant_id`
- **THEN** la base de datos aplica a esa sesión la política de aislamiento de esa tabla

### Requirement: El rol de la aplicación está sujeto a la Row-Level Security

El rol con el que la aplicación conecta MUST NOT ser el propietario de las tablas, porque el propietario de una tabla elude su propia política de Row-Level Security.

#### Scenario: El rol de la aplicación no es el propietario

- **WHEN** se comprueba el rol con el que la aplicación conecta y sus privilegios sobre las tablas de negocio
- **THEN** el rol no es el propietario de esas tablas y está sujeto a su política de Row-Level Security

### Requirement: La ausencia de contexto de tenant produce el conjunto vacío de filas

Cuando no se haya establecido el contexto de tenant en la sesión de base de datos, el sistema MUST obtener el conjunto vacío de filas de las tablas con política de aislamiento, y MUST detectar la ausencia de contexto y fallar de forma explícita en lugar de operar sin filtro.

#### Scenario: Consulta directa sin contexto de tenant establecido

- **WHEN** una sesión con el rol de la aplicación ejecuta una consulta sobre `users` sin que `app.tenant_id` esté establecido
- **THEN** la base de datos devuelve cero filas y la aplicación detecta la ausencia de contexto y falla de forma explícita

#### Scenario: Consulta directa a la base de datos con contexto de tenant establecido

- **WHEN** una sesión con el rol de la aplicación ejecuta `SELECT * FROM users` con `app.tenant_id` establecido al taller A
- **THEN** la base de datos devuelve exclusivamente filas con `tenant_id` del taller A, aunque la consulta no incluya ninguna cláusula `WHERE`

### Requirement: Las tablas de tokens de sesión tienen tenant_id y política de aislamiento

Las tablas de tokens de sesión y de restablecimiento de contraseña SHALL incluir columna `tenant_id` y SHALL quedar cubiertas por la misma política de Row-Level Security que el resto de tablas de negocio.

#### Scenario: Los tokens de sesión quedan cubiertos por la política

- **WHEN** una sesión con `app.tenant_id` del taller A consulta `refresh_tokens` y `password_reset_tokens`
- **THEN** la base de datos devuelve exclusivamente los tokens pertenecientes al taller A

### Requirement: La cola de eventos de dominio está particionada por tenant

El sistema SHALL almacenar el `tenant_id` de origen en cada registro de la bandeja de salida transaccional de tenants, y SHALL rechazar la escritura de un registro de esa cola sin tenant. El sistema MUST permitir filtrar los registros no publicados por tenant, y MUST indexar la cola por tenant y estado de publicación.

#### Scenario: Registro de outbox sin tenant

- **WHEN** se intenta persistir un registro de la bandeja de salida de tenants sin `tenant_id`
- **THEN** la base de datos rechaza la escritura por violación de la restricción `NOT NULL`

#### Scenario: Consulta de la cola por taller

- **WHEN** el relay lista los mensajes no publicados del taller A
- **THEN** devuelve exclusivamente los mensajes cuyo `tenant_id` es el del taller A, sin incluir mensajes de otros talleres

### Requirement: El hub de integraciones deriva el tenant del principal autenticado

El sistema SHALL determinar el tenant efectivo de una llamada al hub de integraciones a partir del principal autenticado.

#### Scenario: Llamada válida con cabecera coincidente

- **WHEN** un principal del taller A envía `X-Tenant-ID: <uuid-del-taller-A>` al hub
- **THEN** el sistema opera sobre el taller A y registra la operación con ese `tenant_id` en la tabla de idempotencia y en la de intentos

### Requirement: La cabecera de tenant no decide el tenant efectivo en el hub

La cabecera `X-Tenant-ID` MAY ser suministrada por el cliente, pero MUST NOT decidir el tenant efectivo, y el sistema MUST verificar su coincidencia con el tenant del principal autenticado.

#### Scenario: Llamada con cabecera de tenant ajena al principal

- **WHEN** un principal del taller A envía `X-Tenant-ID: <uuid-del-taller-B>` al hub
- **THEN** el sistema responde con error de autorización y no escribe ninguna fila perteneciente al taller B

### Requirement: El hub rechaza las llamadas sin tenant resoluble

Cuando no exista tenant resoluble en el principal autenticado, el sistema MUST rechazar la operación con error de autorización y MUST NOT registrarla bajo ningún tenant.

#### Scenario: Llamada sin tenant resoluble al hub

- **WHEN** un principal autenticado sin claim `tenant_id` llama a `GET /api/v1/integrations/validate/ruc/{ruc}`
- **THEN** el sistema responde con error de autorización y no escribe ninguna fila de auditoría ni de idempotencia

### Requirement: El hub no usa un identificador de tenant ficticio

El sistema MUST NOT usar un identificador de tenant ficticio, como el formado por ceros, como valor por defecto del tenant efectivo.

#### Scenario: El identificador de tenant ficticio no aparece en el sistema

- **WHEN** se inspeccionan las tablas de idempotencia, de intentos y de cola de reintentos del hub
- **THEN** ninguna fila contiene el identificador de tenant formado por ceros

### Requirement: El aislamiento entre talleres se verifica de forma automatizada

El sistema SHALL contar con una suite de pruebas que ejercite, contra PostgreSQL real y con la Row-Level Security activa, los vectores de cruce entre tenants de los tres servicios: tenant en el cuerpo de la petición, tenant en una cabecera y tenant omitido. La suite SHALL verificar que cada vector es rechazado, y SHALL ejecutarse en la integración continua.

#### Scenario: La suite cross-tenant cubre los tres vectores

- **WHEN** se ejecuta la suite de pruebas de aislamiento entre tenants
- **THEN** todos los casos de cruce entre el taller atacante A y el taller víctima B resultan en denegación, y la suite falla si alguno de ellos resulta en éxito o en datos del taller víctima

#### Scenario: La suite se ejecuta con el aislamiento de base de datos activo

- **WHEN** se ejecuta la suite de pruebas de aislamiento entre tenants
- **THEN** la base de datos de pruebas tiene la Row-Level Security activa y el rol de la aplicación no es el propietario de las tablas
