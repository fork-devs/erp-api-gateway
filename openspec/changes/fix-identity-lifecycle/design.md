# Design

## Context

El emisor de credenciales es `erp-auth-service`, y los verificadores son `erp-api-gateway` y `erp-integration-hub`. El contrato entre ellos es el token JWT y el conjunto de claves públicas que el emisor publica en `/.well-known/jwks.json`. Hoy ese contrato tiene tres defectos que se refuerzan entre sí:

**El emisor no tiene una clave, tiene un generador.** `RsaKeyPairManager` construye un par RSA nuevo en su constructor. El `kid` es una constante de configuración. Las propiedades de material de clave que existen en la configuración no las lee nadie. La consecuencia no es teórica: el emisor publica un identificador estable con material cambiante, lo que hace que el identificador deje de ser un discriminante de clave.

**El verificador no refresca.** `JwksKeyManager.getPublicKey` (idéntico en el gateway y en el hub) devuelve la clave cacheada en cuanto el `kid` es conocido. Como el `kid` es constante, la rama que respeta el TTL de cinco minutos es inalcanzable y el verificador **jamás vuelve a descargar el conjunto de claves**. Los dos defectos se encadenan: el emisor cambia la clave, el verificador sigue con la anterior, y toda la autenticación falla hasta que el verificador también se reinicie.

**El verificador falla abierto.** Cuando el `kid` no está en el conjunto, `getPublicKey` cae a `getAnyPublicKey()`, que toma la primera clave de un `ConcurrentHashMap` en un orden no especificado. Es decir, ante una clave desconocida el sistema **verifica la firma contra una clave arbitraria** en lugar de rechazar el token. Y la URL del conjunto de claves es `http://` por defecto en ambos servicios, con lo que la sustitución de la clave por un intermediario es trivial en cualquier despliegue que no sobrescriba la variable.

A esto se suma un tercer plano, el del **ciclo de vida de la credencial más allá de la firma**: el token no lleva identificador único, no hay forma de revocar un token de acceso antes de que expire, el token de restablecimiento de contraseña se genera y se descarta, y el claim de roles no coincide entre emisor y verificador.

Hay una restricción del proyecto que condiciona la solución: `doc-microservices.md` §2 limita el núcleo compartido a `erp-contracts` (OpenAPI y proto) y `erp-observability` (registro, trazas, interceptores de tenant), y admite explícitamente "interceptores tenant, validadores de formato y config OTel" sin reglas de negocio. Un cliente de conjunto de claves es un **validador de formato** sin ninguna regla de negocio, de modo que **cabe** en un núcleo compartido; pero ese núcleo **todavía no existe** y crearlo es un cambio de infraestructura mayor que este change no debe arrastrar.

## Goals / Non-Goals

**Goals:**

- Que la clave de firma **persista** entre reinicios y entre despliegues, y que el sistema **no pueda arrancar** sin ella.
- Que el `kid` sea un **discriminante fiable**: distinto para cada clave y derivado de su material.
- Que una rotación de clave **no invalide** los tokens en vuelo, y que un token con clave desconocida se **rechace**.
- Que la indisponibilidad del emisor del conjunto de claves **no degrade** al verificador ni lo cuelgue.
- Que el token de acceso sea **revocable de forma efectiva** antes de expirar.
- Que el token de restablecimiento de contraseña **llegue al usuario** sin quedar expuesto en la respuesta de la API.
- Que el contrato de claims sea **uno solo**, verificable, y que un token sin roles se rechace en lugar de degradarse.

**Non-Goals:**

- No se introduce un servidor de autorización OAuth2/OIDC completo ni se migra el login federado: la decisión de arquitectura y su implementación pertenecen al change de control de acceso.
- No se implementa el segundo factor de autenticación: pertenece al change de control de acceso.
- No se implementa la detección de reutilización de refresh tokens ni la revocación de la familia de tokens: su parte atómica pertenece al change de atomicidad, y su parte semántica se aborda con el factor segundo.
- No se crea el núcleo compartido de librerías: este change corrige el componente **de forma idéntica en los dos servicios verificadores**, y su deduplicación se aborda cuando exista la infraestructura del núcleo compartido.
- No se cambia el **formato** de la respuesta del conjunto de claves más allá de publicar varias claves: el contrato JSON no cambia.
- No se toca el modelo de datos de `user_roles` más allá del mapeo de JPA: la columna que la restricción exige la introduce el change de aislamiento multi-inquilino.

## Decisions

### D1 — La clave de firma se carga de una fuente persistente y su identificador se deriva de su material

El servicio de autenticación lee el par de claves de una fuente persistente en el arranque: un fichero PEM en disco, o el contenido de una variable de entorno. Si no está disponible, **el arranque falla**. El `kid` se deriva del material mediante un hash del módulo público, de modo que el mismo material produzca siempre el mismo identificador y dos materiales distintos nunca lo compartan.

**Alternativas descartadas:**
- *Mantener la generación en el arranque yVersionar el `kid` con un contador o una fecha.* Se descartó porque el identificador seguiría sin corresponder al material: un verificador que cachea por identificador no puede distinguir "misma clave" de "clave nueva con el mismo identificador", que es exactamente el defecto actual. Además, la clave seguiría siendo efímera y los tokens emitidos antes de un reinicio seguirían siendo inválidos.
- *Almacenar el JWK en la base de datos.* Se descartó porque acopla el arranque del emisor a la disponibilidad de la base de datos, con lo que una incidencia de la base impediría emitir tokens, y porque la clave privada no debería residir en la base de datos operativa.
- *Derivado en una biblioteca de gestión de claves de hardware.* Se descarta **para este change**: es la opción correcta a medio plazo, pero exige infraestructura de bóveda que el proyecto todavía no tiene. Se deja registrada como deuda.

### D2 — El emisor mantiene un conjunto de claves con solapamiento

El emisor conoce una clave **vigente** y, opcionalmente, una **anterior**, y publica ambas en el conjunto de claves con sus respectivos `kid`. La rotación consiste en activar una clave nueva conservando la anterior durante una ventana de solapamiento, y en retirarla después. La ventana se configura, no se codifica.

**Alternativas descartadas:**
- *Publicar una sola clave y rotar de golpe.* Se descartó porque invalida de inmediato todos los tokens en vuelo, lo que en la práctica significa un cierre de sesión masivo en cada rotación, que es justo lo que el cambio de `kid` pretende evitar.
- *Publicar todas las claves históricas indefinidamente.* Se descarta porque el conjunto crece sin límite y amplía la ventana de exposición de la clave privada antigua.

### D3 — El verificador refresca por intervalo y falla cerrado ante clave desconocida

El verificador comprueba si la clave solicitada está en la caché; si lo está **y** la caché no ha expirado, la devuelve; si la caché ha expirado, **refresca primero** y después devuelve. Si tras el refresco la clave sigue sin estar, **rechaza el token**. Se elimina la caída a "cualquier clave".

**Alternativas descartadas:**
- *Mantener el retorno inmediato de la clave cacheada para un `kid` conocido.* Se descartó porque es el mecanismo que hace inútil el TTL: con el `kid` constante, el verificador nunca refresca. La corrección no es "refrescar más a menudo" sino **no devolver una clave cacheada sin comprobar la vigencia de la caché**.
- *Ante una clave desconocida, elegir la clave más cercana por fecha de publicación.* Se descarta porque no elimina la raíz del problema (la clave conocida puede ser la equivocada) y porque introduce lógica de selección ambigua.
- *Refrescar sin límite de frecuencia ante un `kid` desconocido.* Se descarta porque un atacante puede provocar una descarga por petición con un `kid` inventado, lo que convierte al emisor en un amplificador de tráfico.

### D4 — La descarga tiene tiempo de espera, conserva la caché en el fallo y acota el reintento

La descarga del conjunto de claves aplica tiempo de espera de conexión y de lectura. Un fallo **no vacía** la caché: se conserva el último conjunto válido y se registra el error. Tras un fallo, el intervalo hasta el siguiente intento se acota por un mínimo, de modo que la tasa de descargas no crezca con el volumen de peticiones.

**Alternativas descartadas:**
- *Vaciar la caché ante un fallo.* Se descartó porque deja al verificador sin ninguna clave, con lo que un fallo pasajero del emisor se convierte en una caída total de autenticación, que es el escenario que la propia arquitectura de caché pretende evitar.
- *Propagar el fallo y rechazar todas las peticiones.* Se descartó por la misma razón, y porque el TTL existe precisamente para permitir operar con una clave algo antigua.
- *Reintentar sin pausa.* Se descartó porque reproduce la tormenta de descargas descrita en el hallazgo del verificador.

### D5 — El esquema del conjunto de claves se valida al arrancar

La URL del conjunto de claves debe usar `https://` fuera del entorno local. El arranque falla si no lo cumple. El valor por defecto en claro se mantiene **únicamente** para el perfil de desarrollo local, y el perfil de producción no lo hereda.

**Alternativas descartadas:**
- *Dejar la validación al despliegue.* Se descartó porque el valor por defecto en claro es precisamente el que hace que un despliegue que olvide la variable quede en claro sin que nadie lo note. La validación en el arranque convierte el olvido en un fallo inmediato y ruidoso.

### D6 — El claim de roles es un array y su ausencia se rechaza

El token de acceso transporta `roles` como array en los tres servicios. El verificador lee ese claim y **rechaza** el token si no está presente. Se elimina el valor por defecto que degradaba a todo principal a `MECANICO`.

**Alternativas descartadas:**
- *Aceptar los dos claims, `role` y `roles`, durante una ventana de migración.* Se descartó porque perpetúa la ambigüedad que causó el defecto: mientras exista un camino que lee `role`, existirá un servicio que lo lea, y la divergencia resurgirá. El contrato cambia una vez y se documenta como incompatible.
- *Mantener el rol por defecto con el valor más restrictivo.* Se descartó porque un rol por defecto, sea restrictivo o no, es una decisión tomada por el verificador sobre un token que no dice nada de roles; lo correcto es rechazar un token incompleto.

### D7 — El token lleva `jti` y la revocación se comprueba en cada verificación

El token de acceso incorpora `jti`, un identificador único por token. El servicio de autenticación mantiene una tabla de tokens revocados, con el `jti` como clave y la fecha de expiración del token, y la consulta en cada verificación. Una tarea programada elimina las entradas cuyo token ya expiró.

**Alternativas descartadas:**
- *No revocar access tokens y confiar en su corta vida útil.* Se descartó porque el requisito de QA-5 es que un token robado deje de servir, y quince minutos de exposición total e incontrolada no lo satisfacen.
- *Mantener la denegación en Redis.* Se descarta porque el volumen es acotado por la vida del token y por el número de sesiones concurrentes, y porque el servicio de autenticación ya tiene su base de datos; añadir Redis introduciría una dependencia de disponibilidad adicional en el camino de verificación de cada petición.
- *Mantener una denegación en memoria.* Se descartó porque con varias réplicas la revocación de una instancia no alcanza a las demás.

### D8 — El emisor valida el emisor y la audiencia del token, y emite la audiencia

El token se emite con `iss` y `aud`, y los verificadores comprueban ambos contra los valores esperados, que se configuran por servicio. Un token sin `aud` se rechaza.

**Alternativas descartadas:**
- *Validar sólo el emisor.* Se descartó porque, en una arquitectura donde el mismo emisor podría servir a varios públicos, el emisor no acota el uso del token: un token emitido para un servicio puede ser aceptado por otro que comparta la confianza en la clave.
- *No emitir `aud` y validarlo sólo cuando esté presente.* Se descarta porque hace la comprobación opcional y por tanto inútil.

### D9 — El token de restablecimiento se entrega por un puerto de notificación

El servicio de autenticación incorpora un puerto de notificación propio, con una implementación por canal, y el caso de uso de solicitud de password reset entrega el token a través de ese puerto. La respuesta de la API no lo incluye. La implementación por defecto en este change es un **registrador de notificación**, que deja constancia del evento de entrega sin enviar nada; el proveedor real de correo o SMS se conecta en un change posterior.

**Alternativas descartadas:**
- *Devolver el token en la respuesta de la API.* Se descartó por la razón de siempre: expone el token a cualquier cliente que llegue a esa ruta, incluidos registros de acceso y trazas.
- *No implementar la entrega y dejar el token solo en la base de datos.* Se descartó porque es el estado actual y el resultado es que el flujo de restablecimiento **no es utilizable por nadie**.

### D10 — El cliente del conjunto de claves se corrige de forma idéntica en los dos verificadores

El componente está duplicado en el gateway y en el hub. Este change aplica **la misma corrección** en ambos, con el mismo comportamiento y la misma configuración, y añade una comprobación automatizada que verifica que ambos siguen siendo equivalentes.

**Alternativas descartadas:**
- *Extraer el componente a una biblioteca compartida en este change.* Se descartó porque la biblioteca compartida **no existe**: crearla exige decidir su empaquetado, su publicación y su versionado, que es trabajo de la tarea de contratos compartidos del plan de remediación. Un componente de seguridad copiado es un coste menor que un componente de seguridad publicado con prisa. La deduplicación se aborda en esa tarea, y la comprobación de equivalencia introducida aquí garantiza que la duplicación no diverja mientras tanto.
- *Corregir sólo el gateway y dejar el hub como está.* Se descartó porque el hub valida tokens de servicio con el mismo componente, y el mismo defecto de "clave arbitraria" aplica a la validación de las peticiones internas del hub.

## Risks / Trade-offs

- **[La clave privada pasa a estar en disco o en una variable de entorno]** → Mitigación: el fichero se crea con permisos restrictivos y fuera del repositorio; la variable de entorno la inyecta el gestor de secretos; el `.env.example` documenta el formato sin contener material real; y el arranque falla si la clave no está, de modo que nunca se opera con una clave efímera por descuido.
- **[La revocación de access tokens añade una consulta a la base de datos en cada verificación]** → Mitigación: la consulta es por clave primaria sobre una tabla pequeña, y el resultado se mantiene acotado por la limpieza de los tokens expirados. Si el volumen lo justificara, la consulta se movería a una caché; queda registrado como decisión a revisar.
- **[Rotar la clave exige un procedimiento operativo]** → Mitigación: el procedimiento es deliberar y guardar dos claves (la vigente y la anterior), reiniciar, esperar la ventana de solapamiento y retirar la anterior. La ventana se configura, y el procedimiento se documenta junto al despliegue.
- **[La duplicación del cliente del conjunto de claves entre dos servicios es una deuda viva]** → Mitigación: la comprobación automatizada de equivalencia hace que la divergencia sea detectable, y la deduplicación está prevista en la tarea de contratos compartidos.
- **[Cambiar el claim de roles rompe a los consumidores que lean `role`]** → Mitigación: el cambio es simultáneo en emisor y verificadores, y el contrato queda publicado como parte de la especificación compartida, de modo que un servicio nuevo no pueda reintroducir la lectura en singular.
- **[Exigir `https://` rompe los despliegues de desarrollo que no la configuren]** → Mitigación: la excepción es explícita y por perfil, de modo que el desarrollo local conserva su comodidad y ningún otro entorno puede arrancar en claro.

## Migration Plan

1. **Emisor: persistencia de la clave.** El servicio de autenticación lee el par de claves de la fuente configurada y deriva el `kid` del material. El arranque falla si no está disponible. La clave vigente actual se genera una única vez, con una herramienta de línea de comandos del propio servicio, y se despliega en el entorno como secreto.
2. **Emisor: conjunto de claves con solapamiento.** El endpoint del conjunto de claves publica todas las claves cargadas. El servicio acepta una clave vigente y, opcionalmente, una anterior.
3. **Emisor: `jti` y `aud`.** El token pasa a llevar `jti` y `aud`, y el claim de roles pasa a ser `roles`. El nuevo `aud` invalida los tokens emitidos antes del despliegue, lo que produce un cierre de sesión en ese momento, **aceptable y único**, porque ocurre en el despliegue de la corrección y no de forma recurrente.
4. **Emisor: tabla de tokens revocados.** Migración que crea la tabla con `jti` como clave primaria, `user_id`, `expires_at` e índice por `expires_at`, más la tarea de limpieza.
5. **Emisor: entrega del token de restablecimiento.** Se incorpora el puerto de notificación y su implementación por defecto, que deja constancia de la entrega.
6. **Verificadores: cliente del conjunto de claves.** Se aplica la corrección en el gateway y en el hub: refresco por intervalo antes de devolver la clave cacheada, rechazo ante clave desconocida, tiempo de espera en la descarga, conservación de la caché en el fallo e intervalo de reintento acotado.
7. **Verificadores: claim de roles y audiencia.** La lectura del claim pasa a `roles` como array, se elimina el valor por defecto, y se validan `iss` y `aud`.
8. **Verificadores: esquema cifrado.** La validación del esquema se aplica al arrancar, con la excepción del perfil de desarrollo local.
9. **Borde: ruta de gestión de usuarios.** Se añade `/api/v1/users` a la tabla de rutas y se corrige la lista de rutas públicas.
10. **Verificación.** Se activan los tests de persistencia de la clave entre reinicios, de validación con la clave anterior durante el solapamiento, de rechazo ante clave desconocida, de comportamiento ante un conjunto de claves inalcanzable, de revocación efectiva del access token y de enrutamiento de la gestión de usuarios.

**Reversión.** La migración de la tabla de tokens revocados es aditiva y su reversión es un `DROP TABLE` sin pérdida de información relevante, porque los tokens revocados caducan solos. La reversión del cambio de claim y de audiencia **no es transparente**: al volver a la versión anterior, los tokens emitidos con la nueva forma no son aceptados por la versión anterior, y los emitidos por la versión nueva con `aud` tampoco lo son, porque la anterior no exige ese claim. La reversión exige por tanto reiniciar las sesiones, igual que el despliegue.

## Open Questions

- ¿La denegación de access tokens debería residir en Redis en lugar de en la base de datos, si el volumen de peticiones crece? La decisión actual es la base de datos. No cambia el comportamiento exigido por las specs de este change, y por tanto no bloquea su implementación.
- ¿El procedimiento de rotación de clave debería ser automático, con una tarea programada que la active, o manual, con un operador? La decisión actual es manual. La automatización exige decidir dónde reside la clave anterior y cómo se audita, y no es necesaria para corregir el defecto.
- ¿El emisor debe convertirse en un servidor de autorización OAuth2/OIDC completo, con descubrimiento, introspección y cliente de credenciales? La decisión actual es que no en este change; se aborda en el change de control de acceso, donde ya se decide el modelo de aplicación de roles.
