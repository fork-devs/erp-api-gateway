# Design

## Context

El sistema tiene el **vocabulario** del control de acceso pero no su **mecánica**. Los cuatro roles existen como enum, se siembran en la base de datos, viajan en el claim `roles` del token y se exponen en las respuestas de la API. Lo que no existe es ningún punto del sistema donde el rol se compruebe: no hay `@EnableMethodSecurity`, ni `@PreAuthorize`, ni `@Secured`, ni `@RolesAllowed` en ninguno de los cuatro servicios. `JwtAuthenticationFilter` deposita las autoridades `ROLE_*` en el `SecurityContext` y nadie las consulta. `User.hasRole()` existe y no lo invoca nadie.

El segundo factor está en un estado peor que el de los roles, porque no es que falte la aplicación: **falta la capacidad**. La columna `mfa_enabled` existe, `User.create()` la fija en `false` sin possibility de cambio, y no hay biblioteca de códigos de un solo uso, ni secreto, ni códigos de recuperación, ni endpoint de desafío.

Y hay tres defectos que no son de "aplicación" sino de **modelado**, y que ninguna cantidad de anotaciones arregla:

- `users.email` es único **globalmente**, lo que impide que una persona tenga cuentas en dos talleres y, en el registro, convierte un `409` en un oráculo de la existencia de la cuenta en otro tenant.
- El login resuelve la cuenta con `findByEmail(email)` cuando no se indica taller, una búsqueda **sin dimensión de tenant**, de modo que el token emitido lleva el tenant de la cuenta encontrada.
- El login **distingue por tiempo** entre "la cuenta no existe" (respuesta inmediata) y "la contraseña no es la correcta" (verificación BCrypt de coste 12, del orden de 250 ms).

La ausencia de registro de eventos de autenticación es, por sí sola, un bloqueante: sin traza de logins, bloqueos, emisiones ni revocaciones no hay forma de demostrar QA-5, y el sistema no es auditable.

Hay una cuarta cuestión de alcance que este change debe resolver explícitamente. `doc-microservices.md` §3.1 registra la decisión *"Java/Spring Authorization Server"*, y el sistema implementa hoy un emisor de JWT artesanal sobre Nimbus. Esa discrepancia está viva desde el principio y este change es el sitio adecuado para decidir qué se hace con ella, en lugar de dejarla implícita.

## Goals / Non-Goals

**Goals:**

- Que exista un **segundo factor real**, activable, exigido en el login, no reutilizable dentro de su ventana y con códigos de recuperación de un solo uso.
- Que **cada operación sensible declare su rol** y que el sistema la rechace cuando el principal no lo ostente, tanto en el borde como en el servicio.
- Que la identidad de una cuenta esté **acotada al taller**, de modo que la misma persona pueda tener cuentas en varios talleres sin que ninguna filtre información de las demás.
- Que el bloqueo por intentos sea **fiable bajo concurrencia**.
- Que el login **no revele** la existencia de una cuenta ni por la respuesta ni por la duración.
- Que la política de contraseñas tenga un **umbral razonable** y rechace las contraseñas más usadas.
- Que **todo evento de autenticación quede registrado**, de forma estructurada y sin secretos.
- Que quede **decidido y documentado** qué se hace con la desviación respecto de Spring Authorization Server.

**Non-Goals:**

- No se migra a Spring Authorization Server, ni se implementa OAuth2/OIDC con descubrimiento, introspección, flujo de código de autorización o cliente de credenciales. La decisión y su justificación quedan registradas en este change; la migración, en uno propio.
- No se implementa el login federado con Google OAuth que el contexto documenta: pertenece a la misma migración.
- No se modifica la emisión de tokens ni el contrato de claims, que el change de ciclo de vida de identidad ya estabiliza. Este change consume ese contrato.
- No se cambia el modelo de permisos más allá de los cuatro roles existentes: no se introducen permisos granulares ni roles por taller.
- No se implementa la detección de reutilización de refresh tokens ni la revocación de la familia de tokens.

## Decisions

### D1 — El segundo factor se implementa con códigos de un solo uso temporizados y códigos de recuperación

Se añade un puerto de dominio para el segundo factor, con dos operaciones: enrolar un secreto y verificar un código. La implementación usa una biblioteca de códigos de un solo uso temporizados. El sistema guarda el secreto cifrado en la base de datos y el estado de consumo de cada código de recuperación. La verificación acepta una tolerancia de **una** ventana temporal y registra el último código aceptado, de modo que el mismo código no pueda aceptarse dos veces dentro de la ventana.

**Alternativas descartadas:**
- *Correo o mensaje como segundo factor.* Se descartó porque convierte cada login en una dependencia de un proveedor externo con su propia latencia y su propio modo de fallo, y porque el contexto ya prevé un canal de notificación para el restablecimiento de contraseña, donde el objetivo es que el usuario reciba un secreto, no que introduzca uno.
- *Códigos generados por el servidor, de un solo uso, sin temporización.* Se descartó porque un código generado por el servidor exige almacenamiento y un canal de entrega en cada login, y expira rápido; la combinación con códigos de recuperación cubre ese caso.
- *No registrar el último código aceptado, y tolerar el reuso dentro de la ventana.* Se descartó porque la ventana de tolerancia de treinta segundos hace que un código observado por un tercero sea reutilizable, y el coste de registrar un único valor es despreciable.

### D2 — La aplicación de roles se expresa por endpoint, con el borde como primera línea

Cada servicio con superficie de autorización habilita la seguridad a nivel de método y anota sus endpoints con el rol requerido. El borde incorpora a su tabla de rutas el **rol requerido**, y rechaza la llamada antes de reenviarla cuando el principal no lo ostente. El borde **no** es la única defensa: los servicios vuelven a comprobar, de modo que un acceso directo a un servicio interno no salta el control.

**Alternativas descartadas:**
- *Comprobar el rol únicamente en el borde.* Se descartó porque los servicios son alcanzables desde la red interna y desde los procesos de despliegue; una comprobación única en el borde deja el control enteramente en un componente que puede ser omitido.
- *Modelar permisos granulares por operación.* Se descarta porque el proyecto tiene cuatro roles y una matriz simple; un modelo de permisos es una decisión dearchitecture de la que no hay necesidad y que multiplicaría el trabajo sin cubrir ninguna necesidad demostrada.

### D3 — La identidad de la cuenta se acota al taller en el esquema

La unicidad del correo pasa a ser **compuesta por tenant y correo**, y la migración comprueba y resuelve los duplicados preexistentes antes de aplicar la restricción. El tenant pasa a ser **obligatorio** en el login, de modo que toda búsqueda de usuario incluye la dimensión de tenant y ninguna puede resolverse sin ella.

**Alternativas descartadas:**
- *Mantener la unicidad global y permitir cuentas multi-taller con una tabla de pertenencias.* Se descartó porque es un modelo distinto, con su propia tabla de afiliación y su propia resolución de sesión por taller, y porque el proyecto no tiene un requisito de cuentas multi-taller; lo que tiene es el requisito de que dos talleres distintos puedan tener la misma persona.
- *Mantener la unicidad global y rechazar el registro con un mensaje genérico.* Se descarta porque no resuelve la limitación funcional: la persona sigue sin poder tener cuenta en dos talleres.

### D4 — El login ejecuta siempre una verificación de contraseña, exista o no la cuenta

Para que el tiempo de respuesta no distinga los casos, el login **siempre** ejecuta una verificación de contraseña con un hash señuelo cuando la cuenta no existe, de modo que el coste sea equivalente. La respuesta al cliente es la misma en los tres casos: cuenta inexistente, contraseña incorrecta y cuenta bloqueada.

**Alternativas descartadas:**
- *Normalizar el tiempo con un retardo artificial.* Se descartó porque fija el tiempo al caso más rápido, desperdiciando latencia en el caso más frecuente y aplica una espera forzada en el resto.
- *Responder con mensajes distintos pero con el mismo tiempo.* Se descartó porque el mensaje distinto revela la información aunque el tiempo no lo haga; la exigencia del sistema es que la respuesta no distinga.

### D5 — El contador de intentos se incrementa de forma atómica y con control de concurrencia optimista

El incremento del contador de intentos fallidos se realiza con una actualización atómica en base de datos, de forma que los intentos concurrentes no se pierdan. La fila de usuario incorpora control de concurrencia optimista, de modo que dos actualizaciones simultáneas del perfil entran en conflicto en lugar de pisarse.

**Alternativas descartadas:**
- *Mantener la lectura-modificación-escritura con bloqueo pesimista de fila.* Se descartó porque el bloqueo pesimista mantiene la fila retenida durante la verificación BCrypt, que dura cientos de milisegundos, y el mismo endpoint es el de mayor contención del sistema.
- *Contar los intentos fuera de la base de datos, en memoria o en Redis.* Se descartó porque el contador debe sobrevivir a un reinicio y ser coherente entre réplicas, y porque la base de datos ya es la fuente de verdad de la cuenta.

### D6 — La política de contraseñas se parametriza y rechaza las contraseñas más usadas

La longitud mínima pasa a ser configurable, con un valor por defecto por encima de doce caracteres, y el sistema rechaza las contraseñas incluidas en una lista de contraseñas más usadas. El coste de la verificación de contraseñas pasa a ser configurable, de modo que pueda subirse sin recompilar.

**Alternativas descartadas:**
- *Mantener el mínimo en seis caracteres.* Se descarta porque seis caracteres no resisten un ataque de diccionario y la decisión no cuesta nada en complejidad.
- *Aplicar reglas de composición de caracteres.* Se descarta porque las reglas de composición empujan a los usuarios hacia contraseñas predecibles; la longitud y la lista de contraseñas más usadas son más efectivas y menos hostiles.

### D7 — El registro de eventos de autenticación es estructurado y está en el servicio que produce los eventos

Cada servicio emite registro estructurado de sus eventos de autenticación, con `tenant_id`, `user_id`, `trace_id`, resultado y marca de tiempo, y **sin** contraseñas, tokens ni secretos. El manejador global de excepciones registra la excepción que captura. Los tres servicios exponen sus comprobaciones de salud y disponibilidad.

**Alternativas descartadas:**
- *Registrar los eventos de autenticación en un servicio centralizado.* Se descartó porque el evento se produce donde ocurre y el coste de enviarlo a otro sitio es un acoplamiento adicional; el formato estructurado ya permite la correlación.
- *Registrar el objeto de petición completo para poder diagnosticar.* Se descarta porque la petición contiene la contraseña, y porque el objetivo del registro es la auditoría, no el volcado de datos.

### D8 — La desviación respecto de Spring Authorization Server se decide y se documenta, sin migrar en este change

La decisión registrada en `doc-microservices.md` §3.1 no se aplica en este change. Se toma la decisión de **diferir la migración** a un change propio, con su ADR, y se **corrige la documentación** para que deje de afirmar una arquitectura que el código no implementa.

El motivo del diferimiento es concreto: la migración a un servidor de autorización completo cambia el contrato de emisión de tokens, el contrato de validación, el modelo de sesión y el modelo de clientes, es decir, es una reescritura del borde de autenticación, no un ajuste. Acoplar esa reescritura a la incorporación del segundo factor, de la aplicación de roles y de las correcciones de enumeración de cuentas produciría un cambio de un tamaño que no se puede revisar con garantías, en un componente del que dependen todos los demás servicios.

**Alternativas descartadas:**
- *Migrar ahora a Spring Authorization Server.* Se descartó por el motivo anterior, y porque el servidor de autorización aporta interoperabilidad con terceros y flujo de código de autorización, que el sistema no necesita todavía: sus consumidores son el propio borde, la aplicación móvil del mecánico y los servicios internos.
- *Retirar la decisión del documento sin registrar nada.* Se descarta porque una desviación no registrada entre documentación y código es exactamente el tipo de deuda que este proyecto está intentando eliminar.

## Risks / Trade-offs

- **[El segundo factor añade un paso al login y, por tanto, fricción y abandono]** → Mitigación: el segundo factor es optativo por usuario, de modo que cada taller decide activarlo para sus usuarios; los códigos de recuperación cubren el caso de dispositivo perdido; y el sistema registra los fallos de verificación de forma agregada, lo que permite medir el impacto antes de exigirlo.
- **[La aplicación de roles puede romper clientes que llamaban a endpoints sin el rol suficiente]** → Mitigación: los roles se aplican sólo después de inventariar qué endpoint llama cada cliente, y el cambio documenta la matriz endpoint→rol. La aplicación es explícita y los rechazos son por rol insuficiente, con un código y un mensaje que lo dicen.
- **[La migración de unicidad del correo requiere resolver duplicados preexistentes]** → Mitigación: la migración **detecta** los duplicados y **falla** antes de aplicar la restricción, en lugar de resolverlos automáticamente. La resolución requiere una decisión humana sobre qué hacer con las cuentas duplicadas, y esa decisión no se puede automatizar.
- **[La normalización del tiempo de respuesta añade una verificación de contraseña en la ruta de cuenta inexistente, lo que consume CPU]** → Mitigación: el coste es el mismo que el de una verificación real, y el sistema lo mide. Si el volumen lo justificara, la optimización sería una caché de hashes de contraseñas por correo.
- **[Migrar la unicidad del correo exige tocar datos en producción]** → Mitigación: la migración es en dos pasos, primero el índice único compuesto y después la retirada del global, de modo que se puede revertir entre pasos. La retirada del global es un `DROP INDEX`, que no afecta a los datos.
- **[El registro de eventos de autenticación contiene datos personales identificables]** → Mitigación: el registro incluye identificadores internos, no datos personales, y su retención está acotada por la rotación de logs.

## Migration Plan

1. **Servicio de autenticación: infraestructura del segundo factor.** Se incorpora la biblioteca de códigos de un solo uso, el puerto de dominio y su implementación, y la migración que añade el secreto cifrado y la tabla de códigos de recuperación con su estado de consumo. El arranque con la migración aplicada no cambia el comportamiento del login.
2. **Servicio de autenticación: habilitación y verificación.** Se añade el endpoint de enrolamiento, que genera el secreto y los códigos de recuperación, y el que confirma la verificación del segundo factor. El campo `mfaEnabled` deja de fijarse en `false` en la construcción del usuario y pasa a ser mutable por el caso de uso correspondiente.
3. **Servicio de autenticación: el login exige el segundo factor.** El caso de uso de autenticación comprueba el estado del segundo factor y, cuando está habilitado, exige un código válido. El tiempo de respuesta se normaliza con la verificación señuelo. El tenant pasa a ser obligatorio en la petición de login y en las búsquedas de usuario.
4. **Servicio de autenticación: unicidad del correo por tenant.** Migración que crea el índice único compuesto por tenant y correo, comprueba la existencia de duplicados globales y falla si los hay; tras resolverlos, una segunda migración retira el índice único global.
5. **Servicio de autenticación: robustez del contador de intentos.** La fila de usuario incorpora control de concurrencia optimista y el incremento del contador pasa a ser atómico.
6. **Servicios: aplicación de roles.** Se habilita la seguridad a nivel de método en los tres servicios con superficie de autorización, se anota cada endpoint con su rol y se añade el rol requerido a la tabla de rutas del borde. Cada endpoint que hoy está abierto a cualquier principal autenticado se revisa caso por caso.
7. **Servicios: política de contraseñas y registro de eventos.** La longitud mínima pasa a ser configurable, se incorpora el rechazo de contraseñas más usadas, y los tres servicios emiten registro estructurado de sus eventos de autenticación y de los errores internos que capturan. Se añaden las dependencias de salud y disponibilidad donde falten.
8. **Documentación.** Se corrige `doc-microservices.md` §3.1 para dejar constancia de la decisión de diferir la migración a Spring Authorization Server, con su motivo, y se referencia el ADR que la recoge.

**Reversión.** La migración del correo es reversible en dos pasos y no toca datos. La del segundo factor añade columnas y es reversible con un `DROP COLUMN`, aunque deja las cuentas con el segundo factor habilitado en un estado que el código anterior ignora, lo que es inocuo. La aplicación de roles se revierte desactivando la seguridad a nivel de método, lo que devuelve el comportamiento anterior.

## Open Questions

- ¿El segundo factor debe ser obligatorio para el rol de administrador de taller, y opcional para el resto? La decisión actual es que es opcional por usuario, y la obligatoriedad por rol se puede activar cuando el equipo tenga evidencia del coste real. No cambia el comportamiento exigido por las specs de este change, y por tanto no bloquea su implementación.
- ¿Los códigos de recuperación se entregan por el mismo canal de notificación que el token de restableecimiento, o por uno independiente? La decisión actual es el mismo puerto, con operaciones distintas. La alternativa exigirá una decisión sobre el coste y la trazabilidad de cada canal.
- ¿El bloqueo de cuenta debe escalar a un bloqueo por dirección de origen cuando hay varios intentos sobre cuentas distintas? La decisión actual es el bloqueo por cuenta. La detección de Credential Stuffing pertenece a un change de protección del borde, donde ya se trabaja la limitación de peticiones.
