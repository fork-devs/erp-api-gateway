# Tasks

## 1. erp-integration-hub — Base de datos: huella criptográfica, avisos procesados e identidad de servicio

- [x] 1.1 Crear la migración `V3__extend_idempotency_and_webhooks.sql` en `erp-integration-hub/src/main/resources/db/migration/`: añadir a `idempotency_keys` la columna de huella criptográfica de la carga útil y una columna de estado de la clave, y crear la tabla de avisos procesados con el identificador de evento como clave única, su marca de tiempo y su taller. Verificar que la migración se aplica y que la restricción de unicidad de `(tenant_id, scope, key_val)` **se conserva**.
- [x] 1.2 Añadir el control de concurrencia optimista a `IdempotencyKeyJpaEntity` y eliminar la asignación manual de identificador, de modo que la base de datos lo asigne. Verificar con un test de persistencia que un registro creado y después actualizado conserva su identificador y no genera una segunda fila.
- [x] 1.3 Incorporar la columna de estado de la clave de idempotencia a `IdempotencyRecord` y a su mapper, con los valores de en curso, completada, liberada para reintento y rechazada. Verificar con un test de dominio que la transición de en curso a completada es válida y que la liberación para reintento conserva el resultado anterior como no definitivo.

## 2. erp-integration-hub — La idempotencia se reclama antes de contactar al proveedor

- [x] 2.1 Añadir a `IdempotencyRepositoryPort` la operación de **reclamar** la clave, que inserta el registro y falla si ya existe, y la de **actualizar por clave de negocio** —tenant, alcance y clave—, con sus implementaciones en `IdempotencyPersistenceAdapter`. Verificar con un test de persistencia que reclamar dos veces la misma clave falla la segunda, y que actualizar un registro existente no inserta una fila nueva.
- [x] 2.2 Cambiar `PaymentIntegrationService.procesarPago` (`erp-integration-hub/src/main/java/com/erp/integrationhub/application/service/PaymentIntegrationService.java:40-114`) para que reclame la clave **antes** de contactar al proveedor, actualice el registro con el resultado al terminar, y libere la clave y reprograme la operación cuando el fallo sea transitorio. Verificar con un test de regresión que un fallo transitorio no queda cacheado como resultado definitivo y que una petición posterior con la misma clave vuelve a intentar el cobro.
- [x] 2.3 Aplicar el mismo orden —reclamar, contactar, actualizar— a `ElectronicInvoicingService.emitirComprobante` (`erp-integration-hub/src/main/java/com/erp/integrationhub/application/service/ElectronicInvoicingService.java:48-120`). Verificar con un test de regresión que un reintento tras un éxito del proveedor **actualiza** el registro de idempotencia en lugar de fallar, y que el comprobante no se reenvía.
- [x] 2.4 Añadir un test de concurrencia que lance dos peticiones simultáneas con la misma clave de idempotencia y compruebe que el proveedor recibe **una sola** llamada. Verificar que el test falla si la reclamación previa se elimina del flujo.
- [x] 2.5 Sustituir la huella de 32 bits por una **huella criptográfica** de la carga útil, y verificarla en la reproducción de una operación: una misma clave con una carga útil distinta debe rechazarse por conflicto. Verificar con un test que la clave repetida con la misma carga útil devuelve el resultado almacenado y que la clave repetida con una carga útil distinta se rechaza.

## 3. erp-integration-hub — La clave de idempotencia es obligatoria en el cobro

- [x] 3.1 Cambiar `PaymentIntegrationController` (`erp-integration-hub/src/main/java/com/erp/integrationhub/interfaces/rest/controller/PaymentIntegrationController.java:36,41-54`) para que la cabecera de clave de idempotencia sea **obligatoria**, y eliminar la derivación automática a partir de tenant, tarjeta, importe y correo. Verificar con un test de controlador que un cobro sin la cabecera se rechaza con `400` y que **no** se crea ningún intento registrado.
- [x] 3.2 Eliminar del modelo de dominio y de los adaptadores cualquier rastro de la derivación de la clave. Verificar con un grep que no queda ninguna referencia a la derivación y que ningún cobro puede llegar al proveedor sin una clave explícita.

## 4. erp-integration-hub — El resultado declarado es el resultado real

- [x] 4.1 Extraer de los cuatro adaptadores de producción (`ApiSunatElectronicInvoicingAdapter`, `ChequeaPeRucValidationAdapter`, `StripePaymentGatewayAdapter`, `SunarpVehicleValidationAdapter`) la rama que fabrica respuestas, y trasladarla a adapters simulados completos registrados por perfil de entorno. Verificar con un test de integración que, con el perfil de desarrollo, el hub opera contra el adapter simulado y con el perfil de producción, el adapter real es el registrado.
- [x] 4.2 Hacer que el arranque **falle de forma explícita** cuando haya un adapter simulado registrado en un perfil que no sea de desarrollo. Verificar con un test de arranque que el perfil de producción con un adapter simulado no levanta el contexto, y que el perfil de desarrollo sí.
- [x] 4.3 Corregir el registro de intentos para que no consigne un intento correcto cuando no se ha producido una llamada al proveedor, y para que el número de intento refleje la progresión real. Verificar con un test que un fallo anterior a la llamada al proveedor no deja ningún intento correcto registrado, y que el número de intento de la segunda ejecución es dos.

## 5. erp-integration-hub — Los estados del comprobante y la anulación

- [x] 5.1 Sustituir el texto libre del estado del comprobante por un conjunto explícito en `domain/model`, que incluya `OBSERVADO` como estado distinguible de `RECHAZADO`, y mapear el estado real que informa el proveedor a ese conjunto en `ApiSunatElectronicInvoicingAdapter`. Verificar con un test que una respuesta del proveedor en estado de observación se traduce al estado de observación y **no** a rechazo definitivo.
- [x] 5.2 Conservar el código de respuesta que el proveedor informó, en lugar de sustituirlo por un literal, en `ResultadoDeclaracion` y en la respuesta de la API. Verificar con un test que el código real del proveedor aparece en la respuesta.
- [x] 5.3 Corregir el procedimiento de anulación en `ApiSunatElectronicInvoicingAdapter.anularComprobante` para que obtenga el ticket de comunicación que el proveedor exige y lo referencie en la solicitud. Verificar con un test, sobre el adapter simulado, que la anulación incluye la referencia al ticket obtenido.
- [x] 5.4 Reprogramar en la cola de reintentos una anulación rechazada de forma transitoria, en lugar de devolverla como rechazo definitivo, y verificar que una anulación rechazada de forma definitiva sí se devuelve al cliente. Verificar con un test que cubre ambos casos.

## 6. erp-integration-hub — Numeración, reintentos y cola de muertos

- [x] 6.1 Verificar la unicidad de la combinación de taller, serie y número antes de emitir, y rechazar la emisión cuando ya exista, sin contactar al proveedor. Verificar con un test que una emisión con numeración repetida en el mismo taller se rechaza sin llamada al proveedor, y que una emisión con numeración libre se emite.
- [x] 6.2 Sustituir la escalera fija de esperas por una configurable, y elevar el número máximo de intentos hasta que la ventana total alcance las 24 horas del diseño. Verificar con un test de dominio que la suma de las esperas de la escalera alcanza la ventana configurada, y que el número máximo de intentos se lee de la configuración y no del código.
- [x] 6.3 Registrar el estado de agotado en la tabla de intentos, de modo que la profundidad de la cola de muertos sea consultable desde la bitácora. Verificar con un test que, tras agotar los intentos, existe un intento registrado con el estado de agotado.
- [x] 6.4 Añadir el caso de uso y el endpoint de **reactivación** de una tarea en cola de muertos, que restablezca su contador de intentos y vuelva a programarla. Verificar con un test de servicio y de controlador que la reactivación devuelve la tarea al estado pendiente y la reprograma.
- [x] 6.5 Añadir una tarea programada que libere las claves de idempotencia que quedaron en curso sin completarse, con una ventana de reclamation configurable. Verificar con un test que una clave en curso antigua se libera y queda disponible para un nuevo intento.

## 7. erp-integration-hub — Avisos idempotentes, con ventana temporal y secreto propio

- [x] 7.1 Registrar cada aviso procesado con su identificador de evento único y rechazar los duplicados, en `WebhookProcessingService`. Verificar con un test que la reentrega del mismo aviso se detecta como duplicada y que el efecto se aplica una sola vez.
- [x] 7.2 Escribir la actualización de estado que aporta el aviso bajo la **misma clave de idempotencia** de la operación original, en lugar de bajo el identificador de la referencia. Verificar con un test que, tras un aviso, la reproducción de la operación devuelve el estado actualizado.
- [x] 7.3 Aplicar la comprobación de la **ventana temporal** del aviso a todas las rutas de firma, no sólo a la de Stripe, y rechazar el aviso fuera de la ventana. Verificar con un test que un aviso de la ruta estándar con marca de tiempo antigua se rechaza.
- [x] 7.4 Introducir una clave de firma de avisos **propia**, distinta de la credencial de acceso al proveedor, con su propia configuración, y rechazar los avisos firmados con la credencial del proveedor. Verificar con un test que un aviso firmado con la credencial de acceso del proveedor se rechaza.

## 8. erp-integration-hub — Identidad interna acotada y límite de las consultas de identidad

- [x] 8.1 Eliminar el privilegio de administrador de plataforma que la credencial compartida concede en `InternalServiceAuthenticationFilter`, e incorporar una **identidad por servicio** con un rol acotado a las operaciones que cada servicio necesita. Verificar con un test que un servicio interno sin el rol de administración recibe `403` al invocar una operación de administración, y que con el rol la ejecuta.
- [x] 8.2 Registrar en la bitácora **quién** ejecuta cada operación de administración, con la identidad del servicio. Verificar con un test que la operación registrada incluye la identidad del servicio que la ejecutó.
- [x] 8.3 Aplicar un **límite de peticiones por taller** a los endpoints de validación de identidad, e informar al cliente del tiempo de espera cuando se alcance. Verificar con un test que la consulta por encima del límite se rechaza con la cabecera de espera, y que la consulta dentro del límite se atiende.

## 9. Limpieza y verificación transversal

- [x] 9.1 Eliminar el bloque de código muerto de configuración de proveedor: la tabla `provider_configs` y su migración de semilla, el modelo `ProviderConfig`, la entidad, el repositorio, el adapter y el port, y corregir la enumeración de proveedores del dominio para que refleje los que existen. Verificar con un grep que no queda ninguna referencia a la configuración de proveedor en el código, y que la migración de eliminación de la tabla se aplica.
- [x] 9.2 Ejecutar la suite del hub y verificar que la exigencia de la clave de idempotencia, la actualización del registro y el nuevo flujo de reintentos no rompen los tests existentes, ajustando los que construyen peticiones sin la cabecera. Verificar que la suite completa pasa y que ningún test se ha convertido en un `skip` ni ha perdido aserción.
- [x] 9.3 Actualizar `docs/doc-estado-implementacion.md` para marcar como cerrados los P0 5.9, 5.10 y 5.11, y los P1 6.3, 6.4, 6.10, 6.40, 6.41, 6.42, 6.43, 6.45, 6.46, 6.47, 6.48, 6.49, 6.50, 7.22, 7.24 y 7.35, y dejar constancia de los que quedan abiertos y de su change asociado. Verificar que el documento no afirma que ninguno de esos defectos está cerrado mientras no lo esté.
- [x] 9.4 Producir el registro de decisión arquitectónica que fija **quién posee la numeración de comprobantes** y **quién ejecuta la anulación**, con el contexto, la decisión, el motivo y las alternativas descartadas, y referenciarlo desde `docs/doc-microservices.md` §3.8. Verificar que la sección de integración del documento remite a la decisión y que la responsabilidad de la numeración queda enunciada de forma inequívoca.
