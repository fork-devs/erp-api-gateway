# Verificación de la actualización de la documentación (tasks 8.3 y 8.5)

> La documentación vive en la **raíz del workspace**, que no es un repositorio
> Git: `docs/` no está versionado. Este archivo deja constancia de lo
> actualizado, para que el cambio sea trazable desde el repositorio.

## Task 8.3 — `docs/doc-estado-implementacion.md`

**Hallazgos marcados como cerrados:**

| Hallazgo | Estado registrado |
|:---|:---|
| P0 5.5 — El registro de usuarios está roto en PostgreSQL | ✅ CERRADO (`fix-identity-lifecycle` 5.1–5.2, `fix-tenant-isolation` 4.1–4.7) |
| P0 5.6 — Caída total de autenticación tras cada reinicio | ✅ CERRADO (`fix-identity-lifecycle` 1.1–1.6) |
| P0 5.7 — Contradicción de claims `roles` vs `role` | ✅ CERRADO (`fix-identity-lifecycle` 2.1, 7.1, 7.2, 8.2) |
| P0 5.12 — JWKS en claro HTTP | ✅ CERRADO (`fix-identity-lifecycle` 6.5) |
| P1 6.1 — Claves RSA efímeras | ✅ CERRADO (`fix-identity-lifecycle` 1.1–1.6) |
| P1 6.20 — No hay revocación de access tokens | ✅ CERRADO (`fix-identity-lifecycle` 3.1–3.5) |
| P1 6.21 — El reset de contraseña es inutilizable | ✅ CERRADO (`fix-identity-lifecycle` 4.1–4.4) |
| P1 6.26 — La gestión de usuarios es inalcanzable | ✅ CERRADO (`fix-identity-lifecycle` 7.3) |
| P1 6.29 — Tormenta de fetch JWKS | ✅ CERRADO (`fix-identity-lifecycle` 6.1–6.4) |

**Otros apartados actualizados:**

- **§1 bis** — incorpora el change `fix-identity-lifecycle`: los 9 hallazgos
  cerrados, los defectos adicionales encontrados (las dos copias del cliente
  del conjunto de claves ya divergían) y la tabla de changes pendientes.
- **§1 (Semáforo)** — recuerda que el recuento es el del estado original y
  que el vigente está en §1 bis, con la lista de changes aplicados.
- **§8 (ASR)** — QA-8 refleja las 221 pruebas.
- **§9 (Build y tests)** — las suites vigentes: auth 74, gateway 28, tenant 62,
  hub 57.
- **§11 (Veredicto por servicio)** — los servicios con demarcación
  transaccional ya no se describen como carentes de ella.

**Hallazgos que quedan abiertos y su change asociado:**

| Change | Cierra |
|:---|:---|
| `harden-access-control` | P1 6.18, 6.22, 6.23, 6.24, 6.25, 6.28, 7.5 |
| `fix-provider-integration` | P0 5.9, P0 5.10, P0 5.11 · P1 6.3, 6.4, 6.10, 6.40-6.50, 7.22, 7.24, 7.35 |

## Task 8.5 — `docs/doc-microservices.md` §3.1 y `docs/doc-tactics.md`

**`doc-microservices.md` §3.1 (IAM):**

- La responsabilidad pasa a declarar el contrato completo del token:
  `tenant_id`, `roles` como **array**, `aud`, `jti`, `iss` y expiración.
- Se añade un apartado **«Contrato del token»**: el claim de roles es un
  array, un token sin roles **se rechaza** (no hay rol por defecto), el token
  lleva identificador único y por tanto se puede revocar un token concreto,
  el cierre de sesión revoca el token de acceso, y emisor y audiencia se
  comprueban contra los valores configurados.
- Se añade el apartado **«Clave de firma»**: el material persiste, el `kid`
  se deriva del material, y la rotación se hace con ventana de solapamiento,
  remitiendo al procedimiento.

**`doc-tactics.md` (Security, QA-4/QA-5):**

- Se añade la táctica **«Contrato del token de acceso»**, con la misma
  descripción del claim de roles como array, el rechazo del token sin roles,
  el `jti` como base de la revocación concreta, y la comprobación de `aud` e
  `iss`.

## Verificación exigida por las tasks

> *Verificar que el documento no afirma que ninguno de esos defectos está
> cerrado mientras no lo esté.*

Las nueve marcas de cerrado se han añadido **solo** a los hallazgos que el
change `fix-identity-lifecycle` cierra, cada una con el intervalo de tasks
que la cierra.

> *Verificar que la documentación de las tácticas de seguridad refleja el
> contrato implementado y no sólo el previsto.*

El contrato descrito es el que el código implementa y el que las pruebas
comprueban: `roles` como array (`TokenClaims.roles`), rechazo del token sin
roles (`NimbusJwtValidatorAdapter`), `jti` y revocación concreta
(`AccessTokenRevocationService`), y comprobación de `iss` y `aud`
(`emisorYAudienciaCorrectos`).
