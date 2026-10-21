# Design

## Context

El aislamiento entre talleres es hoy una **convención de código**, no una garantía del sistema. Cada servicio la aplica a su manera y ninguna de las tres capas de defensa está activa:

1. **Capa HTTP.** `erp-auth-service` fija el tenant desde la cabecera `X-Tenant-ID` antes de validar el token (`JwtAuthenticationFilter.java:39-45`) y marca `/api/v1/auth/**` como `permitAll` (`SecurityConfig.java:37`). `erp-tenant-service` marca `permitAll()` sobre `/api/v1/**` (`SecurityConfig.java:27-33`), comodín que cubre `/api/v1/internal/**`. `erp-integration-hub` exige la cabecera `X-Tenant-ID` en tres controladores (`ElectronicInvoicingController.java:39,101,122`, `PaymentIntegrationController.java:35`) y en los otros dos la acepta como opcional con un tenant ficticio por defecto (`IdentityValidationController.java:39-53`).

2. **Capa de aplicación.** `erp-auth-service` resuelve el tenant de la cabecera, `erp-tenant-service` no tiene ningún mecanismo de contexto de tenant (ni `ThreadLocal`, ni filtro, ni interceptor), y `erp-integration-hub` sólo tiene `AuthenticatedUser.canAccessTenant` (`AuthenticatedUser.java:22-29`), que devuelve `true` para cualquier tenant cuando el principal es de servicio interno.

3. **Capa de datos.** Sólo `erp-auth-service` declara políticas de Row-Level Security (`V3__configure_tenant_rls.sql`), y son **inertes**: `ENABLE` sin `FORCE`, aplicación conectando con el propietario de las tablas (`compose.yaml:6-8`), y un aspecto (`PostgresTenantRlsAspect.java:26-38`) que traga los fallos del `SET LOCAL`. `erp-tenant-service` y `erp-integration-hub` no declaran ninguna política.

Hay además una **dependencia temporal con el change de ciclo de vida de identidad**: la clave RSA del auth-service es efímera y se regenera en cada arranque (`RsaKeyPairManager.java:29-30`), con `kid` constante. Mientras eso no se corrija, ningún servicio puede confiar de forma estable en el JWKS del auth-service, y por tanto el tenant-service no puede validar de forma robusta un token de servicio a servicio emitido por el auth-service. Este change no resuelve ese problema, pero tampoco puede depender de él: el aislamiento en la base de datos y la derivación del tenant desde el token no requieren que la clave esté persistida.

## Goals / Non-Goals

**Goals:**

- Que el tenant efectivo tenga **una sola fuente de verdad**: el claim `tenant_id` de un token verificado.
- Que **ninguna** cabecera ni campo de cuerpo pueda decidir el tenant efectivo en ningún servicio.
- Que el aislamiento de base de datos sea **efectivo** y no decorativo: RLS aplicada al rol real de la aplicación, contexto ausente ⇒ conjunto vacío.
- Que el servicio de tenants deje de exponer operaciones de administración y de lectura de límites de forma anónima.
- Que exista **evidencia automatizada** del aislamiento, ejecutada en la integración continua, contra PostgreSQL real.
- Que el cambio sea **aplicable por partes**: cada servicio se puede migrar sin romper a los demás, y la degradación durante la transición es conocida y explícita.

**Non-Goals:**

- No se implementa autenticación multifactor, rotación de refresh tokens, revocación de access tokens ni aplicación de roles por endpoint: pertenecen al change de ciclo de vida de identidad.
- No se toca la demarcación transaccional ni el outbox transaccional: pertenecen al change de atomicidad.
- No se cambia el comportamiento de los adaptadores externos del hub ni su idempotencia: pertenecen al change de integración con proveedores.
- No se introduce mTLS entre servicios, ni bóveda de secretos, ni rotación de claves.
- No se rediseña el modelo de datos de las tablas de negocio más allá de las columnas `tenant_id` estrictamente necesarias para el aislamiento.

## Decisions

### D1 — El tenant efectivo se resuelve en un único punto por servicio, desde el principal autenticado

Cada servicio que participa del contrato de tenant expone un componente de contexto de tenant con la misma forma: un valor inmutable `TenantContext(tenantId, source)` con un `ThreadLocal` de ámbito de request, poblado **exclusivamente** desde el principal autenticado y limpiado en el `finally` del filtro. El código de negocio lo consume desde el contexto, nunca desde un parámetro de la petición.

En `erp-auth-service` esto ya existe (`TenantContextHolder`) pero se puebla desde la cabecera; el cambio es invertir la fuente. En `erp-tenant-service` hay que introducirlo. En `erp-integration-hub` el equivalente es `AuthenticatedUser.tenantId()`, que ya viaja en el `SecurityContext`.

**Alternativas descartadas:**
- *Un filtro que valide que la cabecera coincide con el token y rechace si no.* Se descartó porque mantiene dos fuentes de verdad y obliga a que cada servicio que reciba la cabecera recuerda validarla; el fallo por omisión de esa validación es exactamente el P0 5.2.
- *Pasar el tenant como parámetro explícito en cada caso de uso.* Se descartó porque convierte el tenant en un dato que cada llamada puede olvidar propagar, y porque el `ThreadLocal` con limpieza por request es el patrón que ya usan Spring Security y el resto del ecosistema.

### D2 — `X-Tenant-ID` se degrada a pista de correlación, no a autoridad

La cabecera se conserva en el contrato (el gateway la sigue enviando y es útil para trazas y para el gateway de caché) pero deja de decidir el tenant efectivo. Cuando viene presente, se **verifica su coincidencia** con el tenant del principal y se rechaza la petición si no coincide, de modo que un cliente que intente colarse con una cabecera ajena recibe un error explícito en lugar de ser silenciosamente corregido.

**Alternativas descartadas:**
- *Eliminar la cabecera del contrato.* Se descartó porque obliga a un cambio de versión del contrato de la API pública y no aporta valor de seguridad: con D1 la cabecera ya no es una superficie de ataque.
- *Aceptar la cabecera sin validarla cuando coincide con el principal.* Se descartó porque no cambia nada respecto a D1 y añade una rama de código sin efecto.

### D3 — Los endpoints internos del tenant-service se autentican con un JWT validado contra el JWKS del auth-service

`erp-tenant-service` valida tokens RS256 contra el JWKS del auth-service, con caché de claves y validación de `exp`, `nbf`, `iat` y `iss`. El tenant efectivo se deriva del claim `tenant_id` del token. El tenant-service acepta dos clases de principal:

- **Principal de usuario**, cuyo `tenant_id` MUST coincidir con el tenant del recurso solicitado. Es el caso del gateway, que reenvía el token del llamante original en la consulta de límites (`TenantServiceClientAdapter.fetchQuotaFromService`); como la caché de cuotas está indexada por tenant, el valor cacheado nunca se comparte entre talleres.
- **Principal de servicio**, con un rol acotado `ROLE_INTERNAL_SERVICE` y **sin** `ROLE_ADMIN`. El error del hub (`InternalServiceAuthenticationFilter.java:73-79`) es precisamente conceder privilegios de plataforma a una credencial compartida.

`erp-tenant-service` ya declara `com.nimbusds:nimbus-jose-jwt:9.40` en su `pom.xml` sin usarlo, de modo que la dependencia no es nueva.

**Alternativas descartadas:**
- *Secreto compartido estático, como hace el hub.* Se descartó porque replica el defecto que el reporte señala en el hub (P1 6.50): una credencial sin identidad de emisor, sin rotación y sin atribución.
- *Exigir al gateway que obtenga un token de servicio propio para la consulta de límites.* Se descartó porque el gateway ya dispone del token del llamante original y ya lo ha validado: reenviarlo evita introducir un mecanismo de emisión y caché de tokens de servicio que ningún otro consumidor necesita todavía. Si en el futuro un consumidor interno necesita consultar límites sin un usuario detrás, se incorpora el principal de servicio.
- *mTLS entre el gateway y el tenant-service.* Se descarta **para este change**, no en principio: es la opción correcta a medio plazo y se deja registrada como deuda. Se rechaza ahora porque exige materializar la confianza en la capa de red, lo que impide los tests de integración contra Testcontainers y añade una dependencia de infraestructura antes de que exista el resto del andamiaje.

### D4 — Separación del rol de aplicación del rol de propietario, con `FORCE ROW LEVEL SECURITY` como segunda capa

En cada base de datos se crea un rol de runtime `NOINHERIT`, no propietario, con permisos `SELECT`/`INSERT`/`UPDATE`/`DELETE` únicamente sobre las tablas de negocio. Flyway sigue ejecutándose con el rol propietario (Spring Boot permite credenciales separadas para Flyway y para el `DataSource`). Además, todas las tablas con `tenant_id` pasan a `FORCE ROW LEVEL SECURITY`.

**Alternativas descartadas:**
- *Solo `FORCE ROW LEVEL SECURITY`, manteniendo el mismo usuario.* Se descartó porque, aunque `FORCE` sí sujeta al propietario a la política, deja al rol de la aplicación con permisos DDL: un compromiso de la aplicación podría deshabilitar la política con un `ALTER TABLE`. La separación de roles es la defensa que sobrevive al compromiso de la aplicación.
- *Una base de datos por tenant.* Se descartó porque el proyecto la rechazó explícitamente en `doc-microservices.md` §2 por coste operativo, y porque QA-4 no la exige: exige denegación, no aislamiento físico.

### D5 — El contexto de tenant se establece dentro de la transacción, y falla de forma explícita si no hay contexto

El mecanismo actual —un aspecto AOP que ejecuta `SET LOCAL` con `catch (Exception ignored)`— se sustituye por uno con dos garantías: **orden explícito** para que el `SET LOCAL` se ejecute dentro de la transacción, y **fallo cerrado** en lugar de fallo abierto.

Para el orden, el interceptor transaccional de Spring y el aspecto compiten en `LOWEST_PRECEDENCE`, lo que hace el orden **indeterminado** hoy. La corrección es fijar el orden de forma explícita: `@EnableTransactionManagement(order = 0)` en la configuración y `@Order(1)` en el aspecto, de modo que el interceptor transaccional sea el advice externo y el aspecto el interno; el `SET LOCAL` queda así dentro de la transacción, que es donde `SET LOCAL` tiene efecto.

Para el fallo cerrado, el aspecto deja de tragar excepciones: si no hay transacción activa o no hay contexto de tenant, lanza una excepción de dominio que aborta la operación. `SET LOCAL` fuera de un bloque transaccional solo emite un aviso en PostgreSQL y no tiene efecto, de modo que el fallo silencioso actual equivale a operar sin filtro.

**Alternativas descartadas:**
- *Interceptor de Hibernate que reescriba el SQL.* Se descartó porque acopla la política de aislamiento a la forma del SQL, que es exactamente lo que se quiere evitar: el aislamiento debe depender de la base de datos, no del generador de consultas.
- *Un `DataSource` que inyecte el `SET LOCAL` al tomar una conexión.* Se descartó porque `SET LOCAL` tiene alcance de transacción, no de conexión: con el pool de Hikari la conexión sobrevive a la transacción y el valor se filtraría a la siguiente operación que la reutilice.

### D6 — La matriz de roles asignables vive en el dominio

`erp-auth-service` incorpora una matriz explícita de privilegio: un `SUPERADMIN` puede asignar cualquier rol; un `ADMIN_TALLER` puede asignar `ADMIN_TALLER`, `ASESOR` y `MECANICO`; un `ASESOR` o `MECANICO` no puede crear usuarios. El conjunto de roles efectivo es la intersección entre lo que el actor pide y lo que su propio rol le permite conceder.

**Alternativas descartadas:**
- *Tabla de configuración de roles en base de datos.* Se descartó **para este change** porque introduce un artefacto de configuración nuevo y una caché que invalidar, a cambio de una tabla que hoy tiene cuatro filas. Se deja registrada como deuda: cuando la matriz crezca o necesite ser operable por taller, debe pasar a tabla.
- *Rechazar todo `POST /api/v1/users` que no venga de un `SUPERADMIN`.* Se descarta porque impide la operación normal de un taller, que es crear Kilometer para su propio taller.

### D7 — El hub resuelve el tenant desde el `SecurityContext`, no desde la cabecera

Los controladores del hub dejan de declarar `X-Tenant-ID` como obligatorio (`ElectronicInvoicingController`, `PaymentIntegrationController`) y lo pasan a opcional e informativo; el tenant efectivo se toma de `AuthenticatedUser.tenantId()` del `SecurityContext`. `IdentityValidationController` y `IntegrationAdminController` ya siguen parcialmente este patrón (`IntegrationAdminController.java:63`); el cambio lo generaliza. Se elimina el tenant "todos ceros" (`IdentityValidationService.java:43`): cuando no hay tenant resoluble, la operación se rechaza con `403`.

**Alternativas descartadas:**
- *Exigir la cabecera en todas partes y derivarla de ella.* Se descartó por D1: reintroduce la cabecera como autoridad.
- *Mantener el tenant por defecto pero marcar las filas como "sin tenant".* Se descartó porque el valor ficticio contamina las tablas de idempotencia, de intentos y de cola de reintentos, y no existe ninguna consulta que lo distinga de un tenant real.

### D8 — `tenant_outbox` incorpora `tenant_id` con backfill desde el agregado

La columna se añade como `NOT NULL` tras el backfill, con índice parcial `(tenant_id, published) WHERE published = FALSE` para sostener la consulta del relay. El valor se toma del agregado que originó el evento (`tenant.id`), que es la única fuente fiable en el momento de escribir el outbox.

**Alternativas descartadas:**
- *Dejar la columna nullable y filtrar en la aplicación.* Se descartó porque una fila sin tenant es indistinguible de una fila de un tenant concreto, y el índice parcial no puede ser útil sobre un `NULL`.

## Risks / Trade-offs

- **[La separación de roles exige cambios de operación]** → Mitigación: las credenciales del rol de aplicación se introducen por variable de entorno, y el compose local y el `.env.example` documentan ambos roles. El rol propietario queda solo para migraciones, con la contraseña en el mismo almacén de secretos que el resto.
- **[Un `GRANT` incompleto rompe la aplicación en tiempo de ejecución, no en el arranque]** → Mitigación: la migración de permisos incluye el `GRANT` explícito por tabla y por secuencia, y la suite de integración con Testcontainers ejecuta las consultas reales contra el rol de aplicación, de modo que un `GRANT` ausente falla en la integración continua y no en producción.
- **[El tenant-service pasa a depender de la estabilidad del JWKS del auth-service]** → Mitigación: el change de ciclo de vida de identidad persiste la clave y rota el `kid`; hasta entonces, el impacto se limita a la validación de tokens de servicio. El aislamiento en base de datos y la derivación del tenant no dependen de ello.
- **[El `ThreadLocal` de contexto de tenant es un estado global compartido entre hilos]** → Mitigación: se limpia en el `finally` del filtro, como ya hace `JwtAuthenticationFilter.java:82-86`; el contexto es inmutable; y la suite de integración incluye un caso de reutilización de hilo del pool que comprueba que el contexto no se filtra entre peticiones.
- **[Rechazar las peticiones sin tenant resoluble reduce la superficie funcional del hub]** → Mitigación: es intencionado. Las rutas públicas que no necesitan tenant (health, documentación) no quedan afectadas, y el gateway, que ya valida el token, es quien propaga el claim.
- **[La matriz de roles en el dominio es rígida]** → Mitigación: documentada como deuda con criterio explícito de transición a tabla. El riesgo real es que la política de privilegios se disperse entre código y configuración; se mitiga manteniendo la matriz en un único tipo de dominio.

## Migration Plan

El orden importa: primero la base de datos, después el código que la usa. Cada paso es reversible y la reversión no requiere restaurar datos.

1. **Rol de aplicación.** Migración que crea el rol `NOINHERIT` sin privilegios de propietario y concede `USAGE` sobre el esquema y `SELECT/INSERT/UPDATE/DELETE` sobre las tablas de negocio, más `USAGE` sobre las secuencias. No cambia todavía el comportamiento de la aplicación.
2. **`FORCE ROW LEVEL SECURITY` y políticas.** Migración que aplica `FORCE` a las tablas con `tenant_id` ya cubiertas, y crea las políticas que faltan en las tablas de tokens. Se acompaña de la política equivalente al `USING (tenant_id = current_setting('app.tenant_id')::uuid)` ya usada en el auth-service, ahora **sin** el hueco de escape `app.bypass_rls`, que desaparece.
3. **Columnas `tenant_id` en las tablas de tokens.** Migración que añade `tenant_id` nullable, hace el backfill por join con `users`, y pasa la columna a `NOT NULL`.
4. **`tenant_id` en `user_roles`.** Migración que añade la columna, hace el backfill por join con `users`, la pasa a `NOT NULL` y añade la columna al `@JoinTable` de JPA. Esto **destrava el P0 5.5**, que es la razón por la que el registro de usuarios falla hoy contra PostgreSQL.
5. **`tenant_id` en `tenant_outbox`.** Migración equivalente en el servicio de tenants, con backfill desde el agregado.
6. **Cambio de credenciales.** La aplicación pasa a conectar con el rol de aplicación. Es el punto de corte: a partir de aquí, la RLS es efectiva.
7. **Código de aplicación.** Derivación del tenant desde el principal, matriz de roles, cierre de los endpoints del tenant-service, contexto de tenant del hub y eliminación del tenant ficticio. La suite cross-tenant se activa en la integración continua.

**Reversión.** Cada migración es aditiva y la política de RLS se puede desactivar con un único `ALTER TABLE ... DISABLE ROW LEVEL SECURITY`, sin pérdida de datos. La migración 3 y la 4 no tienen reversión automática porque el `NOT NULL` sobre el backfill ya aplicado no admite volver a `NULL` sin una migración explícita; se documenta el `ALTER TABLE ... DROP NOT NULL` como reversión manual.

**Secuencia de despliegue.** El auth-service es el primero (define el token de servicio y el modelo de roles), después el tenant-service, después el hub. El gateway no requiere cambios en este change: ya valida el token y propaga `X-Tenant-ID`, que pasa a ser informativa.

## Open Questions

- ¿Debe `X-Tenant-ID` retirarse del contrato público en una versión mayor, o se mantiene de forma permanente como pista de correlación? No cambia el comportamiento exigido por las specs de este change, y por tanto no bloquea su implementación.
- ¿La matriz de roles asignables debe eventualmente pasar a tabla de configuración por taller? No cambia el comportamiento exigido ahora; la decisión queda condicionada a que la matriz deje de ser de cuatro entradas.
- ¿El token de servicio a servicio que consume el tenant-service debe incluir el tenant en su claim, o debe operar con un alcance de plataforma? La decisión actual es un token de plataforma con alcance acotado y sin `ROLE_ADMIN`; la alternativa —un token por tenant— implicaría emisión dinámica y queda sin explorar.
