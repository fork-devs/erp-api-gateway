# Verificación de la matriz de endpoint y rol (task 5.4)

> La documentación vive en la **raíz del workspace**, que no es un repositorio
> Git: `docs/` no está versionado. Este archivo deja constancia de lo
> actualizado, para que el cambio sea trazable desde el repositorio.

## La matriz

`docs/matriz-roles-endpoints.md` se **genera a partir del código** con:

```bash
python3 docs/verificar-matriz-roles.py --generar
```

y se comprueba con:

```bash
python3 docs/verificar-matriz-roles.py
```

La comprobación verifica dos cosas:

1. **Ningún endpoint de escritura se queda sin rol declarado.** Un endpoint
   de escritura sin rol es un endpoint que puede ejecutar cualquier
   principal autenticado.
2. **La matriz documentada cubre todos los endpoints.** La documentación que
   no coincide con el código es documentación que miente.

## Salida

```
Matriz generada: docs/matriz-roles-endpoints.md (28 endpoints)

La matriz cubre los 28 endpoints, y ninguno de escritura se queda sin rol.
```

## Endpoints sin rol, y por qué

| Servicio | Método | Ruta | Motivo |
|:---|:---|:---|:---|
| `erp-integration-hub` | `POST` | `/api/v1/integrations/webhooks/stripe` | Se autentica con la firma HMAC del proveedor, no con un rol del token. |
| `erp-integration-hub` | `POST` | `/api/v1/integrations/webhooks/nubefact` | Se autentica con la firma HMAC del proveedor, no con un rol del token. |
| `erp-auth-service` | `GET` | `/.well-known/jwks.json` | El conjunto de claves públicas es público por definición: lo necesitan los verificadores. |
| `erp-tenant-service` | `GET` | `/api/v1/internal/tenants/{id}/limits` | Endpoint de servicio a servicio: lo exige una credencial interna, no un rol del token. |

## Endpoints revisados que no declaraban rol

| Servicio | Endpoint | Qué se ha hecho |
|:---|:---|:---|
| `erp-auth-service` | `POST /api/v1/auth/login` | Declarado público de forma explícita: no hay principal al que exigirle un rol. |
| `erp-auth-service` | `POST /api/v1/auth/refresh` | Ídem. |
| `erp-auth-service` | `POST /api/v1/auth/forgot-password` | Ídem. |
| `erp-auth-service` | `POST /api/v1/auth/reset-password` | Ídem. |
| `erp-auth-service` | `POST /api/v1/auth/logout` | Exige un principal autenticado. |
| `erp-auth-service` | `GET /api/v1/users/me` | Exige un principal autenticado. |
| `erp-tenant-service` | `GET /api/v1/tenants/{id}` | Exige un principal autenticado, y la comprobación de que el taller es el suyo. |
| `erp-tenant-service` | `GET /api/v1/plans` | Declarado público: es el catálogo que se consulta antes de iniciar sesión. |
| `erp-integration-hub` | `POST /api/v1/integrations/payments/charge` | Exige un rol de administración o de servicio interno: mueve dinero. |
| `erp-integration-hub` | `POST /api/v1/integrations/invoices/emit` | Ídem: tiene efectos fiscales. |
| `erp-integration-hub` | `POST /api/v1/integrations/invoices/void` | Ídem. |
| `erp-integration-hub` | `POST /api/v1/integrations/invoices/status` | Exige un principal autenticado. |
| `erp-integration-hub` | `GET /api/v1/integrations/validate/ruc/{ruc}` | Exige un principal autenticado. |
| `erp-integration-hub` | `GET /api/v1/integrations/validate/placa/{placa}` | Exige un principal autenticado. |

## Discrepancia de nombres de rol, conocida

El hub espera los roles `ADMIN` y `SUPER_ADMIN`, mientras que el servicio de
autenticación emite `SUPERADMIN` y `ADMIN_TALLER`. Por eso las declaraciones
del hub aceptan el conjunto de los dos vocabularios: es una costura
inconsistente, no una decisión.

Unificar el vocabulario de roles entre servicios es un cambio de contrato, y
no cabe en este change, que no toca el modelo de roles. Queda anotado aquí
para que no se pierda.
