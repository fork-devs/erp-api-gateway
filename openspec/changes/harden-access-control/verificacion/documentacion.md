# Verificación de la documentación del change `harden-access-control` (task 7.x)

> La documentación vive en la **raíz del workspace**, que no es un repositorio
> Git: `docs/` no está versionado. Este archivo deja constancia de lo
> actualizado, para que el cambio sea trazable desde el repositorio.

## Task 7.1 — El ADR del diferimiento

**Fichero:** `docs/adr/ADR-001-diferir-migracion-spring-authorization-server.md`

El registro sigue la convención del proyecto (escenario medible, táctica,
patrón y alternativa descartada) y contiene:

- **Contexto:** la decisión original de `doc-microservices.md` §3.1 y la
  distancia entre ella y el código.
- **Decisión:** se difiere la migración; el emisor sigue siendo propio.
- **Motivo:** la migración cambia cuatro contratos a la vez, y acoplarla a
  la corrección de los defectos de seguridad produciría un cambio que no se
  puede revisar con garantías.
- **Alternativas descartadas:** cuatro, con su motivo.
- **Consecuencias:** positivas, negativas y neutrales.
- **Alcance de la decisión posterior:** lo mínimo que la migración futura
  tendrá que cubrir.

La decisión original que queda modificada está **citada de forma literal**
en el propio ADR:

> **Decisión:** Java/Spring Authorization Server. Gateway valida JWKS en edge
> (caché 5min), servicios revalidan claims + RLS.

## Task 7.2 — `docs/doc-microservices.md` §3.1

La sección describe ahora el emisor **real** —token propio con `roles` como
array, `aud`, `jti`, `iss` y `exp`, material de firma persistente con el
`kid` derivado, y el conjunto de claves publicado en `/.well-known/jwks.json`—
y remite al ADR-001 para la decisión diferida. Ya no afirma que el servicio
implemente un Authorization Server.

## Task 7.3 — `docs/doc-estado-implementacion.md`

Hallazgos marcados como cerrados:

| Hallazgo | Estado registrado |
|:---|:---|
| P1 6.18 — MFA: la columna existe pero el segundo factor no | ✅ CERRADO (`harden-access-control` 1.1–1.4, 2.1–2.4) |
| P1 6.19 — RBAC: el modelo existe, la aplicación no | ✅ CERRADO (`harden-access-control` 5.1–5.4) |
| P1 6.22 — `users.email` es UNIQUE global | ✅ CERRADO (`harden-access-control` 3.1–3.4) |
| P1 6.23 — Contador de intentos con lost-update | ✅ CERRADO (`harden-access-control` 4.1) |
| P1 6.24 — Enumeración de usuarios por temporización | ✅ CERRADO (`harden-access-control` 4.2) |
| P1 6.28 — No hay una sola línea de log | ✅ CERRADO (`harden-access-control` 6.1–6.3) |
| P1 7.5 — Policy de contraseña débil | ✅ CERRADO (`harden-access-control` 4.3) |

El apartado **1 bis** incorpora el change con su recuento, sus cierres, los
defectos adicionales encontrados durante la implementación y la tabla de
changes pendientes. **QA-5** se actualiza con lo que queda abierto (la
rotación no detecta reutilización, P1 6.5).

## Task 7.4 — `docs/doc-tactics.md` y `docs/doc-architecture-principles.md` §2.11

- **doc-tactics.md** recoge el contrato del token, el segundo factor y la
  aplicación de roles por endpoint, y la identidad acotada al taller sin
  enumeración de cuentas.
- **doc-architecture-principles.md** §2.11 incorpora una **tabla del estado
  real** de cada control de seguridad, con lo cerrado y lo que sigue
  pendiente. En particular, **no afirma MFA activo mientras el campo siga
  siendo inmutable**: el segundo factor está implementado y se exige en el
  inicio de sesión.

## Comprobación de que la documentación no afirma lo que no es

```
$ grep -n "MFA activo" docs/doc-architecture-principles.md
(sin resultados)

$ grep -rn "Spring Authorization Server" docs/doc-microservices.md
69:  * **Decisión (diferida):** la migración a Java/Spring Authorization Server **queda diferida**, …
```

La única mención al Authorization Server en `doc-microservices.md` §3.1 es
la que declara su **diferimiento**.
