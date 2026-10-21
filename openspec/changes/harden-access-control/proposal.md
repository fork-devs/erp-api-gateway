# Proposal

## Why

El control de acceso del sistema está modelado pero **no aplicado**, y las defensas que existen alrededor son más débiles de lo que su presencia sugiere.

**El segundo factor no existe.** La documentación lo promete en cinco lugares (`doc-microservices.md:56`, `doc-tactics.md:19`, `doc-viewpoints-diagrams.md:467,542`, `doc-architecture-principles.md:77`) y el C4 lo lista en la caja del auth-service. En el código, la única huella de MFA es una columna booleana `mfa_enabled` (`V1__create_auth_tables.sql:15`) y su getter. **No hay ninguna biblioteca TOTP** en el `pom.xml`, ni columna de secreto, ni códigos de recuperación, ni endpoint de desafío, ni segundo paso en `authenticate`. Peor: `User.create()` **fija `mfaEnabled = false` en duro y no existe setter**, de modo que la bandera **no puede activarse ni siquiera editando la base de datos a mano**, y `AuthenticateUserService` **nunca lee** el valor. QA-5 ("MFA + OAuth2/OIDC") no se cumple en ninguno de sus dos términos (P1 6.18).

**Los roles son decorativos.** `RoleCode` existe, los cuatro roles están sembrados, y el claim `roles` viaja en el token. Pero **no hay ni una sola anotación `@PreAuthorize`, `@Secured` o `@RolesAllowed` en los cuatro servicios**, ni `@EnableMethodSecurity` en ninguna `SecurityConfig`. `SecurityConfig.java:45` autoriza `/api/v1/users/**` con `.authenticated()` y nada más: **cualquier principal autenticado puede invocar cualquier endpoint de ese prefijo**, con independencia de su rol. `JwtAuthenticationFilter.java:66-70` deposita las autoridades `ROLE_*` en el `SecurityContext` y **nadie las consulta**. `User.hasRole()` es código muerto. El gateway tampoco comprueba roles: `GatewaySecurityService.java:24-53` no hace ninguna comprobación, y además lee un claim que el emisor no emite (P1 6.19).

**El aislamiento de cuentas está roto por el propio esquema.** `users.email` es `UNIQUE` **global** (`V1__create_auth_tables.sql:12`), no por tenant. Consecuencias: una misma persona **no puede tener cuenta en dos talleres**, que es un caso real en un ERP de talleres; el registro en un taller falla porque el correo ya existe en otro, lo que además **filtra información entre tenants**; y en `AuthenticateUserService.java:56-60`, si el `tenantId` de la petición es nulo —y es opcional en `LoginRequest`— la búsqueda es `findByEmail(email)`, **global y sin tenant**, de modo que un usuario del taller A inicia sesión sin nombrar su taller y el token emitido lleva `tenant_id = A` (P1 6.22).

**El bloqueo por intentos no es fiable.** `recordFailedAttempt()` hace lectura-modificación-escritura de `failed_attempts` **sin `@Version` y sin bloqueo pesimista**, de modo que N intentos fallidos concurrentes se pisan y **el contador puede quedarse por debajo del umbral**. QA-5 exige bloqueo tras 5 intentos (P1 6.23).

**Y el login revela qué cuentas existen.** Para un correo inexistente se lanza la excepción de inmediato; para uno existente se ejecuta `BCrypt.matches` con factor de coste 12, unos 250 ms. Esa diferencia de temporización **distingue de forma reproducible** "la cuenta existe" de "no existe", y se combina con el `409 UserAlreadyExists` explícito del registro (P1 6.24). La política de contraseña es `@Size(min = 6)`, por debajo de cualquier umbral razonable (P1 7.5).

**No hay rastro de auditoría.** `grep` de cualquier llamada a log en `erp-auth-service/src/main/java` devuelve **cero resultados**: un servicio de IAM de 3 122 líneas **no emite ni una línea de log**. Peor: el manejador global de excepciones **captura la excepción y no la registra** (`GlobalRestExceptionHandler.java:102-108`), de modo que cada error interno es invisible en producción. No hay constancia de logins, bloqueos, emisiones, rotaciones ni revocaciones de tokens, lo que es un **bloqueante duro para cualquier auditoría de seguridad** y para la evidencia de QA-5 (P1 6.28).

## What Changes

- **P1 6.18** — `erp-auth-service`: el segundo factor de autenticación pasa a existir. El usuario puede habilitarlo, el sistema le entrega un secreto de enrolamiento y un conjunto de códigos de recuperación, y el login de un usuario con el segundo factor habilitado **exige un código** antes de emitir credenciales. El código de un solo uso se valida con una tolerancia de una ventana temporal y **no puede reutilizarse** dentro de esa ventana.
- **P1 6.19** — Los tres servicios con superficie de autorización activan la **aplicación de roles por endpoint**, de modo que cada operación sensible declare qué roles la habilitan y una llamada con un rol insuficiente sea rechazada. El borde **comprueba el rol** de la ruta antes de reenviar, y el servicio de tenants **aplica el rol** en sus operaciones de administración de plataforma.
- **P1 6.22** — `erp-auth-service`: la unicidad del correo pasa a ser **por tenant** en lugar de global, lo que permite que una misma persona tenga cuentas en talleres distintos y elimina la fuga de información entre tenants en el registro. El tenant pasa a ser **obligatorio** en el login, de modo que ninguna búsqueda de usuario se resuelve sin tenant.
- **P1 6.23** — El contador de intentos fallidos se incrementa de forma **atómica**, de modo que el número de intentos concurrentes no se pierda y el bloqueo al alcanzar el umbral sea fiable. Se añade control de concurrencia optimista a la fila de usuario.
- **P1 6.24** — El login **no revela si la cuenta existe**: el tiempo de respuesta es indistinguible con independencia de que la cuenta exista, y la respuesta de la API es la misma. El registro deja de devolver un conflicto que distinga el correo existente del nuevo.
- **P1 7.5** — La política de contraseñas pasa a exigir una **longitud mínima razonable** y a rechazar las contraseñas más usadas, con el coste de verificación de contraseña parametrizable.
- **P1 6.28** — `erp-auth-service` emite **registro estructurado de los eventos de autenticación**: inicio de sesión correcto e incorrecto, bloqueo de cuenta, emisión, rotación y revocación de tokens, y restablecimiento de contraseña. El manejador global de excepciones **registra** la excepción que captura, sin filtrar su contenido al cliente. Los tres servicios exponen sus comprobaciones de salud y disponibilidad, y el servicio de autenticación deja de estar mudo.
- **Desviación de la decisión de arquitectura documentada** — La decisión registrada en `doc-microservices.md` §3.1 es *"Java/Spring Authorization Server"*. El sistema implementa hoy un emisor de JWT artesanal. Este change **no migra** a Spring Authorization Server: la migración se aborda en un change propio, con su propio ADR, y aquí se deja constancia explícita de la desviación y de su motivo, en lugar de mantener una discrepancia silenciosa entre la documentación y el código.

## Capabilities

### New Capabilities
- `access-authorization`: Garantía de que sólo quien tiene el rol y ha completado los factores de autenticación exigidos accede a cada operación, de que el estado de una cuenta no puede revelar su existencia a un tercero, y de que todos los eventos de autenticación quedan registrados de forma auditable.

### Modified Capabilities
Ninguna. `openspec/specs/` sigue sin specs principales: es el cuarto change del repositorio y todos sus requisitos son nuevos.

## Impact

**Microservicios afectados**

| Servicio | Archivos principales | Hallazgos cerrados |
|:---|:---|:---|
| `erp-auth-service` | `domain/model/User.java`, `domain/model/RoleCode.java`, `domain/port/UserRepositoryPort.java`, `domain/port/NotificationPort.java` (interfaz del puerto de segundo factor), `application/service/AuthenticateUserService.java`, `application/service/RegisterUserService.java`, `application/service/PasswordResetService.java`, `infrastructure/config/SecurityConfig.java`, `infrastructure/security/filter/JwtAuthenticationFilter.java`, `infrastructure/persistence/entity/UserJpaEntity.java`, `infrastructure/persistence/adapter/UserPersistenceAdapter.java`, `interfaces/rest/controller/AuthController.java`, `interfaces/rest/controller/UserController.java`, `interfaces/rest/dto/request/RegisterUserRequest.java`, `interfaces/rest/dto/request/LoginRequest.java`, `src/main/resources/db/migration/` (migración nueva), `src/main/resources/application.yml` | P1 6.18, P1 6.19, P1 6.20, P1 6.21, P1 6.22, P1 6.23, P1 6.24, P1 6.28, P1 7.5 |
| `erp-api-gateway` | `infrastructure/config/GatewayProperties.java`, `interfaces/rest/controller/GatewayDispatcherController.java`, `application/service/GatewaySecurityService.java`, `src/main/resources/application.yml` | P1 6.19 |
| `erp-tenant-service` | `infrastructure/config/SecurityConfig.java`, `interfaces/rest/controller/TenantController.java`, `interfaces/rest/controller/PlanController.java` | P1 6.19 |

**APIs y contratos**

- El login pasa a admitir un **código de segundo factor** y, en algún caso, a exigirlo. **BREAKING** para los clientes que no lo contemplen: la respuesta de login puede pasar a requerir un paso adicional.
- El alta de usuario expone un endpoint de **enrolamiento** del segundo factor y otro de **verificación**, y las respuestas de alta y de login dejan de distinguir si la cuenta existe. **BREAKING** en el código de respuesta del registro.
- El correo pasa a ser único **por taller**, de modo que un mismo correo puede registrarse en dos tenants distintos. **BREAKING** en el modelo de datos.
- La tabla de rutas del gateway incorpora el **rol requerido** por ruta, de modo que el borde puede rechazar una llamada antes de reenviarla.

**Base de datos** — migración en `erp-auth-service` que elimina la restricción de unicidad global del correo y la sustituye por una compuesta por tenant y correo, tras comprobar y resolver los duplicados preexistentes; y migración que añade el secreto del segundo factor y los códigos de recuperación con su estado de consumo.

**Dependencias y configuración** — incorpora una biblioteca de verificación de códigos de un solo uso y una de detección de contraseñas más usadas. La configuración añade el factor de coste de la verificación de contraseñas, el número de intentos fallidos antes del bloqueo, la duración del bloqueo y la tolerancia de la ventana del segundo factor.

**Documentación** — `docs/doc-estado-implementacion.md` se actualiza al archivar. `docs/doc-microservices.md` §3.1 se corrige para dejar constancia de que el emisor actual es un emisor de JWT propio y de que la migración a Spring Authorization Server está planificada en un change propio, con su ADR. `doc-tactics.md` (tácticas de seguridad) y `doc-architecture-principles.md` §2.11 describen el comportamiento deseado y no requieren cambio de texto.

**Fuera de alcance** — la **migración a Spring Authorization Server** con descubrimiento, introspección, flujo de código de autorización y cliente de credenciales, así como el **login federado con Google OAuth** que el contexto documenta, pertenecen a un change propio. Este change **no** modifica la emisión de tokens ni el contrato de claims, que el change de ciclo de vida de identidad ya estabiliza.
