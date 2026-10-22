# ADR-006 — El gateway es un proxy propio sobre Spring MVC, y no Spring Cloud Gateway

- **Estado:** Aceptado
- **Fecha:** 2026-10-21
- **Change:** `harden-gateway-edge`
- **Ámbito:** `erp-api-gateway` y, por consecuencia, toda la plataforma (el gateway es el borde único)
- **Copia canónica:** `erp-api-gateway/docs/adr/ADR-006-el-gateway-es-un-proxy-propio.md` (este repositorio; los demás guardan un espejo)

## Contexto

El gateway es el **borde único** de la plataforma: enruta, valida el token,
propaga el taller, aplica el plan del taller (rate-limit y mamparo) y comprueba
el rol **antes** de reenviar. Todo el tráfico de la plataforma lo atraviesa, y
por eso su implementación importa más que la de ningún otro servicio.

**Lo que hay hoy, verificado:** un **proxy inverso propio** sobre Spring MVC,
con Spring Security, Nimbus JOSE para el token y Caffeine para la caché del
conjunto de claves. Cuatro capas, dominio sin framework, cinco puertos
(`JwtValidatorPort`, `RateLimiterPort`, `BulkheadPort`, `ReverseProxyPort`,
`TenantQuotaPort`) y una tabla de rutas en `application.yml`. El cliente
saliente es `java.net.http.HttpClient` en modo **bloqueante** (`send`, no
`sendAsync`), sobre **HTTP/1.1**. **Cero dependencias de Spring Cloud**: el
`pom.xml` no declara ni una.

**Lo que faltaba escribir.** El catálogo (§2 de `doc-microservices.md`) solo
decía «Java Spring Boot / REST público (REST→REST)»: no decía **qué es** el
gateway, ni por qué no es Spring Cloud Gateway. En un equipo Spring, la opción
por defecto de un proxy inverso es Spring Cloud Gateway, de modo que la primera
persona que quiera «modernizar» el borde la propondrá. Este ADR existe para
que esa propuesta llegue con la decisión ya tomada y su motivo escrito.

**El problema operativo que lo acompaña.** La auditoría del borde encontró tres
defectos que **no son de arquitectura, son de operación**, y que el change
`harden-gateway-edge` va a cerrar:

- **6.56 — el techo de concurrencia son los hilos de plataforma.** Cada
  petición en vuelo retiene un hilo de Tomcat durante toda la llamada
  saliente, incluido el tiempo de espera del destino. El límite no lo pone la
  CPU: lo ponen los 200 hilos de Tomcat, y con ellos la memoria (cada hilo
  arrastra además el cuerpo completo en heap, 6.33).
- **6.57 — no hay interruptor de circuito.** Un destino caído convierte cada
  petición en una espera del timeout completo de su ruta (hasta 10 s) y
  degrada el tráfico que no tiene nada que ver con ese destino.
- **6.58 — no hay métricas por ruta.** El ASR **QA-2** (100 usuarios
  concurrentes por taller, p95 < 300 ms) **no es medible**: no hay forma de
  distinguir un p99 causado por el inventario de uno causado por el pool de
  conexiones.

Y hay una pregunta de fondo que la auditoría abrió: **si el modelo es
bloqueante, ¿no debería ser reactivo?** Es la pregunta que este ADR responde.

## Decisión

**1. El gateway sigue siendo un proxy inverso propio sobre Spring MVC.**
Spring Cloud Gateway **no** se adopta, y no se va a migrar a él.

**2. El modelo de ejecución sigue siendo bloqueante, y escala con hilos
virtuales**, no con un modelo reactivo. `spring.threads.virtual.enabled=true`
hace que el `send()` bloqueante **libere el portador mientras espera**: el
borde escala a miles de peticiones concurrentes **sin tocar el modelo de
programación** ni una línea de la lógica.

**3. La resiliencia del reenvío vive en el puerto `ReverseProxyPort`**, no en
el borde HTTP: interruptor de circuito e interruptor de tiempo por destino
(Resilience4j, `resilience4j-spring-boot4`, compatible con Spring Boot 4.1.1),
con respuesta rápida (`503`) mientras el circuito está abierto.

**4. El tope del cuerpo se aplica por los dos lados**: `spring.codec.max-in-memory-size`
para el entrante (configuración) y lectura acotada con `BodyHandlers.ofInputStream()`
para el saliente (código), porque la propiedad no cubre el lado destino.

**5. Las métricas por ruta** (Micrometer: temporizadores con percentiles,
errores por destino y estado del circuito) son parte del change, y la condición
previa de la prueba de carga que verifica QA-2.

**6. La decisión se hereda.** El servicio que se construya a continuación
—`erp-work-order-service`— hereda el patrón **ya corregido**: con un cambio de
patrón después de clonar el patrón, hay que aplicarlo siete veces.

## Motivo

**Spring Cloud Gateway es un proxy *de configuración*, y la plataforma tiene un
estándar de cuatro capas con el dominio libre de framework.** SCG son rutas
declaradas en YAML o en un DSL, más una cadena de filtros: no hay dominio, ni
puertos, ni casos de uso. Migrar significaría **borrar el `domain/` del
gateway** —`RouteTarget`, `TenantQuota`, `TokenClaims`, `BulkheadResult`— y
sus cinco puertos, para sustituirlos por un `KeyResolver` y filtros. El
gateway pasaría a ser **el único de los nueve servicios fuera del estándar**,
y con él se caerían dos propiedades de la plataforma: que todos los servicios
se puedan auditar con la misma lista de comprobación, y que un servicio nuevo
sea *una copia del anterior con otro dominio*.

**SCG no elimina ni uno de los filtros a medida.** El control por plan —cuota
traída del `tenant-service`, token bucket y mamparo por taller—, la
comprobación de rol en el borde y la validación del conjunto de claves
seguirían siendo filtros propios. SCG aporta el **enrutado**, que hoy es una
tabla de diez entradas en YAML. El intercambio sería: perder el dominio y los
puertos, ganar un enrutado declarativo que no era el problema.

**Impondría el modelo reactivo**, que es lo contrario de lo que necesita la
corrección de concurrencia. Con hilos virtuales, el código bloqueante **ya**
escala. Con Reactor, el techo pasaría a ser el número de reactores y el
complejidad de la cancelación, a cambio de reescribir el servicio que está en
la ruta del 100 % del tráfico.

**Obligaría a re-verificar el cliente del conjunto de claves.** El
`JwksKeyManager` ya corrigió dos fallos sutiles —un `kid` desconocido obliga a
recargar, y un fallo de descarga **no** vacía la caché ni actualiza la marca de
éxito (6.29, cerrado)—. Reescribir esa lógica dentro de un `GlobalFilter` es
reescribir **justo lo que costó dos hallazgos de máxima severidad**, en el
camino crítico de todas las peticiones.

**Y es un *big bang* sobre la ruta de todo el tráfico**, para ganar sobre todo
comodidad de configuración.

**Sobre el modelo reactivo, por separado.** Reescribir el gateway en WebFlux
**no quitaría el techo**: el límite seguirían siendo los hilos, solo que más
caros de escribir, en un código que además tiene que cambiar de estilo de
tests, de manejo de errores y de traza. La respuesta moderna al bloqueo **no**
es Reactor: son los hilos virtuales. El problema real del gateway no era el
modelo de programación, eran los **200 hilos** y el **cuerpo sin tope**.

**Sobre portarlo a Go.** El gateway **no es** un problema de rendimiento:
cada petición valida un JWT contra un conjunto de claves cacheado, pide la
cuota del plan al tenant-service, aplica un cubo de tokens y un mamparo por
taller, comprueba el rol y reenvía. Son milisegundos de trabajo, con decenas
de conexiones en vuelo, no miles. La ventaja de Go —throughput por núcleo y
huella por proceso— brilla **exactamente donde a esta plataforma le sobra**:
su cuello de botella es el pool de conexiones de PostgreSQL, no el borde.

El riesgo del gateway, en cambio, está en la **identidad**: sus modos de fallo
no son "va lento", son "acepta un token que no debería" o "reenvía cabeceras
sin validar", que es lo que ya ha pasado (5.1, 5.7, 6.30, 6.31). Eso se
combate con código aburrido, bibliotecas maduras y buena observabilidad, no
con un runtime eficiente. Y en Go, el borde sería un **segundo runtime en el
camino de la identidad**: otro stack de TLS, otro cliente HTTP, otra
biblioteca de JWT que auditar y otra forma de observar —justo lo que hace
falta para poder medir QA-2 (6.58).

**El principio que decide esta cuestión** es que *el lenguaje se elige por
**dónde está el riesgo**, no por dónde está el rendimiento*. En esta
plataforma el riesgo está en el **dinero y el stock** (núcleo transaccional →
Java) y en el **borde de identidad** (→ Java, porque su modo de fallo es un
defecto de seguridad y su código tiene que ser aburrido y observable). El
riesgo de rendimiento está en el **borde de datos** (→ Go). Go se gana su sitio
donde la carga es brutal; aquí la carga es trivial y Go no compra nada.

## Evidencia del sector

La decisión no es solo una opinión: el sector tiene casos públicos, y todos
dicen lo mismo en la misma dirección.

| Empresa | Borde | Tecnología | Qué pasó |
|:---|:---|:---|:---|
| **Netflix** | Zuul 1 → Zuul 2 | Spring MVC (bloqueante) → **Netty asíncrono** | Migró a asíncrono a escala de **50 000 millones de peticiones/día**; su arquitectura de entrega está construida sobre **NGINX**. Es también la casa de **Resilience4j**, el interruptor de circuito que este change va a usar |
| **Tinder** | TAG (Tinder Application Gateway) | **JVM + Spring Cloud Gateway** | Un borde sobre Spring Cloud Gateway, en producción y a escala: prueba de que SCG no es una trampa y de que un gateway JVM aguanta |
| **Shuhe Group** (fintech) | SCG → **APISIX** | Java → Lua/OpenResty | Migró **fuera** de Spring Cloud Gateway por desbordamiento de memoria y CPU alto; declara un 30 % más de rendimiento y ~60 % menos de servidores (fuente del propio proveedor de APISIX: **a tomar con sesgo**) |
| **Shopify** | Monolito Rails → **Rust** | Ruby → Rust | Reescribió el borde cuando su lenguaje base dejó de dar la talla: **489 millones de peticiones por minuto** en el borde |
| **Uber** | Gateway de 2ª generación | **Go**, dirigido por configuración | Pasó de un gateway definido en código a uno definido por configuración, a decenas de millones de RPS |

**Lo que dice el patrón:**

1. **El lenguaje del borde lo decide la escala, y la escala llega después de
   salir a producción.** Netflix, Shopify, Uber y Shuhe reescribieron su borde
   **cuando ya era el cuello de botella**, no antes de lanzarlo. El caso de
   Shuhe, en el que el problema fue la huella de la JVM, ocurrió a 80 000 QPS
   por máquina de 4 núcleos; el de Shopify, a 489 millones de peticiones por
   minuto; el de Netflix, a 50 000 millones al día. La carga de este borde es
   de milisegundos y decenas de conexiones en vuelo: **tres o cuatro órdenes
   de magnitud por debajo** de donde esas decisiones se tomaron.
2. **Un gateway JVM aguanta.** Tinder lo demuestra en producción, y Netflix
   tuvo su Zuul 1 sobre Spring MVC. La JVM no está descalificada en el borde:
   lo que la descalifica es la **huella**, y solo cuando el borde es el cuello
   de botella.
3. **Para código bloqueante, el camino que recomienda el propio framework es
   MVC con hilos virtuales**, no un modelo reactivo: Spring documentó el
   soporte de Spring Cloud Gateway sobre Spring MVC precisamente con la
   llegada de los hilos virtuales de Java 21. Es el mismo camino que sigue
   este gateway —un `send()` bloqueante sobre un hilo virtual— y es el que
   permite **no reescribir el borde** para escalar.

## Alternativas descartadas

- **Spring Cloud Gateway (variante reactiva).** Se descarta por lo expuesto:
  se pierde el dominio y los puertos del borde, no se elimina ningún filtro a
  medida, se impone el modelo reactivo, se re-verifica el cliente del
  conjunto de claves y se migra el camino crítico de todo el tráfico. **La
  evidencia del sector no está en contra**: Tinder opera un borde sobre
  Spring Cloud Gateway en la JVM, y funciona. Lo que se descarta no es la
  calidad de SCG, sino que su beneficio es el **enrutado declarativo** —que
  aquí son diez entradas en un YAML— y que su precio (perder el dominio y los
  puertos del borde, y re-verificar la lógica de identidad) es alto para lo
  que aporta.
- **Spring Cloud Gateway en su variante MVC** (`spring-cloud-starter-gateway-server-mvc`).
  Se descarta porque los dos argumentos de fondo —el estándar de cuatro capas
  y los filtros a medida— siguen exactamente igual, y porque es la variante
  menos ejercitada y con menos filtros disponibles.
- **Reescribir el gateway en WebFlux.** Se descarta porque no quita el techo
  de concurrencia y porque cambia el modelo de programación del equipo en el
  servicio que más tráfico soporta.
- **Portar el gateway a Go.** Se descarta porque el lenguaje se elige por
  **dónde está el riesgo**, y en el borde el riesgo es de identidad, no de
  rendimiento: la carga (milisegundos, decenas de conexiones en vuelo) está
  dos órdenes de magnitud por debajo de donde la ventaja de Go empieza a
  importar, mientras que un segundo runtime en el camino de la identidad
  añade superficie que auditar y una segunda forma de observar. La decisión
  **no** se apoya en que el código ya esté escrito, sino en la carga y en el
  riesgo: si la carga o el riesgo cambian, la decisión se revisa (ver las
  condiciones de revisión).
- **Dejarlo todo como está, sin endurecer.** Se descarta porque los tres
  defectos son hallazgos verificados (6.56, 6.57, 6.58) y porque la propia
  documentación de la plataforma (§4 y §4.1) **ya exige** «timeout + retry
  (1x) + circuit-breaker» en las llamadas internas: el borde es el caso más
  expuesto y el único que no lo cumple.

## Consecuencias

**Positivas.**

- El gateway conserva las cuatro capas y el mismo patrón que los otros ocho
  servicios: la lista de comprobación de conformidad sirve para los nueve.
- La corrección de concurrencia es **una propiedad**, no una reescritura.
- La resiliencia que SCG habría dado se obtiene **en la capa correcta** —el
  puerto de reenvío—, y con ella la prueba de carga que verifica QA-2.
- La decisión queda escrita: nadie propone Spring Cloud Gateway dentro de seis
  meses sin leerla.

**Negativas.**

- Se renuncia al enrutado declarativo y a los filtros «hechos de fábrica» de
  SCG: cada filtro nuevo se escribe a mano.
- El gateway mantiene su propia tabla de rutas, y esa tabla hay que
  sincronizarla con la configuración de cada servicio.

**Neutrales.**

- El endurecimiento no cambia el rendimiento de forma medible: no hay
  ninguna llamada interna de volumen apreciable.
- La revisión de esta decisión se dispara por **condiciones**, no por una
  fecha.

## Condiciones de revisión

Este ADR se revisa si se da **una** de estas:

1. **El borde tiene que transmitir en vivo** (SSE o WebSocket con difusión
   hacia el navegador, telemetría en tiempo real): ahí el modelo reactivo es la
   herramienta correcta.
2. **La tabla de rutas crece a decenas de rutas con transformaciones**: ahí el
   enrutado declarativo de SCG empieza a pagar su coste.
3. **La plataforma se estandariza en Spring Cloud** (Config Server,
   Discovery, Bus): la coherencia se invierte y la decisión se replantea.
4. **El borde tiene que sostener más de 10 000 conexiones simultáneas por
   réplica**, o el despliegue exige **scale-to-zero** con arranques en frío
   medibles, o la **huella de la JVM** aparece como límite en el presupuesto de
   la infraestructura: ahí Go pasa a ser el candidato natural. La decisión se
   apoya en la carga y en el riesgo, no en el estado del código, así que
   estos números son los que la revierten.

Ninguna de las cuatro se cumple hoy: la carga del borde es de milisegundos y
decenas de conexiones en vuelo, y el despliegue previsto no exige arranques en
frío medibles.
