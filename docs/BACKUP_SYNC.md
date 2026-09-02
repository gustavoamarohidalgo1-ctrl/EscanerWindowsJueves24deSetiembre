# Respaldo de compras, ventas, deudas e inventario compartido

## Contrato

Publicar o anular una compra escribe su operación de respaldo **dentro de la misma
transacción Room** (`SYNC_PURCHASE` / `SYNC_PURCHASE_VOID`). El drenado corre después, de
forma eventual e idempotente, sin bloquear al usuario ni exigir conectividad. La UI nunca
lee la respuesta del transporte: observa la fila Room (`PurchaseSyncState`) y solo ofrece
reintento manual en `ERROR`/`CONFLICT`.

El flavor `local` enlaza `UnavailablePurchaseBackupTransport` (`configured = false`): no encola
trabajo de red ni marca una compra `SYNCED`. El flavor `cloud` enlaza
`FirebasePurchaseBackupTransport` y solo confirma tras el acuse del callable real. En ambos casos
ninguna compra se marca `SYNCED` sin acuse; el protocolo también se prueba con transportes de
guion y Firebase Emulator Suite.

La publicación/anulación de compras conserva ese modelo offline-first. La venta y el pago de una
deuda son distintos cuando
el negocio tiene un enlace cloud durable: `postSale` debe autorizarla y descontar el saldo remoto
antes del commit Room, y `recordDebtPayment` debe avanzar la versión y el saldo por cobrar remoto
antes de guardar el pago local. Si el respaldo está apagado, no hay sesión activa o la red no
responde, la mutación enlazada falla cerrada como «conexión requerida»; permitir una venta o pago
solo local bifurcaría el estado entre dispositivos.

## Modelo durable (`outbox_operations`)

Cada fila modela: `operationId` (UUID), `businessId`, `purchaseId` nullable, `operationType`,
`payloadVersion` (versión del payload mínimo JSON), `payload`, `idempotencyKey` única,
`status`, `attemptCount`, `nextAttemptAt` (próxima ejecución), `completedAt`, `lastError`
sanitizado, `claimToken` y `claimLeaseUntil`. Desde v16 también `conflictRemotePurchaseId` y
`conflictReceiptId` (solo IDs, presentes únicamente en CONFLICT). Las claves e IDs se derivan
por SHA-256 del agregado: un reintento tras un commit exitoso regenera las mismas claves y
la unicidad resuelve la carrera.

Para `SYNC_PURCHASE` hay tres contratos deliberadamente compatibles:

| Outbox | Documento remoto | Uso |
| --- | --- | --- |
| `payloadVersion=2` | `document.version=1` | Replay legacy exacto; no añade tax, procedencia ni excepción |
| `payloadVersion=3` | `document.version=2` | Replay legacy exacto con tax/evidencia, procedencia y excepción opcional |
| `payloadVersion=4` | `document.version=3` | Alta vigente; añade identidad semántica de almacén y costo aplicado exacto para el inventario compartido |

Las compras nuevas crean v4/documento v3. Este conserva el contrato tributario de v2 y exige en
cada movimiento `movement.locationName` y `movement.appliedCostTotal`; el total debe ser no
negativo y corresponder exactamente con el costo unitario de la línea usando división decimal
`HALF_EVEN` a escala 18. Una outbox v2 o v3 existente conserva byte/lógicamente su versión: pudo
haber llegado al servidor y perder su ACK, por lo que reescribirla cambiaría el hash y rompería
idempotencia. Functions permite su replay exacto si el hecho ya existe, pero rechaza una alta nueva
legacy con `INVENTORY_WIRE_MIGRATION_REQUIRED`. Tampoco se hace backfill ciego de
`UNKNOWN_LEGACY` ni se inventa la decisión tributaria o el costo exacto de una línea histórica.

El mismo libro durable contiene dos operaciones documentales cerradas:

| Operación | Momento | Condiciones |
| --- | --- | --- |
| `SYNC_DOCUMENT_UPLOAD` | Después de publicar la compra o durante el bootstrap idempotente | Respaldo comercial y documental activos, negocio enlazado y política que aún conserve la imagen. Depende de que `SYNC_PURCHASE` esté `COMPLETED` |
| `SYNC_DOCUMENT_PURGE` | Antes de borrar localmente una imagen que pudo respaldarse, o al retirar un candidato de subida | Se persiste antes del borrado local y también depende del alta remota de la compra. Puede drenarse aunque el respaldo comercial esté apagado |

La preparación de subida lee la imagen privada mediante `RetainedImageStore`, genera un JPEG
derivado en memoria y escribe directamente un artefacto temporal AES-GCM; nunca crea un temporal
plaintext. Android lo entrega al callable por HTTPS. Functions vuelve a validar identidad,
membresía, rol, compra terminal, checksum, tamaño y firma JPEG antes de escribir con Admin SDK.
Un preflight transaccional anterior a Sharp descarta replays, `imageId` ya ocupado/purgado y
cuotas llenas; además reserva un lease idempotente y aplica límites pseudónimos de 60 uploads/h
por actor y 500/día por negocio. Una reserva ya validada reutiliza dimensiones y un fallo JPEG
definitivo se recuerda 48 h, evitando volver a decodificar el mismo cuerpo. La reserva final relee
de nuevo control-plane y cuotas documentales para seguir siendo la autoridad frente a carreras.
Storage aplica cifrado administrado en reposo: el protocolo no se presenta como E2E. El SDK cliente
de Storage solo tiene lectura autorizada; `storage.rules` rechaza sus escrituras y borrados.
La fuente se limita por tamaño antes de reservar el buffer y el decode se muestrea a un máximo de
4 millones de píxeles; un agotamiento de memoria se convierte en
`DOCUMENT_PREPARATION_RESOURCE_LIMIT`, permanente y sin causa/ruta. Al hacer durable una purga se
elimina y verifica el `.fse` local sin esperar el ACK remoto. Si falla, queda un estado local de
retry y el sweep de reinicio elimina cualquier derivado sin upload abierto.

Una purga `PENDING` o `PROCESSING` significa «eliminación de la nube pendiente». Solo el ACK que
cierra la fila como `COMPLETED` permite mostrar «eliminada de la nube». `DURABLE` en el ciclo de
retención describe únicamente que la intención quedó guardada en Room, no que el objeto remoto ya
desapareció. Una operación legacy ya intentada sin `targetCloudBusinessId` no puede adoptar el
binding actual: se clasifica `LEGACY_DESTINATION_UNKNOWN`, conserva el archivo local y exige
revisión manual.

## Ledger de inventario compartido, ventas y cuentas por cobrar

El saldo cloud se identifica por `remoteProductId + canonicalLocationName`, no por el UUID local
del almacén. `locationId`/`sourceLocationId` queda como evidencia de origen; otro teléfono resuelve
el producto mediante sus enlaces de catálogo y el almacén por nombre canónico. Cada compra v4,
anulación y venta cambia sus `inventoryBalances` y agrega una entrada a
`inventorySyncChanges` dentro de la misma transacción Firestore. El balance proyectado contiene
`productId`, `locationName`, `quantityOnHand`, `averageUnitCost`, `currency`, `version`,
`updatedAtMillis` y `seq`.

- El alta v4 aplica la cantidad de cada movimiento; una entrada positiva recalcula el promedio
  ponderado con `movement.appliedCostTotal` exacto. Una anulación revierte cantidad y conserva el
  promedio vigente.
- `postSale` acepta `payloadVersion=1`/documento v1 para una venta al contado y
  `payloadVersion=2`/documento v2 para una venta a crédito, con membresía
  OWNER/ADMIN/OPERATOR, moneda y productos sincronizados. El documento v2 añade la identidad
  determinística de la deuda, el nombre canónico del deudor y el vencimiento opcional. Rechaza dos
  líneas con la misma pareja producto+almacén canónico y descuenta todos los balances en una sola
  transacción; si uno no alcanza devuelve `INSUFFICIENT_STOCK` con producto, almacén, cantidad
  solicitada y disponible, sin commit parcial. En crédito, la misma transacción crea la deuda
  `OPEN` por el total exacto; nunca queda una cuenta sin su venta.
- El ACK idempotente de una venta al contado devuelve
  `{ receiptId, idempotencyKey, status, seq, postedAtMillis, balances }`; en una venta a crédito
  añade la instantánea autoritativa `debt`. El primer commit fija
  `postedAtMillis` efectivo como un instante no anterior al request, a los balances de apertura ni
  al reloj de Functions; el replay devuelve exactamente el valor guardado.
- `recordDebtPayment` exige la misma membresía mutante, deuda `OPEN`, moneda y versión esperadas,
  importe positivo y no superior al saldo. En una transacción inserta el pago append-only, reduce
  el saldo, marca `PAID` al llegar a cero y publica `DEBT_PAYMENT` sin modificar inventario. La clave
  `debt-payment:v1:<debtId>:<paymentId>` hace que un ACK perdido se reproduzca sin cobrar dos veces;
  dos teléfonos que parten de la misma versión no pueden ganar el mismo CAS.
- Si hay compras legacy sin saldo cloud, Functions falla cerrado. Android intenta una sola vez
  `bootstrapInventoryBalances` con autenticación reciente y rol OWNER/ADMIN: envía los nombres de
  almacén y el snapshot Room actual. El servidor exige 1..200 balances, hasta 200 compras legacy,
  productos/monedas válidos, cantidad no negativa y nunca superior a las compras activas. La
  diferencia se audita como `legacyUnreplicatedSales`; el promedio inicial es el snapshot
  administrativo porque el wire antiguo no llevaba `appliedCostTotal`. Un ledger vacío o compuesto
  solo por compras anuladas responde `INVENTORY_BOOTSTRAP_NOT_REQUIRED` y no crea metadata/feed.

## Concurrencia: claim con token y lease

Dos workers nunca duplican una operación:

1. `claim` es un CAS (`PENDING → PROCESSING`) que incrementa `attemptCount` de forma durable
   y graba `claimToken` (UUID) y `claimLeaseUntil` (5 min,
   `BackupBackoffPolicy.CLAIM_LEASE_MILLIS`).
2. `complete` y `fail` exigen ese token: un resultado que llega sin el token del intento
   afecta cero filas. El ganador del CAS es el único que puede cerrar su intento.
3. Un proceso muerto deja un claim sin dueño: la recuperación (arranque y cada pasada)
   devuelve a PENDING los claims **sin lease** (legados, anteriores a v15) o con **lease
   vencido**, conservando `attemptCount`. Un worker vivo con lease vigente jamás pierde su
   intento.
4. Si el transporte declara no estar disponible antes de cualquier IO, `release` devuelve el
   claim a PENDING **descontando** el intento que nunca salió.

## Procesamiento (`ProcessPurchaseBackupOutboxUseCase`)

Cada pasada: recupera leases vencidos, toma hasta 50 operaciones listas en orden FIFO
(`nextAttemptAt`, luego inserción) y por cada una reclama, envía el sobre (`BackupEnvelope`)
y clasifica el resultado:

| Resultado del transporte | Efecto durable |
| --- | --- |
| `Acknowledged` con `echoedIdempotencyKey` igual a la enviada | `COMPLETED` (SYNCED) |
| `Acknowledged` con eco distinto | `FAILED` permanente con `INVALID_ACK`: un acuse ajeno nunca confirma |
| `TransientFailure` (5xx, timeout) o excepción acotada | Vuelve a `PENDING` con `nextAttemptAt = ahora + backoff(attempt)` |
| `TransientFailure` con `attemptCount == 5` | `FAILED` permanente: acción manual |
| `PermanentFailure` (4xx) | `FAILED` sin próxima ejecución: acción manual |
| `Conflict` (409) | `CONFLICT`: conciliación explícita existente; se conservan los IDs del registro remoto |
| `Unavailable` | `release` sin consumir intento y fin de la pasada |

El backoff por operación es exponencial y durable: 30 s × 2^(intento−1), tope 2 h
(`BackupBackoffPolicy`). `lastError` solo persiste códigos cerrados `[A-Z0-9_]{1,64}`;
cualquier razón arbitraria del transporte se sustituye por el código genérico de su clase,
así que la cola jamás contiene cuerpos de respuesta ni contenido del comprobante.

## Pull incremental, aplicación local y reconciliación

Después del push, el flavor `cloud` drena tres flujos ordenados: catálogo, inventario compartido y
el espejo compacto de compras. Cada flujo usa su propio cursor y un límite fail-closed de **500
páginas por invocación**; si todavía queda `hasMore`, conserva el prefijo/cursor ya confirmado y
reanuda en otra pasada.

- `listChanges(businessId, sinceSeq, limit, expectedUid)` conserva el libro compacto de respaldo
  de compras. Su `sync/metadata.seq` avanza con alta/anulación, la ventana interna es 20 y cada
  `syncChanges` incluye el `movementSummary`. Android persiste las páginas en un espejo durable;
  este flujo no reconstruye el grafo completo de una compra ni modifica el ledger local.
- `listSalesInventoryChanges(businessId, sinceSeq, limit, expectedUid)` usa una secuencia
  `inventorySeq` propia, estrictamente contigua. Devuelve
  `{ changes, nextCursor, hasMore, latestSeq }`; cada cambio contiene
  `{ kind, seq, receiptId, sale, balances, syncedAtMillis }`; `DEBT_PAYMENT` añade exactamente
  `debt` y `payment`. `kind ∈ {SALE, PURCHASE, PURCHASE_VOID, DEBT_PAYMENT}`. En las mutaciones de
  inventario, `balances` representa el estado final de los saldos tocados; en un pago es vacío. En
  `SALE`, `sale` contiene cabecera, líneas y movimientos completos y puede contener la deuda inicial;
  en compra, anulación o pago es `null`. Replays idempotentes no avanzan ninguna secuencia.
- Android valida continuidad y aplica **cada página de inventario y su cursor en una sola
  transacción Room**. Resuelve productos por enlace cloud y almacenes por nombre canónico, aplica
  los balances finales y, para una venta no existente, materializa cabecera, líneas, movimientos,
  auditoría y deuda opcional sin generar una outbox de eco. En `DEBT_PAYMENT` inserta primero el
  pago histórico y después avanza la versión y el saldo de la deuda. Una referencia
  ausente/ambigua, un historial incompleto o un conflicto de grafo revierte la página completa; el
  cursor no se adelanta.
- Si una compra se anula antes de que el alta pendiente llegue a la nube, el transporte proyecta
  primero el snapshot `POSTED` congelado y después `SYNC_PURCHASE_VOID`; el void no se reclama hasta
  que el alta de esa compra esté `COMPLETED`, sin bloquear compras independientes.
- Cada intento del espejo de compras invalida primero su recibo de completitud. Solo la página
  terminal lo vuelve a acuñar junto con filas y cursor. La **reconciliación diagnóstica** exige que
  ese espejo cubra exactamente `1..completeThroughSeq`, compara identidad documental y diferencias
  de stock, y nunca corrige datos por sí sola. Esta propiedad de solo lectura pertenece al reporte
  diagnóstico, no al feed autoritativo de inventario descrito arriba.

Este mecanismo recupera balances compartidos, ventas completas, deudas y pagos presentes en el
feed, pero **no es una restauración integral del negocio**: el espejo de compras es una proyección
compacta, no recrea todo el grafo de compras, y tampoco incluye borradores, preferencias, toda la
auditoría local ni imágenes que no hayan pasado por el opt-in documental.

## Resolución de conflictos (nunca "último gana")

Un CONFLICT (p. ej. dos teléfonos publican el mismo comprobante: el servidor rechaza con
`already-exists` + `details.existingPurchaseId/receiptId`) no se resuelve solo. La pantalla
Sincronización muestra la comparación local↔nube (documento, fecha, total, estado, origen y
acuse remoto) y las únicas opciones permitidas:

- **Conservar el registro de la nube**: la operación pasa a `RESOLVED` (no se reintenta
  jamás; la compra local permanece intacta) y la decisión se audita en la misma transacción.
  Si esa alta tenía un `SYNC_PURCHASE_VOID` dependiente que nunca pudo salir, también queda
  `RESOLVED` localmente: no se anula a ciegas una compra remota que puede tener otro ID.
  (`SYNC_CONFLICT_RESOLVED`).
- **Reintentar**: reencola la operación por la vía existente (solo tiene sentido tras
  corregir la causa).

`RESOLVED` se muestra como `PurchaseSyncState.RESOLVED` ("Resuelto en la nube").

Una excepción de duplicado no usa esta resolución para saltarse el conflicto. Android envía en el
documento v3 el target, borrador, evento y motivo; Functions obtiene el rol real de membresía,
admite solo OWNER/ADMIN, revalida target/identidad/estado y crea un slot de excepción atómico sin
reemplazar `PRIMARY`. Si el target aún no llegó a la nube responde como transitorio para reintentar
en orden. El motivo solo se valida durante la llamada: Firestore conserva referencias y rol
autorizado, no UID, motivo, hash ni longitud; el detalle completo sigue en Room local.

## Despliegue compatible del protocolo

Se despliega **Functions primero**, con `postPurchase` v4/documento v3, `postSale` v1/v2,
`recordDebtPayment`, bootstrap y el feed unificado con `DEBT_PAYMENT`, manteniendo v2/documento v1 y
v3/documento v2 de compras exclusivamente para replays exactos. Después se distribuye Android, que
emite las versiones vigentes y entiende el feed. Retirar una versión legacy solo
sería seguro tras demostrar que ninguna instalación conserva operaciones pendientes; migrarlas
automáticamente no es una estrategia válida de rollout. Los negocios que ya tienen compras legacy
deben completar el bootstrap one-shot antes de su primera venta compartida o anulación legacy.

## Programación (WorkManager)

`WorkManagerPurchaseBackupScheduler` conserva dos cadenas inmediatas y follow-ups por deadline:

- `enqueue()` encadena en `purchase-backup-sync` con `APPEND_OR_REPLACE`. Si se publica una fila
  después del último snapshot de un worker todavía `RUNNING`, ese wake queda detrás de la pasada y
  no se pierde. El canal mínimo `purchase-backup-privacy-purge` usa la misma política, pero lleva
  `purgeOnly=true` y nunca ejecuta bootstrap, pull ni uploads comerciales.
- Un follow-up externo usa un nombre determinista que incluye `nextAttemptAt` y política `KEEP`:
  llamadas repetidas para el mismo instante se deduplican, mientras dos deadlines distintos se
  preservan. Si el propio request despierta antes de su T, crea exactamente un sucesor cuyo nombre
  incluye el ID del work `RUNNING`; así no colisiona consigo mismo ni amplifica wakes externos.
- Ambos canales requieren `NetworkType.CONNECTED`, por lo que recuperar conectividad dispara el
  drenado. El canal comercial espera además batería y almacenamiento no bajos; una purga explícita
  no hereda esas dos restricciones porque debe poder liberar datos remotos.
- Backoff exponencial a nivel trabajo (30 s) para `Result.retry()` (una pasada que falla por
  completo, p. ej. almacenamiento no disponible).
- Al drenar, el worker programa el follow-up con `enqueueAt(min próximo nextAttemptAt,
  próximo lease por vencer)` para que los reintentos diferidos y las recuperaciones en caliente no
  dependan de otro evento. Los tags permiten que `cancelRegular` retire solo respaldo/follow-ups
  comerciales y que `cancelAll` retire también privacidad al cerrar sesión.
- Los work requests viven en la base de WorkManager: un reinicio del teléfono no elimina la
  cola, y la recuperación de arranque reencola lo reclamado.
- **Interruptor de respaldo** (`backup_enabled` en `AppSettingsDataStore`): con el respaldo
  desactivado desde Ajustes → Privacidad y datos, el worker deja de reclamar subidas/descargas
  comerciales y vuelve a consultar la preferencia entre operaciones. La cola comercial queda
  honestamente pendiente hasta reactivarlo. Una request mínima separada sí puede drenar solo
  `SYNC_DOCUMENT_PURGE`: una solicitud explícita de privacidad no queda bloqueada por apagar el
  respaldo (ver [`PRIVACY_DATA_LIFECYCLE.md`](PRIVACY_DATA_LIFECYCLE.md)).
- **Interruptor documental** (`document_backup_enabled`): apagado por defecto y subordinado al
  master comercial. Al activarlo se reconstruyen candidatos retenidos idempotentemente; al
  apagarlo se retiran uploads abiertos y se despiertan las purgas necesarias. El worker relee la
  configuración en cada pasada para no crear nuevas subidas con una decisión obsoleta.

`FacturaStockApplication` implementa `Configuration.Provider` e instala
`BackupSyncWorkerFactory` (el inicializador por defecto se elimina del manifiesto); el worker
recibe sus dependencias por constructor vía Hilt, sin `ServiceLocator`.

## Garantías

- **Compra offline → outbox**: la fila nace en el commit; ninguna ruta de publicación la omite.
- **SYNCED solo tras acuse válido**: `complete` exige token de claim y eco idempotente exacto.
- **Repetición segura**: la clave idempotente es única y estable; un segundo envío tras un
  commit perdido se reconcilia por clave, no por duplicado.
- **Evolución segura**: v2/v1 y v3/v2 se reproducen solo como replays exactos; las altas nuevas usan
  v4/documento v3. La versión durable nunca se eleva durante un reintento.
- **Stock serializado**: compra v4, anulación y venta mutan balances y feed en una transacción;
  `postSale` impide sobreventa contra el saldo cloud y crea la deuda junto con una venta a crédito.
- **Cobro serializado**: `recordDebtPayment` usa versión esperada, saldo remoto e idempotencia; un
  pago no modifica inventario y no puede aplicarse dos veces.
- **Aplicación incremental atómica**: cada página materializa ventas y deudas, inserta pagos, aplica
  balances cuando corresponde y avanza el cursor Room en el mismo commit, con máximo 500 páginas
  por flujo y pasada.
- **Concurrencia**: CAS de claim + token + lease; probado con dos procesadores en paralelo.
- **Reinicio**: Room conserva la cola, WorkManager conserva los trabajos, el arranque recupera
  claims vencidos y reencola.
- **Privacidad verificable**: la UI deriva los estados documentales desde la outbox Room —local,
  pendiente, enviando, respaldada, error, purga pendiente o eliminada— sin mostrar rutas ni leer
  Storage directamente. El borrado local y el ACK remoto son hechos distintos.

## Evidencia automatizada

- `ProcessPurchaseBackupOutboxUseCaseTest` (JVM, sin red): éxito, acuse inválido, 5xx con
  backoff 30/60/120/240 s y agotamiento a FAILED, permanente, conflicto, sanitización,
  concurrencia sin duplicados, lease vigente/vencido, release, cancelación intacta.
- `BackupBackoffPolicyTest`: secuencia, tope y constantes de la política.
- `OutboxOperationDaoTest` (Room real): CAS de claim, complete/fail con token ajeno en cero
  filas, release, recuperación por lease, consultas de follow-up.
- `MigrationTest.migrate14To15AddsClaimProtocolColumnsPreservingRows`: columnas nuevas,
  defaults y conservación contra los esquemas exportados.
- `PurchaseBackupSyncWorkerTest`: reconectar procesa la cola (TestDriver), los wakes inmediatos no
  se pierden durante una pasada, el mismo deadline se deduplica, deadlines distintos se conservan,
  un wake temprano crea un solo sucesor, restricciones por canal, transporte ausente, backoff y
  follow-up diferido.
- `OfflineRoomRestartRepositoryTest`: la cola y el claim interrumpido sobreviven a cerrar y
  reabrir Room en disco, sin red.
- `FirebasePurchaseDocumentMapperTest`, `FirebaseSaleWireMapperTest` y
  `FunctionsErrorMapperTest`: formas legacy de replay, alta v4/documento v3, ventas al contado y a
  crédito, deuda/pago, bootstrap, feed unificado y errores cerrados.
- `postPurchase.test.mjs` y `saleInventorySync.test.mjs`: idempotencia, migración legacy,
  cálculo exacto del promedio, venta atómica/sin sobreventa, deuda inicial, pagos
  parciales/totales, sobrepago, carrera CAS, bootstrap, compra/anulación/pago en el feed y
  continuidad de `inventorySeq` contra Emulator Suite.
- `PullRemoteChangesUseCaseTest` y `RoomSharedInventoryApplicationRepositoryTest`: límite de 500
  páginas, validación del cursor, aplicación atómica de saldos y materialización idempotente del
  grafo de venta, deuda y pagos.
- Reconciliación estructurada: **41/41 JVM**, compilación AndroidTest correcta y **1/1 Compose** en
  Pixel 10a API 37 para la ambigüedad visible.
