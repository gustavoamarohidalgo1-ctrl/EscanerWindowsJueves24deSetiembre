# Outbox de respaldo de compras (sin transporte remoto)

> **Estado desde el 24 de septiembre de 2026.** La variante `cloud` y su transporte (Firebase
> Auth, Firestore, Functions, Storage y el feed de inventario compartido) se retiraron. La outbox y
> el código de drenado y pull permanecen en el código compartido (`src/main`), pero sin destino
> remoto: el flavor `local` enlaza `UnavailablePurchaseBackupTransport` y repositorios remotos no
> disponibles, WorkManager no programa ningún trabajo y cada operación queda en `PENDING_SYNC`.
> Las secciones que describían el backend cloud (ledger de inventario compartido, ventas y abonos
> remotos, respaldo documental en Storage, resolución de conflictos y despliegue del protocolo) se
> eliminaron. Las menciones a la nube que quedan son históricas: explican por qué existen ciertos
> campos, estados y tablas.

## Contrato

Publicar o anular una compra escribe su operación de respaldo **dentro de la misma
transacción Room** (`SYNC_PURCHASE` / `SYNC_PURCHASE_VOID`). Con un transporte configurado, el
drenado corría después, de forma eventual e idempotente, sin bloquear al usuario ni exigir
conectividad. La UI nunca lee la respuesta del transporte: observa la fila Room
(`PurchaseSyncState`) y solo ofrece reintento manual en `ERROR`/`CONFLICT`.

El flavor `local` enlaza `UnavailablePurchaseBackupTransport` (`configured = false`): no encola
trabajo de red ni marca una compra `SYNCED`. Ninguna compra se marca `SYNCED` sin acuse; el
protocolo se prueba con transportes de guion.

Las ventas y los abonos no usan la outbox. La autorización remota previa que exigían en un negocio
enlazado a la nube (`postSale`, `recordDebtPayment`) queda inactiva, porque la variante `local` no
puede crear ese enlace: ambos se confirman únicamente en Room.

## Modelo durable (`outbox_operations`)

Cada fila modela: `operationId` (UUID), `businessId`, `purchaseId` nullable, `operationType`,
`payloadVersion` (versión del payload mínimo JSON), `payload`, `idempotencyKey` única,
`status`, `attemptCount`, `nextAttemptAt` (próxima ejecución), `completedAt`, `lastError`
sanitizado, `claimToken` y `claimLeaseUntil`. Desde v16 también `conflictRemotePurchaseId` y
`conflictReceiptId` (solo IDs, presentes únicamente en CONFLICT). Las claves e IDs se derivan
por SHA-256 del agregado: un reintento tras un commit exitoso regenera las mismas claves y
la unicidad resuelve la carrera.

Para `SYNC_PURCHASE` hay tres contratos deliberadamente compatibles:

| Outbox | Uso |
| --- | --- |
| `payloadVersion=2` | Replay legacy exacto; no añade tax, procedencia ni excepción |
| `payloadVersion=3` | Replay legacy exacto con tax/evidencia, procedencia y excepción opcional |
| `payloadVersion=4` | Alta vigente; añade identidad semántica de almacén y costo aplicado exacto para el inventario compartido |

Las compras nuevas crean v4. Este conserva el contrato tributario de v2 y exige en cada movimiento
`movement.locationName` y `movement.appliedCostTotal`; el total debe ser no negativo y corresponder
exactamente con el costo unitario de la línea usando división decimal `HALF_EVEN` a escala 18. Una
outbox v2 o v3 existente conserva byte/lógicamente su versión: reescribirla cambiaría el hash y
rompería la idempotencia. Tampoco se hace backfill ciego de `UNKNOWN_LEGACY` ni se inventa la
decisión tributaria o el costo exacto de una línea histórica.

El mismo libro durable admite dos operaciones documentales cerradas, heredadas del respaldo de
imágenes en la nube:

| Operación | Momento | Condiciones |
| --- | --- | --- |
| `SYNC_DOCUMENT_UPLOAD` | Después de publicar la compra | Respaldo comercial y documental activos, negocio enlazado y política que aún conserve la imagen |
| `SYNC_DOCUMENT_PURGE` | Antes de borrar localmente una imagen que pudo respaldarse, o al retirar un candidato de subida | Se persiste antes del borrado local |

En la variante `local` no se generan: exigen un negocio enlazado o una imagen que pudo salir del
dispositivo.

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
| `Conflict` (409) | `CONFLICT`: conciliación explícita; se conservan los IDs del registro remoto |
| `Unavailable` | `release` sin consumir intento y fin de la pasada |

El backoff por operación es exponencial y durable: 30 s × 2^(intento−1), tope 2 h
(`BackupBackoffPolicy`). `lastError` solo persiste códigos cerrados `[A-Z0-9_]{1,64}`;
cualquier razón arbitraria del transporte se sustituye por el código genérico de su clase,
así que la cola jamás contiene cuerpos de respuesta ni contenido del comprobante.

Con `UnavailablePurchaseBackupTransport` el único resultado posible es `Unavailable`. `CONFLICT` y
`RESOLVED` solo podían producirse con el transporte cloud; la pantalla Sincronización que los
mostraba y resolvía se retiró.

## Pull incremental y reconciliación (inactivos)

`PullRemoteChangesUseCase`, la aplicación Room del feed de inventario compartido y la
reconciliación diagnóstica (`ReconcileRemoteLedgerUseCase`) siguen en `src/main` con sus pruebas,
pero la variante `local` enlaza repositorios remotos no disponibles y no programa el worker que los
invocaba, así que nunca materializan datos. Las tablas que alimentaban (`remote_sync_states`,
`remote_purchase_changes`, `remote_movement_summaries`, `remote_catalog_changes`,
`catalog_sync_links` y `cloud_business_bindings`) siguen en el esquema Room, sin migración.

Nada de esto es una restauración. La única copia completa de los datos del negocio es el respaldo
`adb run-as` descrito en [`RUNBOOK.md`](RUNBOOK.md).

## Programación (WorkManager)

`WorkManagerPurchaseBackupScheduler` sigue implementado, pero **sin transporte configurado todo es
un no-op**: `enqueue()`, `enqueueAt()`, los sucesores y el canal de purga retornan sin tocar
WorkManager, así que en la variante `local` no existe ningún trabajo de respaldo. Con transporte,
el diseño era:

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
  comerciales y que `cancelAll` retire también privacidad.
- Los work requests viven en la base de WorkManager: un reinicio del teléfono no elimina la
  cola, y la recuperación de arranque reencola lo reclamado.
- **Interruptores** `backup_enabled` y `document_backup_enabled` (`AppSettingsDataStore`): el
  worker los releía entre operaciones para no reclamar trabajo comercial ni crear subidas con una
  decisión obsoleta. Ajustes ya no ofrece ninguno de los dos.

`FacturaStockApplication` implementa `Configuration.Provider` e instala
`BackupSyncWorkerFactory` (el inicializador por defecto se elimina del manifiesto); el worker
recibe sus dependencias por constructor vía Hilt, sin `ServiceLocator`.

## Garantías

- **Compra offline → outbox**: la fila nace en el commit; ninguna ruta de publicación la omite.
- **SYNCED solo tras acuse válido**: `complete` exige token de claim y eco idempotente exacto.
- **Repetición segura**: la clave idempotente es única y estable; un segundo envío tras un
  commit perdido se reconcilia por clave, no por duplicado.
- **Evolución segura**: v2 y v3 se reproducen solo como replays exactos; las altas nuevas usan
  v4. La versión durable nunca se eleva durante un reintento.
- **Concurrencia**: CAS de claim + token + lease; probado con dos procesadores en paralelo.
- **Reinicio**: Room conserva la cola y el arranque recupera claims vencidos.

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
- `PullRemoteChangesUseCaseTest` y `RoomSharedInventoryApplicationRepositoryTest`: límite de 500
  páginas, validación del cursor, aplicación atómica de saldos y materialización idempotente del
  grafo de venta, deuda y pagos (código compartido hoy inactivo).
