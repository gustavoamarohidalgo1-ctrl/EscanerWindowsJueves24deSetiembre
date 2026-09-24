# Arquitectura de FacturaStock

FacturaStock conserva un único módulo Gradle, `:app`, y separa responsabilidades mediante paquetes Kotlin. Las flechas representan la única dirección permitida para una acción iniciada por el usuario:

```text
UI (Composable) → ViewModel → UseCase → Repository (interfaz de dominio)
                                      ↓
                         implementación en data
```

La UI emite acciones y observa estado. El ViewModel traduce acciones a llamadas de casos de uso. Un caso de uso aplica reglas y solo conoce interfaces de repositorio. `data` implementa esas interfaces con Room, archivos, cámara u otros adaptadores. La inyección se resuelve en `di`; ninguna capa busca dependencias globalmente.

## Variante única: flavor `local`

El módulo define un único flavor, **`local`** (dimensión `backend`): la aplicación offline-first
completa. `src/local/AndroidManifest.xml` elimina `INTERNET` y `verifyOfflineFirstBoundaries`
exige esa eliminación; además inspecciona el modelo de configuraciones y falla la compilación si
cualquier configuración declara un módulo `com.google.firebase`. El flavor `cloud` (Firebase,
Functions, cuenta, membresías y sincronización entre teléfonos) y el build type `spark` se
retiraron el 24 de septiembre de 2026.

Los puertos remotos siguen declarados en `domain` y la lógica de outbox, drenado y pull sigue en
`src/main`, pero `src/local` los enlaza a implementaciones sin destino remoto:

- `BackupTransportModule`: `UnavailablePurchaseBackupTransport` (`configured = false`) y los
  repositorios remotos de catálogo, documentos, ventas y deudas en su versión no disponible. Sin
  transporte, el programador no encola trabajo y la outbox queda en `PENDING_SYNC`.
- `AccountModule`: cuenta, membresías y libro remoto no disponibles. La autorización de
  excepciones de duplicado y de anulaciones usa `LocalOwnerPurchaseOverrideAuthorizationRepository`,
  que representa al propietario (`OWNER`) del negocio activo.
- `ObservabilitySinkModule`: `NoOpObservabilitySink`; no hay telemetría ni reporte de fallos.

Las fuentes compartidas viven en `src/main`. Las ramas que dependían de un negocio enlazado a la
nube (checkout y abonos con autorización remota, pull incremental) permanecen en el código, pero
quedan inactivas porque la variante única no puede crear ese enlace.

## Fronteras de seguridad móvil

- El manifiesto productivo exporta únicamente `MainActivity`, porque es el launcher. El provider
  de AndroidX Startup es privado; no hay `FileProvider`, receiver o service productivo exportado.
  Cámara usa CameraX dentro del proceso y las importaciones/exportaciones usan Photo Picker/SAF con
  concesiones temporales del sistema; no se persisten URIs ni se comparte una ruta privada.
- `allowBackup=false`, `fullBackupContent=false` y `data_extraction_rules.xml` excluyen Room,
  preferencias, imágenes, tokens y claves tanto de cloud backup como de device transfer. La
  configuración de red y el manifiesto niegan cleartext; el flavor `local` elimina además
  `INTERNET`.
- `MainActivity` aplica `FLAG_SECURE` antes de componer, de modo que pantallas con facturas,
  ventas e inventario no aparecen en capturas o vistas recientes. El bloqueo biométrico opcional
  sigue implementado en `MainActivity`, pero Ajustes ya no ofrece activarlo: solo actúa si la
  preferencia `biometric_lock_enabled` quedó activa desde una versión anterior. En ese caso solicita
  biometría fuerte o credencial del dispositivo solo desde estado `RESUMED`; la credencial es la
  ruta de recuperación y un dispositivo que ya no pueda autenticar desactiva el ajuste en vez de
  bloquear los datos para siempre.
- Los lectores de códigos externos entran como teclados físicos HID: `MainActivity` solo enruta sus
  eventos mientras la sesión está desbloqueada y la pantalla Ventas está reanudada en modo lector.
  Pausa, bloqueo, cambio de modo, un diálogo o una edición pendiente invalidan la lectura parcial;
  el ensamblador no registra ni conserva el último código completo.
- Las imágenes retenidas se cifran AES-GCM mediante una clave no exportable de AndroidKeyStore;
  que tenga hardware seguro depende del dispositivo. La UI recibe bytes descifrados en memoria,
  no una ruta ni un temporal plaintext. La app no tiene cuentas: no maneja contraseñas ni tokens.
- Release habilita minificación y reducción de recursos. Las reglas conservan las entradas por
  reflexión/serialización de Room, Hilt, WorkManager y enums durables. Los gates
  `verifyReleaseManifests`, `verifyMobileSecurityBoundaries`, `verifyNoSensitiveLogging` y
  `verifyAndroidReleaseConfiguration` revisan manifiestos fusionados, configuración release/R8,
  HTTP, mutabilidad de `PendingIntent` si se introduce alguno, secretos y componentes críticos.


## Paquetes

| Paquete | Responsabilidad | Puede depender de |
| --- | --- | --- |
| `core` | Dispatchers, reloj, UUID y utilidades transversales | Kotlin/JDK y coroutines |
| `domain` | Dinero, cantidades, estados, errores, casos de uso y puertos de repositorio | `core`, Kotlin/JDK y `Flow` |
| `data` | Implementaciones de repositorio, Room, archivos y mapeadores | `domain`, `core` y librerías de infraestructura |
| `di` | Unión explícita de interfaces e implementaciones | Todas las capas que conecta |
| `navigation` | Contrato de rutas y raíz de composición del grafo | `core`, entradas públicas de `feature`, componentes de `ui` y Compose Navigation; nunca `data` |
| `ui` | Tema y componentes Compose reutilizables | `domain`, `core` y Compose |
| `feature` | Pantallas, contratos UDF y ViewModels por funcionalidad | `ui`, `domain`, `core` y AndroidX Lifecycle; nunca `navigation` ni `data` |

## Reglas obligatorias

1. Un Composable no accede a DAO, archivos, cámara ni repositorios.
2. Un ViewModel no conoce implementaciones de `data`; invoca casos de uso.
3. Un UseCase depende de interfaces ubicadas en `domain.repository`.
4. Una implementación de repositorio vive en `data` y convierte sus entidades a modelos de dominio antes de devolverlas.
5. `domain` no importa Android, Compose, Room, CameraX, Firebase ni clases de UI.
6. `Money` almacena unidades menores en `Long`. `Quantity` y `UnitCost` usan `BigDecimal` y conservan su escala.
7. No se acepta `Float` ni `Double` para dinero. Toda reducción de escala exige un `RoundingMode` explícito; si se omite al crear `Money`, se usa `UNNECESSARY` para impedir pérdida silenciosa.
8. Las operaciones entre monedas distintas fallan con un error de dominio controlado.
9. PEN y `es_PE` son valores iniciales suministrados por inyección. `RegionalSettings` admite cualquier moneda ISO 4217 y locale válido.
10. Navigation transporta únicamente IDs tipados y canónicos; imágenes, entidades e importes se recuperan desde repositorios fuera del back stack.
11. Los ViewModels se crean con Hilt y reciben sus dependencias por constructor. No existe un `ServiceLocator`, singleton mutable ni acceso global al contenedor.
12. Cada pantalla expone un `UiState` inmutable mediante `StateFlow`, recibe `UiAction` y publica operaciones de una sola vez mediante `UiEffect`.
13. Los IDs de ruta se recuperan desde `SavedStateHandle`; un ID ausente o no canónico se trata como navegación inválida y nunca se sustituye silenciosamente.
14. Los efectos se transportan por un canal sin `replay`. La UI los recoge en un único `LaunchedEffect` asociado al ViewModel, por lo que una recomposición no repite navegación.
15. Los repositorios y cualquier operación potencialmente bloqueante se ejecutan en `DispatcherProvider.io`. Las mutaciones de estado y la entrega de efectos vuelven a `DispatcherProvider.main`.
16. La cancelación es estructurada: `CancellationException` se vuelve a lanzar y nunca se convierte en un error visible.
17. Nada se registra en producción: ni rutas de archivos, ni RUC, ni contenido de documentos. La tarea `verifyNoSensitiveLogging` lo hace cumplir en cada compilación.

## Contrato UDF y alcance de Hilt

`FacturaStockApplication` es la raíz de Hilt. `MainActivity` únicamente aloja Compose y
no resuelve dependencias de negocio. Los módulos de `di` enlazan los puertos del dominio
con implementaciones de `data` y proporcionan dispatchers, reloj, UUID, configuración
regional y casos de uso. Los ViewModels no conocen el contenedor: Hilt llama a sus
constructores y AndroidX les entrega su `SavedStateHandle`.

Los nueve contratos base son Inicio, Catálogos, Captura, OCR, Revisión, Preparación,
Compras, Ventas e Inventario. Cada uno define sus propios tipos de estado, acción y efecto; no
existe un estado global que acople pantallas. Un estado de carga, error o confirmación es
persistente y renderizable. Abrir cámara, navegar o volver a Compras es un efecto único.

```text
Composable --UiAction--> ViewModel --UseCase--> Repository
     ^                       |                     |
     +--StateFlow<UiState>---+                     +-- IO dispatcher
     +--------UiEffect-------+
```

Las pantallas de Éxito, Compras y Detalle usan `PurchaseReadRepository`: el caso de uso toma el
negocio activo de configuración y el adaptador emite proyecciones `Flow` de Room. La lista devuelve
una sola fila por compra aun cuando tenga varias líneas; el detalle combina cabecera, líneas,
movimientos, auditoría, outbox, snapshot preparado e imágenes retenidas. No existe lectura directa
de red desde UI. El `purchaseId` siempre se consulta junto con `businessId`, incluidos deep links.

El detalle vuelve a validar que la cabecera y cada línea publicada coincidan con
`PreparedPurchase`. El codec vigente v5 conserva el importe y motivo de la diferencia aceptada
que introdujo v3, con signo de conciliación (`suma de líneas + ajuste = total`), y congela además
la decisión tributaria, su evidencia y la procedencia o producto staged de cada línea dentro del
hash lógico. Los payloads v2/v3 siguen siendo legibles y sus decisiones ausentes se marcan
`UNKNOWN`/`UNKNOWN_LEGACY`; v4 conserva esas decisiones y valida su hash delimitado histórico.
Desde v5 el hash usa longitudes y marcadores de nulo para que texto libre o listas no puedan
confundir límites de campos. El impuesto desconocido siempre obliga a revisar antes de publicar.
Si el archivo privado de una imagen ya no está retenido, se mantienen visibles sus metadatos Room
sin presentar un archivo inexistente.

`verifyDomainBoundaries`, conectado a `preBuild`, inspecciona las raíces Java y Kotlin y
hace fallar la compilación si `domain` importa algo fuera de Kotlin/JDK, `core`, el propio
dominio o `Flow`. Esto excluye Android, Compose, Room, CameraX, Firebase, DI y capas
exteriores. También rechaza los tokens `Float` y `Double` en esa capa para impedir
aritmética financiera binaria. `verifyNoSensitiveLogging`, en la misma compuerta, rechaza
`android.util.Log`, `System.out`/`System.err` y `print`/`println` en todo `src/main`: la
app no registra rutas, identificadores tributarios ni contenido de documentos (regla 17).

## Política decimal

- `Money` conserva únicamente `minorUnits: Long` y `CurrencyCode`; la escala se obtiene
  de ISO 4217 (`PEN=2`, `JPY=0`, `KWD=3`).
- `Quantity` y `UnitCost` conservan el `BigDecimal` y la escala decimal declarada. No se
  eliminan ceros declarados ni se redondea al construirlos. Como límite técnico contra
  entradas OCR patológicas, admiten hasta 38 dígitos de precisión, 18 decimales y 128
  caracteres de entrada; esos límites no convierten todos los valores a una escala fija.
- Toda reducción de escala recibe un `RoundingMode`. La opción predeterminada al crear
  dinero es `UNNECESSARY`, por lo que una pérdida de precisión se convierte en un error
  sellado en lugar de producir un resultado silencioso.
- Las factorías financieras aceptan `String`, `BigDecimal` o unidades menores `Long`;
  deliberadamente no existe ninguna entrada `Float` o `Double`.
- La escala declarada forma parte de la identidad de `Quantity` y `UnitCost`: `1.0` y
  `1.00` tienen el mismo valor numérico, pero expresan precisiones diferentes.
- `PurchaseStatus` solo expresa el estado contable (`DRAFT`, `POSTED`, `VOIDED`). El estado de
  sincronización vive por separado en outbox para no acoplarlo
  al ciclo de vida de una compra. Los enums se persisten por nombre, nunca por ordinal.

## Normalización regional de OCR

`domain.normalization` es una capa Kotlin/JDK pura entre el snapshot OCR y la futura
extracción de campos. Conserva siempre el fragmento `rawText`; NFKC, el colapso de espacios y
la versión en mayúsculas para comparación son derivados separados. Una corrección de carácter
OCR solo se intenta después de elegir un contexto numérico, de fecha o de unidad, queda en la
evidencia y obliga a revisión.

`Candidate<T>` transporta el valor propuesto, evidencia, caja OCR, confianza y advertencias.
Cuando dos gramáticas producen resultados distintos, `value` queda vacío y `alternatives`
enumera las lecturas válidas: ni la configuración regional ni la confianza OCR resuelven una
ambigüedad semántica. Los parsers usan `BigDecimal`, `Money`, `Quantity`, `UnitCost` y
`LocalDate` exactos; no usan `NumberFormat`, redondeo implícito ni aritmética binaria. El
contrato completo y sus ejemplos están en
[`docs/PERUVIAN_NORMALIZATION.md`](PERUVIAN_NORMALIZATION.md).

## Persistencia Room

`FacturaStockDatabase` (versión 29, en `data/local`) define treinta y cinco tablas: `businesses`,
`suppliers`, `units`, `inventory_locations`, `products`, `supplier_product_aliases`,
`invoice_drafts`, `invoice_images`, `captured_page_publications`, `invoice_lines`, `invoice_ocr_snapshots`,
`invoice_ocr_snapshot_pages`, `invoice_parsed_results`, `invoice_header_edits` e
`invoice_line_edits`, `prepared_purchases`, `purchases`, `purchase_lines`, `sales`, `sale_lines`,
`inventory_balances`, `stock_movements`, `audit_events`, `outbox_operations`,
`remote_sync_states`, `remote_purchase_changes`, `remote_movement_summaries`,
`remote_catalog_changes`, `catalog_sync_links`, `cloud_business_bindings`, `debts`,
`debt_payments`, `pending_sale_checkouts`, `invoice_inventory_receipts` y `sale_voids`. Las tablas
de espejo remoto y enlace cloud (`remote_*`, `catalog_sync_links`, `cloud_business_bindings`) y
`pending_sale_checkouts` provienen de la etapa cloud: siguen en el esquema, sin migración, aunque
la variante `local` no tiene transporte que las alimente. El esquema se exporta
a `app/schemas` en cada compilación y se versiona con el código. **La migración destructiva está
prohibida**: el builder nunca invoca `fallbackToDestructiveMigration`, la apertura falla cerrada
ante corrupción y todo cambio de versión exige `Migration` explícitas. El builder fija WAL en vez de
delegar la elección al modo automático. Las garantías y límites completos están en
[`DATABASE_RELIABILITY.md`](DATABASE_RELIABILITY.md).

- El dinero se persiste en unidades menores `Long` junto a su código ISO 4217. Las
  cantidades y los costos unitarios se guardan como texto decimal plano
  (`BigDecimal.toPlainString`, sin signo ni exponente), de modo que la escala declarada
  sobrevive al ciclo persistir/leer dentro de los límites de `ExactDecimalPolicy`.
- `products.salePriceMinorUnits` y `products.salePriceCurrencyCode` conservan juntos el precio de
  venta opcional; las filas migradas quedan `NULL/NULL` hasta una decisión humana. La lectura de
  ganancias agrega los saldos por producto con `BigDecimal`, pondera sus costos por cantidad y
  falla de forma tipada cuando falta precio/stock o las monedas no son comparables. No hay cambio
  de divisas ni cálculo de utilidad realizada.
- La confianza OCR es un entero de 0 a 1000 (0.000 a 1.000). Las marcas de tiempo son
  `Long` en epoch millis y los UUID se almacenan canónicos (36 caracteres, minúsculas,
  no nulos).
- `invoice_drafts.activeOcrRunId` guarda el token de la única ejecución autorizada a
  publicar o cancelar OCR. Inicio, commit y cancelación usan `UPDATE` condicional por estado,
  token y ausencia de confirmación; un callback tardío afecta cero filas. La recuperación de un
  OCR interrumpido compara además el token observado antes de volver a `CAPTURED`, de modo que
  una acción UI atrasada no puede cancelar un intento posterior.
- `invoice_ocr_snapshots` conserva una cabecera por borrador y
  `invoice_ocr_snapshot_pages` una BLOB versionada y verificada por página. Reemplazar páginas y
  pasar a `OCR_READY` ocurre en una única transacción condicionada por `activeOcrRunId`; por eso
  revisión nunca observa un documento parcial ni un reintento duplica resultados.
- `invoice_parsed_results` conserva el audit trail versionado de cabecera, líneas y totales,
  con candidatos, razones, confianza, advertencias, bloqueos y evidencia. Su FK compuesta lo
  liga al `(draftId, runId)` OCR exacto. Audit, proyección editable, reemplazo de líneas y CAS
  `OCR_READY → NEEDS_REVIEW` comparten una transacción; un reintento idéntico no toca IDs ni
  timestamps.
- `invoice_header_edits` conserva el formulario móvil como texto parcial versionado: también
  sobreviven estados todavía inválidos como `20/` o `12,`. Cada autosave compara una revisión
  monotónica **y la revisión base esperada** y, en la misma transacción, actualiza la proyección
  tipada del borrador. Un reintento idéntico no toca timestamps; una escritura nacida de una
  fotografía antigua se rechaza aunque su contador local sea mayor, y el ViewModel la rebasa
  mezclando únicamente sus campos modificados.
- `invoice_line_edits` conserva el snapshot agregado de hasta 100 líneas activas y sus
  tombstones restaurables. Cada celda mantiene por separado el texto OCR original, su valor
  canónico interpretable, el calculado y el escrito, además de la procedencia seleccionada,
  confianza y confirmación humana. Editar, agregar,
  eliminar, restaurar y reordenar comparten un CAS de revisión base; el autosave reemplaza en la
  misma transacción la proyección tipada `invoice_lines`, reindexa las líneas válidas y conserva
  `productId`, `unitId` y `linkConfidence` ya enlazados por otro flujo.
- `prepared_purchases` congela la revisión validada con codec, SHA-256 y hash lógico, incluido
  el texto original de cada fila. La publicación vuelve a decodificar esa instantánea y compara
  proveedor, comprobante, moneda, textos, importes y resoluciones antes de crear el libro
  definitivo; conocer solo el hash no permite sustituir el contenido.
- `purchases` y `purchase_lines` preservan la identidad documental, texto revisado, producto,
  cantidad, costo, impuesto, total y confianza. Los índices garantizan una compra por borrador,
  clave idempotente global y comprobante único por negocio/proveedor/tipo/serie/número.
- `sales` conserva un único carrito `DRAFT` por negocio y moneda, con versión y hash de contenido;
  `sale_lines` congela producto, unidad, almacén, código, cantidad y precio comercial explícito.
  Confirmar vuelve a validar existencias, aplica por CAS los movimientos negativos `SALE`, mantiene
  el costo promedio y publica venta, líneas y auditoría en una sola transacción idempotente. Una
  línea sin precio puede guardarse en el carrito, pero nunca publicarse, y el checkout no permite
  stock negativo. El checkout es siempre local: la rama que, con un binding cloud, autorizaba y
  descontaba primero el saldo remoto mediante `postSale` sigue en el código compartido, pero la
  variante única no puede crear ese binding. Las ventas no usan outbox.
  Las entidades Room y `RoomSaleRepository` son la autoridad de la aritmética decimal exacta; los
  triggers SQLite refuerzan forma, pertenencia, transiciones e inmutabilidad, pero no se presentan
  como un segundo motor universal de `BigDecimal`. Ninguna ruta productiva escribe una venta
  publicada fuera del coordinador transaccional.
- `debts` materializa una cuenta por cobrar uno-a-uno con una venta a crédito `POSTED`: congela el
  nombre normalizado, moneda, importe original, saldo, estado, versión y fechas. `debt_payments` es
  su libro append-only con importe, método, referencia/nota opcionales, versión esperada y saldo
  resultante. Insertar un abono y avanzar la deuda ocurre en la misma transacción con CAS; los
  triggers exigen el grafo exacto y una deuda no puede existir sin su venta. La rama con
  autorización remota previa, propia de un binding cloud, queda inactiva por la misma razón.
- `stock_movements` y `audit_events` son libros append-only reforzados con triggers contra
  `UPDATE`, `DELETE` e `INSERT OR REPLACE`. `inventory_balances` es una proyección versionada:
  el coordinador comprueba cantidad, conversión de unidades y costo promedio ponderado contra
  los movimientos antes del CAS. El cálculo conserva costo leído y aplicado por separado,
  requiere tratamiento/evidencia tributarios y política neta/bruta explícitos, y usa el total
  aplicado exacto antes de redondear el promedio; la fórmula completa está en
  [`INVENTORY_COSTING.md`](INVENTORY_COSTING.md).
- `outbox_operations` es la cola durable del respaldo: nace en el commit de publicación o
  anulación, se reclama por CAS con `claimToken` + lease, y se drena con el worker
  WorkManager de `data/sync` (dos cadenas inmediatas `APPEND_OR_REPLACE`, follow-ups deduplicados
  por deadline, constraint de red y backoff exponencial por operación). Privacidad usa un canal
  `purgeOnly` independiente del opt-in comercial. Solo un acuse cuyo eco coincide con la
  `idempotencyKey` la marca `COMPLETED`.
  Sin backend, el transporte enlazado declara no estar configurado, WorkManager no programa
  ningún drenado y la cola queda honestamente en `PENDING_SYNC`. El protocolo completo está en
  [`BACKUP_SYNC.md`](BACKUP_SYNC.md).
- `PurchasePostingDao.postAtomically` escribe compra DRAFT, líneas, enlace del borrador, saldos,
  movimientos, auditoría y outbox, y recién entonces ejecuta DRAFT → POSTED. Todo ocurre en una
  transacción Room; conflicto de documento/idempotencia, snapshot distinto, catálogo cruzado o
  versión obsoleta revierte el grafo completo. Triggers adicionales conservan propiedad por
  negocio y prohíben un POSTED incompleto incluso mediante SQL de bajo nivel.
- Las imágenes guardan solo metadatos (ruta relativa al almacenamiento privado, SHA-256,
  dimensiones, rotación, página y recorte normalizado en diezmilésimas 0..10000 sobre la
  imagen ya rotada); el contenido nunca es un BLOB. El archivo original jamás se reescribe:
  girar y recortar solo actualizan metadatos mediante intenciones atómicas (`rotateImage90` y
  `setImageCrop`). Las versiones preprocesadas
  para OCR se escriben como lotes nuevos en `draft_images/{draftId}/ocr/runs/{runId}/`, con
  muestreo, gris, contraste moderado y JPEG 88. Un manifiesto publica solo el lote completo,
  sin sobrescribir el original ni exponer páginas parciales.
- La unicidad operativa es por negocio: RUC de proveedor, SKU, código de barras, código
  de unidad, nombre de ubicación y alias de proveedor; `(draftId, pageIndex)` y
  `(draftId, position)` ordenan páginas y líneas. Los `NULL` no colisionan, así que
  varios registros pueden carecer de RUC, SKU o código de barras. Las páginas admiten
  varias por borrador: `CapturedPageIntent.Append` añade al final y
  `CapturedPageIntent.Replace(imageId)` conserva la posición del objetivo resuelto dentro de la
  transacción. `moveImageOneStep` lee y permuta el vecino en esa misma transacción, por lo que
  movimientos concurrentes se componen; `deleteImage` reindexa las restantes a 0..n-1.
- Cascadas: mientras el negocio no tenga historia publicada, eliminarlo elimina su grafo;
  cuando ya existen compras/movimientos/auditoría append-only, los triggers rechazan la
  eliminación para preservar el libro. Eliminar un borrador editable elimina sus
  imágenes, líneas, snapshot OCR y resultado parseado. Proveedor, producto, ubicación y unidad
  se archivan; guards SQL rechazan su eliminación directa mientras cualquier borrador, alias,
  producto, saldo, movimiento o compra los referencie, incluso donde una FK legada usa
  `SET_NULL`/`CASCADE`.
- `data/local/EntityValidation.kt` impone en las entidades las mismas invariantes del
  dominio, reutilizando `ExactDecimalPolicy` y `CurrencyCode` (mismo módulo Gradle).
- Los puertos de persistencia viven en `domain/repository` (uno por agregado):
  `BusinessRepository`, `SupplierRepository`, `UnitRepository`,
  `InventoryLocationRepository`, `ProductRepository`, `SupplierProductAliasRepository`,
  `InvoiceDraftRepository` (raíz que cubre borrador, imágenes y líneas),
  `ParsedInvoiceRepository`, `InvoiceHeaderReviewRepository` y `SaleRepository`. Sus
  implementaciones Room están en `data/repository` y se enlazan en `di/RepositoryModule`.
  La outbox de respaldo expone además tres puertos: `PurchaseBackupRepository` (observación y
  reintento manual para la UI), `PurchaseBackupOutboxRepository` (CAS para el procesador) y
  `PurchaseBackupScheduler` (programación del drenado), más el puerto de transporte
  `PurchaseBackupTransport`, que `local` enlaza a `UnavailablePurchaseBackupTransport` sin hacer
  que el dominio dependa de la red.
- El puerto de archivos `DraftFileStore` (implementado por `LocalDraftFileStore` en
  `data/files`, enlazado en `di/RepositoryModule`) cubre las imágenes de los borradores:
  al eliminar un borrador, Room borra primero el agregado; luego `deleteDraftTreeIf` toma el
  lock compartido del árbol y revalida dentro de él que el draft siga ausente. Si otro flujo
  materializó de nuevo el mismo ID, omite todo borrado físico; si sigue ausente, elimina el árbol
  completo en mejor esfuerzo. Las rutas se resuelven bajo `filesDir`, sin seguir symlinks ni permitir path
  traversal, y el callback de revalidación no reentra al almacén bloqueado.
- El puerto de importación `DraftImageImporter` (implementado por `LocalDraftImageImporter`
  en `data/files`, enlazado en `di/RepositoryModule`) valida y copia imágenes al
  almacenamiento privado desde dos entradas: `import` (URI de galería) e `importBytes` (JPEG
  en memoria de la cámara, con el MIME real verificado al decodificar cabeceras). Cada página
  sigue un pipeline todo o nada: temporal aleatorio `import-*.tmp` (nombre no identificable)
  en la raíz privada, validación contra la política, **limpieza de metadatos**
  (`ImageMetadataScrubber`: la copia de trabajo queda sin geolocalización, EXIF ni XMP vía el
  stripper estructural `ImageMetadataStripper`), revalidación cerrada de decodificabilidad,
  cálculo de SHA-256 y tamaño sobre el archivo YA LIMPIO (hash y metadatos persistidos
  coinciden con el contenido guardado) y movimiento atómico (`Files.move` con `ATOMIC_MOVE`)
  a `draft_images/{draftId}/{imageId}.{ext}`. La orientación EXIF se rescata antes de limpiar
  y viaja en los metadatos. Nada se publica en la galería del sistema.
  `StartInvoiceDraftUseCase` materializa el borrador antes de abrir cámara/galería;
  `StartDemoInvoiceScenarioUseCase` hace la misma reserva antes de generar su JPEG.
  `ImportDraftImageUseCase` exige esa fila preexistente antes de tocar archivos: un callback
  tardío tras descartar falla y nunca resucita el agregado. Luego publica Append/Replace con
  `publishCapturedPage` y avanza `CREATED` → `CAPTURED`.
  La misma transacción crea `captured_page_publications`: un recibo durable por `imageId` que
  conserva intención, payload y ruta sustituida. Un replay exacto evita recopia y reintenta el
  borrado del archivo anterior; el mismo ID con otra intención se rechaza incluso después de
  reorder, delete o reinicio. Antes de Room se exige exactamente
  `draft_images/{draftId}/{imageId}.{jpg|jpeg|png|webp}` coherente con el MIME. La rotación
  registrada es la del sensor en cámara y la del EXIF rescatado en
  galería; en ambos casos el archivo se guarda sin rotar y la vista lo aplica. La operación
  es todo o nada respecto de la página: un fallo (`FileException`) conserva el borrador
  `CREATED` preexistente y no persiste imagen; si la
  persistencia falla con el archivo nuevo ya copiado, solo se borra si ninguna fila referencia
  esa ruta exacta. Al reemplazar una página, el archivo anterior se conserva
  hasta que la nueva página quedó validada y persistida, y solo entonces se borra —nunca otro
  archivo— también en mejor esfuerzo.
- Los temporales huérfanos (proceso muerto a mitad de importación) los limpia
  `StaleImportCleanup` (`data/files`): solo borra `import-*.tmp` de la RAÍZ del
  almacenamiento privado con más de una hora de antigüedad, al arrancar la app
  (`FacturaStockApplication`) y al inicio de cada importación. Jamás entra a
  subdirectorios ni toca `draft_images`, y un temporal en curso es reciente: la limpieza
  nunca borra páginas de sesiones activas.
- La cámara (CameraX) vive aislada en `feature/capture/camera/` como adaptador de vista:
  composables de presentación que ligan `Preview`/`ImageCapture`, gestionan enfoque táctil y
  flash, y entregan el JPEG en memoria a la ruta. Es una excepción de presentación
  documentada a la regla 1: el adaptador no contiene lógica de negocio —la validación y la
  persistencia se hacen siempre por `ImportDraftImageUseCase`— y libera la cámara con
  `unbindAll()` al salir de la composición.
- Política de captura (`domain/model/CaptureImagePolicy`): MIME permitidos
  (`image/jpeg|png|webp` — HEIC/HEIF quedan fuera porque su contenedor no permite garantizar
  la limpieza de metadatos con las herramientas de la plataforma), tamaño máximo 15 MiB y
  8000 px por lado, más una prueba de decodificación real. La copia se hace primero en un
  temporal y el SHA-256 se calcula sobre el contenido ya limpio de EXIF. La galería usa el
  Photo Picker (`PickVisualMedia`), que cae a `ACTION_OPEN_DOCUMENT` en dispositivos sin él:
  no requiere ningún permiso de almacenamiento ni permiso de URI persistente porque el
  archivo se copia de inmediato. El único permiso del manifiesto es `CAMERA`, solicitado en
  runtime solo al pulsar "Tomar foto".
- Política de marcas de tiempo: los repositorios estampan con el `AppClock` inyectado —
  `createdAt = updatedAt = now` al crear, `updatedAt = now` al actualizar, y toda mutación
  de imágenes o líneas toca el `updatedAt` del borrador padre dentro de la misma
  transacción (`withTransaction`). Los valores de tiempo que llegan del llamador se ignoran.
- Los fallos de disco se traducen a dominio: `SQLiteConstraintException` se convierte en
  `StorageException(StorageError.ConstraintConflict)` y `SQLiteException`/`IOException` en
  `StorageException(StorageError.Unavailable)` (`data/local/StorageErrorTranslation.kt`).
  `CancellationException` nunca se atrapa: se vuelve a lanzar intacta (regla 16).
- Las conversiones entidad ↔ dominio viven en `data/local/mapper`, separadas de los
  repositorios; los campos derivados de persistencia (`normalizedName`, `aliasNormalized`)
  se recalculan en la entidad y nunca cruzan al dominio.
- Las pruebas disponen de fakes en memoria en `app/src/test/.../testing/FakeRepositories.kt`:
  mismas políticas de orden, búsqueda y marcas de tiempo que Room, emisión por
  `MutableStateFlow`, `Mutex` para las operaciones atómicas y un hook para forzar fallos.

## Configuración y preferencias

Las preferencias no relacionales viven en DataStore (`app_settings`, provisto en
`PersistenceModule` e implementado en `data/settings`): `onboarding_completed`,
`business_id`, `demo_business_id`, `tax_rate_percent` (texto decimal, default `"18"`),
`cost_policy` (`NET`/`GROSS`), `currency` (`"PEN"`) y `timezone` (`"America/Lima"`). El
puerto es `domain/repository/AppConfigurationRepository` y su binding vive en un módulo
propio (`di/AppConfigurationModule`) para que los tests instrumentados lo reemplacen con
`@TestInstallIn`. Una clave corrupta vuelve a su default en lugar de romper la lectura.

- El negocio activo es `demoBusinessId ?: businessId`: el id real nunca se sobrescribe.
  Entrar al modo demostración siembra un grafo 100 % sintético marcado `[DEMO]` (negocio sin
  RUC, unidades, almacén, proveedores y seis productos); dos productos comparten un nombre
  normalizado pero conservan SKU/barcode distintos para reproducir una decisión ambigua. Los
  ids hijos se derivan del id de negocio y claves semánticas estables. Una descripción adicional
  permanece ausente para ejercitar la creación explícita. Salir borra la clave demo y elimina
  el negocio demo, cuya cascada limpia el resto. El contrato sintético completo está en
  `docs/DEMO_SCENARIO.md`. Desde que Ajustes quedó en su forma mínima (septiembre de 2026), la
  interfaz no ofrece entrar ni salir del modo demostración; los casos de uso y el escenario
  sintético siguen en el código y en las pruebas.
- El iniciador visible de la factura demo deriva y materializa un único borrador estable por
  negocio antes de generar el JPEG que pasa a `ImportDraftImageUseCase`; no inserta resultados
  OCR/parseados. Un mutex y
  la cardinalidad de una página hacen idempotente el doble toque. La UI abre Vista previa con
  `draftId/captureId`, o el detalle con `purchaseId` si la misma demo ya fue publicada.
- La publicación parseada captura en un fingerprint la tasa IGV, moneda de respaldo, RUC del
  comprador y versión usados. Un cambio posterior de configuración no sobrescribe un resultado
  que ya está en revisión: requiere una acción de reproceso explícita.
- El RUC se evalúa localmente en dos niveles sin consulta a SUNAT: el formato (11 dígitos ASCII)
  bloquea el guardado y el checksum módulo 11 solo advierte — no confirma existencia, estado ni
  identidad. El usuario confirma reenviando y la app jamás modifica el valor ingresado.
- `InvoiceHeaderParser` recorre una sola capa OCR, asocia etiquetas por geometría y devuelve tipo,
  emisor, número, fecha y moneda con selección, alternativas y razones. El resultado conserva
  `draftId/runId`; el parser no usa repositorios, configuración implícita ni red.
- Migración v1 → v2: `businesses.ruc` se volvió opcional (onboarding y demo pueden no
  tenerlo; los `NULL` no colisionan en el índice único). `MIGRATION_1_2` recrea la tabla y el
  índice conservando los datos; la migración destructiva sigue prohibida y
  `MigrationTest` la verifica con `MigrationTestHelper` contra los esquemas exportados.
- Migración v2 → v3: el recorte de `invoice_images` pasó de píxeles a coordenadas
  normalizadas en diezmilésimas (`crop*Fraction`). `MIGRATION_2_3` recrea la tabla
  convirtiendo cada arista con la dimensión rotada correspondiente (con 90°/270° ancho y
  alto se intercambian) y conserva los `NULL`; `MigrationTest` verifica la conversión.
- Migración v5 → v6: añade `invoice_parsed_results`, el índice único compuesto del snapshot
  y la FK `(draftId, runId)`. `MigrationTest` verifica creación, inserción y cascada.
- Migración v6 → v7: amplía la proyección de cabecera con razón social, tipo y cargos, y añade
  `invoice_header_edits` con payload versionado, SHA-256 y cascada por borrador. El repositorio
  prueba reinicio, idempotencia, carreras, conflictos y rollback.
- Migración v7 → v8: amplía `invoice_lines` con código, unidad, descuento e IGV, y añade
  `invoice_line_edits` con payload versionado, SHA-256 y cascada por borrador. El total de línea
  conserva signo; costo, descuento e IGV no se vuelven negativos ni se calcula una tasa implícita.
- Migración v8 → v9: añade unidad/factor de compra al producto conservando el catálogo.
- Migración v9 → v10: añade `prepared_purchases` sin reinterpretar borradores existentes.
- Migración v10 → v11: añade compras, líneas, saldos, movimientos, auditoría y outbox vacíos,
  conserva borradores, OCR y ediciones, y crea índices, FKs y triggers explícitos. Reabre en
  `NEEDS_REVIEW` las preparaciones v10 y elimina solo su BLOB congelado: ese codec permitía tipo
  o costo nulos y SQLite no puede distinguir cuáles cumplen el contrato contable v11. También
  limpia cualquier `confirmedPurchaseId` provisional de v10, que no podía referenciar una compra
  real porque esa tabla aún no existía.
  `PurchasePostingMigrationTest` valida preservación, unicidad, huérfanos y append-only contra el
  esquema v11 exportado; `PurchasePostingDaoTest` cubre commit completo y rollbacks.
- Migración v11 → v12: normaliza de forma durable el RUC de proveedor, SKU y código de barras
  de producto antes de que los repositorios sigan escribiendo sus formas canónicas. Si valores
  legados colisionan, el id lexicográficamente menor conserva la clave y los demás campos
  opcionales quedan `NULL`; ninguna fila, id ni referencia se pierde. También instala guards que rechazan `DELETE` directo de
  proveedor, producto, unidad o almacén mientras esté referenciado; el flujo productivo siempre
  archiva/restaura. `MigrationTest` verifica conservación, colisiones y presencia de los guards.
- Migración v12 → v13: añade a `purchase_lines` la auditoría durable del costeo (costo leído y
  aplicado, total exacto, conversión, descuento, tratamiento/evidencia tributaria, política,
  redondeo y advertencias). La historia conserva `unitCost` y recibe `NULL` en las columnas nuevas:
  no se inventan decisiones retroactivas. El contrato y las fórmulas están documentados en
  [`docs/INVENTORY_COSTING.md`](INVENTORY_COSTING.md).
- Migración v13 → v14: identidad fiscal `PRIMARY` única por negocio/proveedor/tipo/serie/número
  con slots excepcionales solo cuando conservan autorización durable; las compras publicadas que
  quedaban en `READY_TO_POST` se materializan `COMMITTED`.
- Migración v14 → v15: la outbox adopta el protocolo de transporte concurrente. Añade
  `claimToken` y `claimLeaseUntil` (el claim PROCESSING tiene dueño y vencimiento; completar o
  fallar exige el token y un lease vencido se recupera sin reinicio) y `payloadVersion`
  (default 1; los writers fijan la versión de su formato). Los claims legados quedan con lease
  `NULL` y la recuperación los trata como vencidos. El drenado WorkManager, su constraint de
  red, el backoff exponencial durable y el acuse validado por clave idempotente se documentan
  en [`docs/BACKUP_SYNC.md`](BACKUP_SYNC.md).
- Migración v15 → v16: añade `version` con default 1 a `products` y `suppliers` para el CAS
  optimista, y agrega a `outbox_operations` los IDs remotos de compra y recibo en conflicto.
  Los cambios son aditivos: ninguna operación histórica cambia de estado, intentos, error o
  contenido, y los IDs nuevos permanecen `NULL` cuando la nube aún no los había informado.
- Migración v16 → v17: añade a `purchase_lines` los snapshots anulables
  `productNameSnapshot` y `unitCodeSnapshot`. Las líneas existentes se rellenan desde las FK de
  producto y unidad dentro de la propia transacción de migración; después se reinstalan los
  invariantes que impiden editar líneas publicadas.
- Migración v17 → v18: añade `purchase_lines.productProvenance` no nulo con default
  `UNKNOWN_LEGACY`. La historia permanece consultable sin atribuir retroactivamente productos al
  catálogo existente o al borrador; las publicaciones nuevas escriben `EXISTING` o
  `CREATED_IN_DRAFT`.
- Migración v18 → v19: generaliza `outbox_operations` con identidad y versión causales por tipo de
  agregado. La historia conserva payload y clave, se clasifica como `PURCHASE` y recibe versión 1
  para el alta o 2 para la anulación. También crea vacías las tablas durables
  `remote_sync_states`, `remote_purchase_changes`, `remote_movement_summaries`,
  `remote_catalog_changes` y `catalog_sync_links`; no inventa datos remotos retroactivos.
- Migración v19 → v20: añade el destino durable `targetCloudBusinessId` a la outbox y crea
  `cloud_business_bindings` para enlazar de forma inmutable cada negocio local con su tenant cloud.
  Las operaciones históricas conservan destino `NULL`, porque SQL no puede deducir el enlace previo
  de DataStore; solo el flujo explícito de primer enlace puede asignar operaciones `PENDING` sin
  intentos consumidos.
- Migración v20 → v21: crea vacías `sales` y `sale_lines`, añade a `stock_movements` las referencias
  opcionales `saleId`/`saleLineId` e instala índices, claves foráneas y triggers de integridad. No
  reescribe compras, saldos, movimientos ni códigos históricos; los códigos nuevos se limitan a
  ASCII imprimible y las filas Unicode heredadas permanecen legibles hasta una edición explícita.
- Migración v21 → v22: añade a `products` el par nullable de precio de venta en unidades menores y
  moneda ISO. Conserva IDs, catálogo, borradores, compras, saldos, ventas y outbox; no infiere un
  precio desde el costo y reinstala los invariantes con validación del par completo.
- Migración v22 → v23: valida antes de cualquier DDL que las referencias de catálogo y factura no
  crucen negocios, reemplaza índices cortos por compuestos alineados con consultas crecientes e
  instala triggers para que SQL directo tampoco pueda crear esos cruces. No reconstruye tablas ni
  reinterpreta datos; una precondición fallida conserva transaccionalmente la base v22.
- Migración v23 → v24: añade recibos append-only de publicación de páginas capturadas, vacíos para
  historia legacy porque no se inventan intenciones que el esquema anterior no conocía.
- Migración v24 → v25: añade el índice compuesto que calcula la salud de la outbox por tenant sin
  recorrer payloads.
- Migración v25 → v26: añade a `remote_sync_states` el cursor y la fecha del feed independiente de
  inventario/ventas.
- Migración v26 → v27: crea vacías `debts` y `debt_payments`, sus índices/FK y los triggers que
  ligan deuda, venta y transición por pago. No infiere deudas desde ventas históricas.

`FullPathMigrationTest` valida la cadena completa v1…v27 desde cada esquema histórico, las FKs,
los backfills/defaults legados y que un destino cloud desconocido no se infiera durante la
migración.
