# ADR-001 — Se difiere la migración a Spring Authorization Server

- **Estado:** Aceptado
- **Fecha:** 2026-10-21
- **Change:** `harden-access-control`
- **Ámbito:** `erp-auth-service` y, por consecuencia, `erp-api-gateway` y `erp-integration-hub`
- **Copia canónica:** `erp-auth-service/docs/adr/ADR-001-diferir-migracion-spring-authorization-server.md` (ruta relativa al workspace; este repositorio guarda un espejo)

## Contexto

`doc-microservices.md` §3.1 declara, en la decisión del contexto IAM:

> **Decisión:** Java/Spring Authorization Server. Gateway valida JWKS en edge (caché 5min), servicios revalidan claims + RLS.

El código **no implementa** esa decisión. `erp-auth-service` es un emisor de
JWT propio: firma con una clave RSA que carga del secreto configurado
(`RsaKeyPairManager`), emite `iss`, `aud`, `jti`, `tenant_id`, `email`, `name`
y `roles` como array, y publica el conjunto de claves en
`/.well-known/jwks.json`. No hay authorization server, ni cliente OAuth2, ni
puntos de token, ni consentimiento, ni rotación de refresh gestionada por el
servidor.

El reporte de estado (`docs/doc-estado-implementacion.md`, hallazgo **P1
6.25**) recogía precisamente esa distancia entre la decisión y el código: la
documentación describía un Authorization Server y el repositorio tenía un
emisor artesanal.

Este change corrige además varios defectos del emisor actual que un
Authorization Server habría resuelto por construcción: el rol por defecto
para los tokens sin roles, la ausencia de identificador único y de audiencia,
la revocación que no revocaba el token de acceso, y el token de
restablecimiento que se generaba y se perdía.

## Decisión

**Se difiere la migración a Spring Authorization Server.** El servicio sigue
siendo un emisor de JWT propio, y su contrato queda **documentado como
real**: token propio con `roles` como array, `aud`, `jti`, `iss` y `exp`.

La decisión de `doc-microservices.md` §3.1 **queda modificada** en consecuencia:
la sección deja de afirmar que el servicio implementa ese estándar, y remite a
este registro.

## Motivo

La migración a un servidor de autorización completo cambia **cuatro contratos
a la vez**:

1. **El contrato de emisión.** Las respuestas de login, refresh y logout
   cambian de forma: aparecen `access_token`, `refresh_token`, `id_token`,
   `expires_in`, `scope` y `token_type` en un contrato OAuth2, con su propio
   formato de error.
2. **El contrato de validación.** El verificador deja de validar un JWT
   cualquiera y pasa a validar un token emitido por el authorization server,
   con su `iss`, su `aud` y sus scopes.
3. **El modelo de sesión.** El refresh token deja de ser una fila propia y
   pasa a ser una concesión del authorization server, con su revocación, su
   consentimiento y su durée gestionados por otro.
4. **El modelo de clientes.** El gateway, el hub y los siete microservicios
   restantes tienen que hablar con el authorization server, y no solo validar
   un token.

Acoplar esa reescritura a la incorporación del segundo factor, a la aplicación
de roles por endpoint y a las correcciones de enumeración de cuentas
produciría un cambio de un tamaño que **no se puede revisar con garantías**,
en un componente del que dependen todos los demás servicios. Los defectos que
se corrigen en este change son de seguridad, y no pueden esperar a una
reescritura que no está acotada.

La decisión además no es de las que se toman a la ligera: migrar a Spring
Authorization Server implica decidir sobre el almacenamiento de las
concesiones, la duracion de los tokens, el consentimiento de los clientes
publicos, la integracion con el JWKS que ya consumen el gateway y el hub, y
la estrategia de autorizacion (scopes frente a roles). Es un change con su
propio diseno, sus propias pruebas y su propio periodo de convivencia con el
emisor actual.

## Alternativas descartadas

- **Migrar ahora a Spring Authorization Server, y corregir por el camino los
  defectos del emisor actual.** Se descartó: mezcla dos changes con perfiles
  de riesgo distintos, y hace que la revision de la correccion de los
  defectos de seguridad dependa de una reescritura de cuatro contratos. Un
  cambio de seguridad que no se puede revisar con garantia no es un cambio de
  seguridad: es un cambio de seguridad que no se sabe lo que hace.
- **Corregir los defectos del emisor actual y dejar la migracion para mas
  adelante, sin registrar la decision.** Se descarta: la distancia entre la
  documentacion y el codigo es en si misma un defecto. Un ADR que difiere
  una decision con un motivo, unas consecuencias y un alcance es lo que
  permite que la documentacion deje de prometer lo que el codigo no hace.
- **Sustituir Spring Authorization Server por otro proveedor de identidad
  gestionado (Keycloak, Auth0, Cognito).** Se descarta por ahora: es la
  misma decision con otra dependencia, y la eleccion de proveedor tiene sus
  propios requisitos (residencia de datos, coste, operacion) que no se pueden
  decidir sin el equipo de operacion.
- **Mantener el emisor artesanal sin registrar nada.** Se descarta: es el
  estado actual, que es precisamente lo que el reporte de estado senala como
  P1 6.25.

## Consecuencias

**Positivas:**

- El servicio de autenticacion sigue siendo simple y su contrato esta
  documentado como real: el cliente, el gateway y el hub saben exactamente
  que reciben.
- Los defectos de seguridad que este change corrige (segundo factor, rol por
  defecto, revocacion, audiencia, identificador unico, entrega del token de
  restablecimiento) quedan resueltos **antes** de la reescritura, y no
  dependen de ella.
- La decision queda registrada con su motivo y su alcance, de modo que la
  migracion se puede planificar cuando tenga su propio diseno.

**Negativas:**

- El emisor de JWT propio **sigue siendo artesanal**: no hay puntos de
  token, ni consentimiento, ni clientes publicos con flujo de autorizacion
  completo, ni revocacion de tokens de refresco gestionada por el servidor.
  Cada uno de esos deficits exige trabajo manual que un authorization server
  daria hecho.
- La decision de `doc-microservices.md` §3.1 queda modificada, y el cambio
  de contrato que implica una migracion futura no se puede hacer de forma
  transparente: los tokens emitidos con una forma no son aceptados por la
  otra, y el despliegue de la migracion reinicia las sesiones.
- Mientras el emisor siga siendo propio, el servicio tiene que mantener el
  material de firma, su rotacion, la revocacion y la publicacion del
  conjunto de claves. Es trabajo que un authorization server delegaria.
- Los roles siguen siendo un array de cadenas, no un modelo de permisos con
  ambito. La aplicacion por endpoint la resuelve el codigo, no un permiso
  declarado por el emisor.

**Neutrales:**

- La migracion, cuando se aborde, seguira siendo obligatoria para el
  contexto IAM. Este ADR no la cancela: la aplaza y acota su alcance.

## Alcance de ladecision posterior

Cuando se aborde la migracion, este registro debera ser sustituido por otro
que la/anime, y la decision original de `doc-microservices.md` §3.1 debera
restituirse. La migracion tendra que cubrir, como minimo:

1. El contrato de emision y el de validacion, con su periodo de convivencia.
2. La migracion de las sesiones existentes y su fecha de corte.
3. El modelo de roles: roles frente a scopes, y quien los concede.
4. La integracion con el `JWKS` que ya consumen el gateway y el hub.
5. El almacenamiento de las concesiones y su retencion.
