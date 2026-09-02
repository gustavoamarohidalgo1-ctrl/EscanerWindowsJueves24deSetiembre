# Room como fuente única y resiliencia offline

## Contrato

El flujo funcional de FacturaStock no necesita conectividad. Captura/importación, OCR,
parseo, revisión, vinculación, preparación, publicación, lista de compras e inventario leen y
escriben almacenamiento privado y Room. La UI no consume respuestas de red: las pantallas
durables reciben `Flow` de repositorios Room y las acciones escriben primero la copia local.

Room es la única fuente de los datos de negocio y del flujo (borradores, revisión, catálogos,
compras, inventario, auditoría y outbox). DataStore se limita a preferencias globales no
relacionales —negocio activo, onboarding y política futura—; no guarda una segunda copia de
facturas, compras o stock ni actúa como caché de red.

El APK elimina el permiso `android.permission.INTERNET`. El OCR latino está integrado en el
APK, las imágenes se resuelven desde `filesDir` y el proyecto no configura Retrofit, Firebase,
API key ni backend. La prueba instrumentada comprueba tanto la ausencia del permiso como la
reapertura y recuperación de repositorios locales; no cambia la conectividad global del
dispositivo y no usa un mock de red.

## Guardado local y respaldo

Son garantías distintas y nunca se presentan como equivalentes:

- **Guardado en este celular** significa que la transacción Room terminó. El dato ya puede
  consultarse sin conexión y sobrevive al cierre del proceso.
- **Respaldado** significa que la outbox local conserva un acuse exitoso (`SYNCED`) de un
  adaptador remoto. Estar sin conexión no revierte ni oculta la copia local.

El proyecto implementa la outbox durable, su observación, su recuperación y el worker
WorkManager que la drena (`data/sync`). El flavor `local` usa
`UnavailablePurchaseBackupTransport`; el flavor `cloud` incluye Firebase configurable, aunque
el repositorio no contiene ni prueba un proyecto productivo desplegado. Sin configuración una
compra queda en `PENDING_SYNC`: está guardada y es plenamente utilizable en el teléfono, pero no
se afirma que tenga respaldo externo. El protocolo completo —claim con
token y lease, backoff exponencial durable, acuse validado por clave idempotente— está
probado contra transportes de guion y documentado en
[`BACKUP_SYNC.md`](BACKUP_SYNC.md).

El protocolo de concurrencia exigido para el transporte ya existe: cada claim PROCESSING
lleva `claimToken` y `claimLeaseUntil` (esquema v15), completar o fallar exige ese token, y
la recuperación devuelve a PENDING los claims sin lease (legados) o con lease vencido, tanto
al arranque como en caliente al inicio de cada pasada.

## Estados observables de respaldo

| Estado | Significado local | Acción recuperable |
| --- | --- | --- |
| `DRAFT` | Compra local histórica sin operación de outbox | Sigue consultable; encolar cuando exista política de respaldo |
| `PENDING_SYNC` | Outbox durable pendiente de conectividad/procesamiento | Ninguna; continuar trabajando offline |
| `SYNCING` | Un procesador reclamó la operación por CAS | Un reinicio reencola el claim abandonado |
| `SYNCED` | El acuse de respaldo fue persistido en Room | Ninguna |
| `ERROR` | Fallo recuperable persistido y sanitizado | Reintentar; el doble toque no duplica la operación |
| `CONFLICT` | El destino requiere conciliación explícita | Revisar el impacto y reintentar explícitamente |

`attemptCount`, `lastError`, `updatedAt`, `operationId` e `idempotencyKey` viven en Room. Un
reintento cambia `ERROR/CONFLICT → PENDING_SYNC` mediante CAS, conserva identidad y contador, y
una segunda pulsación devuelve `AlreadyPending`. `SYNCING` huérfano vuelve a `PENDING_SYNC` al
arranque, sin fabricar una operación nueva.

## Auditoría de lecturas de pantalla

| Superficie | Fuente local observable | Persistencia relevante |
| --- | --- | --- |
| Inicio / borradores recientes | `InvoiceDraftRepository.observeDrafts` | borrador, estado, error OCR y destino de reanudación |
| Vista previa | `InvoiceDraftRepository.observeImages` | orden, ruta privada, hash, rotación y recorte |
| Revisión de cabecera | `InvoiceHeaderReviewRepository.observe` | snapshot revisionado y proyección del borrador |
| Revisión de líneas | `InvoiceLinesReviewRepository.observe` | celdas, selecciones, tombstones y enlaces |
| Catálogos y vinculación | consultas/Flows Room del negocio activo | proveedor, producto, unidad, almacén y alias |
| Resumen y preparación | `Flow` del borrador + snapshot `prepared_purchases` validado desde Room | hash lógico, decisiones y ajuste motivado |
| Lista, éxito y detalle de compras | `PurchaseReadRepository` mediante `Flow` | compra, líneas, movimientos, auditoría, imagen y outbox |
| Inventario y producto | `InventoryReadRepository` mediante `Flow` | libro de movimientos y proyección de saldos |
| Estado de respaldo | `PurchaseBackupRepository.observe` | última operación outbox de la compra |

La vinculación vuelve a resolver la lista cuando `InvoiceLinesReviewRepository.observe`
reemite; la pantalla de resumen observa `PreparedPurchaseRepository.observe` y cada invalidación
durable del borrador recalcula bloqueos y totales sin borrar la aceptación o el motivo que el
usuario todavía está editando. Configuración
combina el `Flow` de preferencias con `BusinessRepository.observeById`, y cada búsqueda de
catálogo observa su página Room. Las llamadas puntuales restantes son comandos (guardar,
confirmar, reordenar, reintentar) o lecturas internas de una operación, no fuentes de UI.

Lista, éxito y detalle muestran siempre dos bloques independientes: **Guardado en este
celular** y **Respaldo**. Solo `ERROR` y `CONFLICT` ofrecen reintentar; la acción hace CAS sobre
la outbox Room y la pantalla espera su siguiente emisión, sin consumir una respuesta remota.
Los detalles internos de `lastError` no se imprimen: la UI usa textos recuperables sanitizados.

Los puertos que mantienen una lectura puntual (`find`) se usan dentro de comandos o para crear
un snapshot consistente; no son una fuente remota ni entregan datos directamente a un
Composable. Los argumentos de navegación solo transportan IDs, nunca agregados en memoria.

## Recuperación por etapa

### Captura e importación

Al tocar **Escanear factura**, la app crea primero un borrador `CREATED` en Room y solo después
abre cámara o galería. Por eso incluso un fallo de espacio en la primera imagen deja una sesión
visible y reanudable en Inicio; la navegación nunca es la única copia de su identidad.

La importación escribe un temporal privado, valida/limpia la imagen y la mueve al destino final
antes de insertar sus metadatos. Si falla antes del commit, no crea una página parcial; al
recapturar, la página anterior se conserva hasta que la sustituta quede persistida. Temporales
`import-*.tmp` con más de una hora se limpian al siguiente arranque sin tocar originales.

La página, la invalidación de una preparación anterior, el `updatedAt` y la transición
`CREATED → CAPTURED` se publican mediante `publishCapturedPage` en una sola transacción Room.
Source y Camera reservan antes del picker/obturador un `ImageId` en `SavedStateHandle`: si el
proceso muere después del commit pero antes de navegar, el recibo durable de captura reconoce
exactamente el par ID+intención. Un REPLACE conserva además la ruta anterior pendiente para que
el replay reintente su limpieza sin recopia; reutilizar el mismo ID como APPEND u otro target se
rechaza. Sus ViewModels abren Preview una vez. Una ruta fresca de **Añadir página** no tiene
esa intención pendiente y, aunque el borrador ya esté `CAPTURED`, no rebota a Preview.

Si el proceso termina mientras CameraX todavía no entregó bytes, no existe contenido que pueda
persistirse: la acción segura es volver a capturar. Desde que la importación publica la página,
el borrador y el archivo sobreviven al reinicio.

### OCR

Room reclama un `activeOcrRunId` antes de procesar. El snapshot OCR y `OCR_READY` se publican en
una sola transacción. Una muerte durante el trabajo deja `OCR_PROCESSING` con su token durable;
Inicio y la propia ruta OCR restaurada ofrecen reanudar/repetir y el CAS lo devuelve a
`CAPTURED`. Si el parseo ya terminó, una ruta `NEEDS_REVIEW` abre Revisión directamente y no
repite el motor. Antes de otro preprocesado se eliminan runs derivados no publicados, conservando
siempre el señalado por el manifiesto vigente; así un run incompleto no bloquea el retry por
espacio ni sustituye evidencia completa anterior.

### Revisión

Cabecera y líneas guardan snapshots revisionados y su proyección en una transacción. Un conflicto
o fallo de disco mantiene el último baseline durable; el formulario conserva el texto local y
ofrece reintentar. Los tombstones, IDs y enlaces confirmados sobreviven a recreación y reinicio.

### Publicación

`ConfirmPurchaseUseCase` termina en una única transacción Room: compra, líneas, proveedor,
productos/alias, movimientos, saldos, auditoría, outbox y `COMMITTED`. Un fallo final revierte
todo, deja `READY_TO_POST` y el mismo comando puede reintentarse. La clave idempotente estable
convierte un reintento posterior al commit en `AlreadyPosted`. Si el proceso muere después del
commit pero antes de consumir el efecto, Preparación observa el `confirmedPurchaseId` durable y
abre la compra una sola vez, sin volver a ejecutar preflight ni publicación.

### Respaldo

Publicar la compra no espera la red. La outbox nace dentro del mismo commit en
`PENDING_SYNC`. Un claim interrumpido se reencola cuando su lease vence —al inicio de la
siguiente pasada o del siguiente arranque— y `ERROR/CONFLICT` conservan la compra local
completa mientras se ofrece reintentar. Publicar, anular o reintentar encolan el drenado
único (KEEP) con constraint de red: recuperar conectividad dispara el procesamiento sin que
la UI consuma ninguna respuesta remota.

## Poco almacenamiento

- Escritura de archivos: `ENOSPC`/`No space left` se traduce a
  `FileError.InsufficientSpace`; se borra solo el temporal o destino incompleto.
- Escritura Room: `SQLiteFullException`, `SQLITE_FULL` y `ENOSPC` envuelto se traducen a
  `StorageError.InsufficientSpace`; la transacción revierte.
- En captura se conserva la página previa; en OCR se conservan originales y no se publica un
  snapshot parcial; en revisión queda el baseline durable; en publicación no aparecen compra,
  stock ni outbox parciales.
- Liberar espacio y pulsar **Reintentar** repite la misma operación o clave, no crea otro
  borrador ni otra compra.
- Origen/cámara distinguen falta de espacio de una imagen inválida; cabecera, líneas, resumen,
  confirmación y anulación mantienen el valor o snapshot visible y explican **Libera espacio y
  reintenta**.

## Evidencia automatizada

- `OfflineRoomRestartRepositoryTest`: paquete sin `INTERNET`, archivo y metadatos de captura,
  OCR interrumpido, revisión y outbox sobreviven a cerrar/reabrir una base Room real en disco;
  una búsqueda paginada de productos reemite al insertar/editar y conserva el límite.
- `StorageErrorTranslationTest`: `SQLiteFullException`, `SQLITE_FULL` envuelto y `ENOSPC`
  producen `StorageError.InsufficientSpace`.
- `ImportDraftImageUseCaseTest`: poco espacio durante recaptura conserva página y borrador.
- `InvoiceDraftRepositoryTest` y `CapturedPageAtomicRestartTest`: la captura avanza y reemplaza
  atómicamente; un fallo inducido en el último `UPDATE` restaura página/preparación y el commit
  normal sobrevive a cerrar y reabrir Room.
- `SourceViewModelTest` y `CaptureViewModelTest`: galería/cámara restauradas reconocen su
  `ImageId` comprometido sin repetir la importación; **Añadir página** no se auto-redirige.
- `RunInvoiceOcrUseCaseTest`: poco espacio conserva originales y deja un estado reintentable sin
  snapshot parcial.
- `OcrViewModelTest`: el token interrumpido se recupera por CAS, un token nuevo no se limpia y un
  borrador ya parseado abre Revisión sin repetir el motor.
- `InvoiceHeaderReviewViewModelTest`: el fallo por espacio no elimina el baseline y el retry
  publica el texto local.
- `InvoiceLineReviewViewModelTest`: los `Flow` de cabecera/líneas actualizan un editor inactivo y
  un fallo por espacio conserva la edición optimista hasta reintentar el autosave.
- `PurchaseSummaryViewModelTest`: el fallo al congelar conserva borrador, cabecera y líneas; la
  inspección Room habilita preparar de nuevo después de liberar espacio y reacciona a cambios
  externos sin perder las entradas locales.
- `PreparationViewModelTest`: el fallo de almacenamiento al publicar conserva `READY_TO_POST`,
  hash y comando; el retry reutiliza exactamente la misma identidad y una recreación post-commit
  navega una vez sin publicar otra compra.
- `PurchaseVoidViewModelTest`: la reversa fallida por falta de espacio conserva impacto, motivo y
  confirmación para reintentar la transacción completa.
- `PurchasePostingDaoTest`: un fallo inducido en el último paso revierte todo; al retirarlo, el
  mismo comando publica una sola compra y el siguiente retry es idempotente.
- `ProcessPurchaseBackupOutboxUseCaseTest` y `BackupBackoffPolicyTest`: el drenado clasifica
  éxito, 5xx con backoff exponencial y agotamiento, permanente, conflicto y acuse inválido sin
  red; dos procesadores concurrentes no duplican y la cancelación se relanza intacta.
- `OutboxOperationDaoTest`: el CAS de claim es único, completar/fallar exige el token del
  intento y la recuperación solo toca claims sin lease o con lease vencido.
- `PurchaseBackupSyncWorkerTest`: con el constraint de red satisfecho (TestDriver) el trabajo
  único procesa la cola; sin transporte configurado no se encola nada.
- `MigrationTest` (14→15): las columnas `claimToken`/`claimLeaseUntil`/`payloadVersion` se
  añaden conservando filas y estados.

Las pruebas de reapertura y transacción instancian repositorios Room/locales directamente; las de
presentación usan fakes locales de los mismos puertos para aislar estados y acciones recuperables.
Ninguna necesita conectividad, servidor, token, API key ni respuesta remota. La reapertura
file-backed acredita persistencia de datos y repositorios; la restauración directa de rutas se
verifica por separado en ViewModels contra IDs, estados y tokens observados desde Room.
