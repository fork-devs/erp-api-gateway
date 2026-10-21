# Proposal

## Why

El ciclo de vida de las credenciales del sistema —su emisión, su validación, su rotación y su revocación— está roto en un punto que invalida todo lo demás, y arrastra consigo el único mecanismo de defensa que el sistema tiene contra el robo de tokens.

**Las claves de firma no sobreviven a un reinicio.** `RsaKeyPairManager` (`erp-auth-service/src/main/java/com/erp/auth/infrastructure/security/crypto/RsaKeyPairManager.java:29-30`) genera un par RSA nuevo **en el constructor**, en cada arranque. Nunca se persiste, nunca se lee de disco, nunca se rota de forma programada. Las propiedades `private-key-pem` y `public-key-pem` que existen en `application.yml:45-46` **no las lee ningún código**: son configuración muerta. Y el `kid` es una **constante** (`erp-auth-key-2026-v1`), de modo que el JWKS sirve el mismo identificador con material distinto tras cada arranque (P0 5.6, P1 6.1).

**Eso se combina con un segundo defecto en el gateway para producir una caída total.** `JwksKeyManager.getPublicKey` (`erp-api-gateway/src/main/java/com/erp/apigateway/infrastructure/security/JwksKeyManager.java:30-44`) devuelve **la clave cacheada sin refrescar** cuando el `kid` ya es conocido. Como el `kid` es constante, la ruta `refreshKeysIfNecessary(false)` —la que respeta el TTL de 5 minutos del spec— **es inalcanzable**, y el gateway **jamás vuelve a descargar el JWKS**. Resultado: tras cada reinicio del auth-service, **el 100 % de los tokens emitidos son rechazados por firma inválida**, y la condición no se recupera hasta que el gateway también reinicie. El mismo `JwksKeyManager` está duplicado en el hub de integraciones, con el mismo defecto (P0 5.6).

**El mismo componente tiene otros dos fallos de disponibilidad y seguridad.** Al fallar la descarga **no se actualiza `lastFetchTime`** y la descarga **no tiene timeout**, de modo que cuando el auth-service no responde el gateway encadena **dos llamadas HTTP bloqueantes por petición**, todas serializadas por un `synchronized` (P1 6.29). Y `getPublicKey` **cae a `getAnyPublicKey()`** cuando el `kid` es desconocido, es decir, **verifica la firma contra una clave arbitraria** en lugar de rechazar el token (P0 5.12, presente también en el hub). La URL del JWKS es además **`http://` por defecto** en los dos servicios, lo que permite la sustitución de claves por intermediario.

**El contrato de claims está roto entre servicios.** El auth-service emite `roles` como array (`NimbusJwtTokenSignerAdapter.java:66`) y el gateway lee `role` en singular (`NimbusJwtValidatorAdapter.java:78`), de modo que `getStringClaim("role")` devuelve `null` y `TokenClaims.java:26` **degrada a `"MECANICO"` a todo principal autenticado**. El gateway tampoco valida `iss` ni `aud`, aunque capture el issuer (P0 5.7, P1 6.22 en el Anexo). Y **el registro de usuarios no funciona contra PostgreSQL**: el `@JoinTable` de `UserJpaEntity` no mapea el `tenant_id` que la tabla `user_roles` declara `NOT NULL`, de modo que el `INSERT` viola la restricción y el alta de usuario devuelve `500` (P0 5.5).

**No hay revocación de access tokens.** El token **no lleva `jti`** y **no existe tabla de denylist**, de modo que el logout revoca sólo el refresh token: un access token robado sigue siendo válido hasta 15 minutos y **no hay forma de matarlo antes** (P1 6.20). El reset de contraseña tampoco es utilizable: el token se genera, se persiste y **se descarta** en `AuthController`, sin ningún canal de entrega (`AuthController.java:95-98`, P1 6.21). Y la API de gestión de usuarios es **inalcanzable por el gateway**, que no tiene ruta para `/api/v1/users` y cuya lista de rutas públicas apunta a `/api/v1/auth/register`, una ruta que no existe (`GatewayDispatcherController.java:41-47,133-169`, P1 6.26).

## What Changes

- **P0 5.5** — `erp-auth-service`: el `@JoinTable` de `UserJpaEntity` mapea la columna `tenant_id` de `user_roles`, de modo que el alta de usuario con roles funciona contra PostgreSQL. Es el desbloqueo del registro de usuarios, que hoy falla con `500` en toda instalación real.
- **P0 5.6** — `erp-auth-service` y `erp-api-gateway`: la clave de firma se **carga desde una fuente persistente** en lugar de generarse en cada arranque, y el identificador de la clave (`kid`) se **deriva del material de la clave**, de modo que una rotación produce un `kid` distinto y distinguible. El JWKS publica **todas** las claves activas, de modo que los tokens firmados con la clave anterior siguen siendo validos durante la ventana de solapamiento. El cliente del gateway deja de devolver la clave cacheada sin refrescar y **respeta su TTL**, con lo que un token firmado tras una rotación se valida contra la clave correcta.
- **P0 5.12** — `erp-api-gateway` y `erp-integration-hub`: el cliente del JWKS **deja de caer a una clave arbitraria** cuando el `kid` del token es desconocido y **rechaza el token**. La URL del JWKS **debe ser `https://`** fuera de desarrollo, validado al arrancar el servicio.
- **P1 6.29** — `erp-api-gateway` y `erp-integration-hub`: la descarga del JWKS tiene **tiempo de espera** de conexión y de lectura, y un fallo **no vacía la caché** ni dispara una tormenta de descargas: el sistema conserva el último conjunto válido, registra el fallo y acota el intervalo de reintento. El efecto neto es que una indisponibilidad del emisor **no cuelga el servicio verificador**.
- **P0 5.7** — El claim de roles pasa a ser **`roles` (array)** en los tres servicios que leen el token, con el contrato publicado como parte de la especificación compartida. Un token **sin claim de roles se rechaza**, en lugar de degradarse a un rol por defecto que convertiría a todo principal en mecánico. **BREAKING** para cualquier consumidor que lea el claim `role` en singular.
- **Validación de emisor y audiencia** — El emisor y la audiencia del token se **validan** contra los valores esperados en los servicios que verifican el token, que hasta ahora sólo comprobaban firma y expiración. **BREAKING**: los tokens emitidos sin audiencia dejan de aceptarse.
- **P1 6.20** — `erp-auth-service`: el access token incorpora un identificador único (`jti`) y el sistema mantiene una **denegación de tokens revocados** que se consulta en cada verificación, de modo que el **logout invalida el access token** y no sólo el refresh token. La denegación se limpia al expirar el token, de modo que su tamaño queda acotado.
- **P1 6.21** — `erp-auth-service`: el token de restablecimiento de contraseña **llega al usuario** por un canal de notificación, y el flujo deja de descartar el token. La respuesta de la API **no** incluye el token en claro.
- **P1 6.26** — `erp-api-gateway`: la tabla de rutas incluye `/api/v1/users`, y la lista de rutas públicas se corrige para apuntar a las rutas que existen. La gestión de usuarios deja de ser inalcanzable por el borde.
- **Verificación** — Cada corrección lleva el test que la demuestra: la persistencia de la clave entre reinicios, la validación de un token firmado con la clave anterior durante el solapamiento, el rechazo de un token con `kid` desconocido, el comportamiento del cliente ante un JWKS inalcanzable, y la revocación efectiva del access token.

## Capabilities

### New Capabilities
- `identity-lifecycle`: Garantía de que las credenciales del sistema —access tokens, refresh tokens y tokens de restablecimiento— se emiten con un contrato estable y verificable, sobreviven a un reinicio del emisor sin invalidar a los usuarios legítimos, se rotan de forma que los tokens en vuelo siguen siendo válidos durante la ventana de solapamiento, y se revocan de forma efectiva cuando el usuario cierra sesión o el sistema detecta un uso anómalo.

### Modified Capabilities
Ninguna. `openspec/specs/` sigue sin specs principales: es el tercer change del repositorio y todos sus requisitos son nuevos.

## Impact

**Microservicios afectados**

| Servicio | Archivos principales | Hallazgos cerrados |
|:---|:---|:---|
| `erp-auth-service` | `infrastructure/security/crypto/RsaKeyPairManager.java`, `infrastructure/security/jwt/NimbusJwtTokenSignerAdapter.java`, `infrastructure/security/filter/JwtAuthenticationFilter.java`, `interfaces/rest/controller/JwksController.java`, `interfaces/rest/controller/AuthController.java`, `interfaces/rest/dto/response/AuthResponse.java`, `application/service/RefreshTokenService.java`, `application/service/PasswordResetService.java`, `domain/port/TokenSignerPort.java`, `domain/port/RevokedTokenRepositoryPort.java` (nuevo), `infrastructure/persistence/entity/UserJpaEntity.java`, `src/main/resources/db/migration/` (migración nueva) | P0 5.5, P0 5.6, P1 6.1, P1 6.20, P1 6.21 |
| `erp-api-gateway` | `infrastructure/security/JwksKeyManager.java`, `infrastructure/security/NimbusJwtValidatorAdapter.java`, `domain/model/TokenClaims.java`, `infrastructure/config/GatewayProperties.java`, `interfaces/rest/controller/GatewayDispatcherController.java`, `src/main/resources/application.yml` | P0 5.6, P0 5.7, P0 5.12, P1 6.26, P1 6.29 |
| `erp-integration-hub` | `infrastructure/security/JwksKeyManager.java`, `infrastructure/security/InternalServiceAuthenticationFilter.java`, `src/main/resources/application.yml` | P0 5.12, P1 6.29 |

**APIs y contratos**

- El token de acceso incorpora `jti` y `aud`, y el claim de roles es `roles` (array). **BREAKING** para los consumidores que lean `role` en singular o que no comprueben la audiencia.
- El JWKS pasa a devolver **varias** claves con `kid` distintos cuando hay más de una clave activa. El contrato de la respuesta no cambia de forma, pero su contenido sí.
- La API de restablecimiento de contraseña deja de ser un canal de entrega del token y pasa a ser un disparador de notificación.
- El gateway incorpora la ruta `/api/v1/users` y corrige su lista de rutas públicas.

**Base de datos** — migración en `erp-auth-service` que crea la tabla de tokens revocados, con `jti` como clave primaria, `user_id`, `expires_at` e índice por `expires_at` para la limpieza. La tabla `user_roles` **no cambia de esquema** en este change: la columna `tenant_id` que exige la constraint la introduce el change de aislamiento multi-inquilino, y el mapeo de JPA de este change se prepara para recibirla.

**Dependencias y configuración** — sin dependencias de librerías nuevas: la verificación TOTP del factor segundo pertenece al change de control de acceso. Requiere configuración nueva para el material de la clave de firma, la URL del JWKS y los valores esperados de emisor y audiencia, y la lectura de las variables de entorno que hasta ahora eran ignoradas (`JWT_PRIVATE_KEY_PEM`, `JWT_PUBLIC_KEY_PEM`).

**Documentación** — `docs/doc-estado-implementacion.md` se actualiza al archivar para reflejar qué hallazgos quedan cerrados. `docs/doc-microservices.md` §3.1 (IAM) y `doc-tactics.md` (tácticas de seguridad) describen el comportamiento deseado y no requieren cambio de texto.

**Fuera de alcance** — la **aplicación efectiva de los roles por endpoint** y el **segundo factor de autenticación** pertenecen al change de control de acceso. La **atomicidad** de la rotación de refresh token pertenece al change de atomicidad, y este change construye sobre ella sin modificarla. La **entrega del token de reset** se implementa aquí con un puerto de notificación propio; el proveedor concreto de correo o SMS se conecta en un change posterior.
