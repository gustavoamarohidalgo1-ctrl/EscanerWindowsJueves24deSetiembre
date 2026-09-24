# Confiabilidad de la base de datos local

FacturaStock usa Room sobre SQLite como fuente de verdad **en el dispositivo**. El esquema vigente
es v28 y contiene 34 tablas para negocio, catálogos, borradores OCR, compras, ventas, deudas, pagos,
inventario, auditoría, sincronización y recibos de confirmación. Este documento separa las garantías
implementadas de los límites que todavía requieren una fase distinta de respaldo y restauración.

## Garantías implementadas

| Riesgo | Control |
| --- | --- |
| Escritura parcial de una compra o venta | Las cabeceras, líneas, saldos, movimientos, auditoría y outbox correspondientes se confirman en una sola transacción Room. Un fallo tardío revierte el lote completo. |
| Doble toque o reintento | Claves únicas, claves idempotentes y compare-and-set (CAS) hacen que el segundo intento devuelva el resultado ya publicado o falle sin repetir inventario. |
| Respuesta perdida al confirmar una venta cloud (variante retirada el 2026-09-24) | `pending_sale_checkouts` conservaba la intención antes de llamar a la nube y congelaba el borrador y sus líneas. La tabla y su código siguen en el esquema, pero la variante `local` confirma las ventas en una sola transacción Room y no la usa. |
| Ingreso OCR parcialmente aplicado | Productos nuevos, outbox de catálogo, inventario, recibo y cierre del borrador se confirman juntos. `invoice_inventory_receipts` permite recuperar el éxito después de perder la respuesta y rechaza reintentos con contenido diferente. |
| Concurrencia sobre existencias | `inventory_balances.version` protege cada cambio con CAS; los movimientos son append-only y permiten recalcular y comparar el saldo. |
| Referencias inválidas | Foreign keys, índices únicos y triggers verifican negocio, catálogo, borrador, compra, venta, deuda, pago, producto, unidad y almacén, incluso cuando una FK simple no puede expresar el aislamiento entre negocios. |
| Edición de historia publicada | Compras, ventas, deudas, pagos, líneas, movimientos y auditoría publicados tienen invariantes SQL que bloquean reemplazos, cambios o borrados no autorizados. La anulación de compra crea movimientos compensatorios; los pagos de deuda avanzan el saldo mediante CAS y permanecen append-only. |
| Cierre de proceso durante uso normal | WAL está fijado explícitamente; Room reabre la base y los borradores, outbox y operaciones confirmadas permanecen en disco. |
| Apagado abrupto o pérdida de energía | `synchronous=FULL` se fija en cada apertura (`onOpen`) y exige sincronizar el WAL en cada commit. La durabilidad física depende de que el sistema de archivos y el dispositivo cumplan esas operaciones. |
| Reporte de éxito sin evidencia | Todo guardado de producto verifica por relectura, dentro de su transacción, que la fila en disco coincide con lo escrito; la discrepancia falla como `StorageError.Unavailable`. |
| Actualización de la app | Hay una migración explícita por cada salto v1→v28. No existe `fallbackToDestructiveMigration`. Las pruebas de todas las rutas históricas validan los JSON exportados y ejecutan `foreign_key_check` e `integrity_check`. |
| Corrupción detectada al abrir | La factory productiva falla cerrada: no autoriza `allowDataLossOnRecovery` ni delega al handler que puede borrar la base y sus sidecars. Conserva el original para diagnóstico/recuperación explícita. |
| Cambio de esquema accidental | `verifyRoomSchemaPolicy` exige el JSON nuevo, la migración declarada y registrada, WAL explícito y la factory no destructiva antes de compilar. La CI además protege el historial append-only. |
| Consultas que crecen con el negocio | v28 conserva los índices compuestos de borradores, compras, ventas, auditoría, outbox, movimientos, inventario, espejo remoto y cuentas por cobrar, y añade índices por negocio para las dos tablas de confirmación. |

Los importes monetarios son `Long` en unidades menores con moneda ISO 4217. Cantidades y costos se
guardan como decimal textual exacto; no se usa `Double`. Los UUID, estados y fechas se validan antes
de persistir. El libro de movimientos no se “repara” silenciosamente: el diagnóstico reproduce el
saldo y reporta diferencias sin reescribir la historia.

## Cambio histórico v22 → v23

La migración no reconstruye tablas ni reinterpreta valores comerciales. Primero comprueba cinco
grafos que históricamente podían tener una FK válida pero pertenecer a negocios distintos:
producto–unidad/almacén, alias–proveedor/producto, borrador–proveedor, imagen–borrador y
línea–borrador/producto/unidad. Si encuentra una inconsistencia, aborta con un mensaje fijo sin IDs;
la transacción conserva intacta la base v22.

Después sustituye índices cortos o redundantes por índices compuestos compatibles con las lecturas
reales e instala triggers que rechazan nuevos cruces entre negocios. La prueba específica acredita
preservación de filas, planes de consulta, rechazo de escrituras cruzadas y rollback del DDL. La
prueba full-path actual incluye estos orígenes y los lleva hasta v28.

## Cambios v23 → v27

- v23 → v24 añade recibos append-only de publicación de páginas capturadas.
- v24 → v25 añade el índice de salud de la outbox por tenant.
- v25 → v26 añade a `remote_sync_states` el cursor independiente del feed compartido de
  inventario y ventas.
- v26 → v27 crea vacías `debts` y `debt_payments`, con claves foráneas, índices y triggers que
  exigen una deuda por venta a crédito publicada y una transición CAS por cada pago. La migración no
  inventa cuentas por cobrar a partir de ventas históricas.

`DebtMigrationTest` verifica preservación de las filas v26 e instalación del ledger nuevo;
`FullPathMigrationTest` recorre actualmente cada origen histórico v1…v27 hasta v28.

## Cambio v27 → v28

La migración crea dos tablas vacías con claves foráneas e índices por negocio. No reinterpreta
ventas, deudas ni movimientos históricos:

- `pending_sale_checkouts` conserva por venta la versión esperada, huella, clave idempotente,
  datos de cobro/crédito y vínculo cloud de la confirmación en curso. Los triggers validan la
  intención, impiden mutarla o editar su borrador/líneas y la eliminan al publicar la venta.
- `invoice_inventory_receipts` conserva por borrador el negocio, huella, cantidad de líneas
  aplicadas y fecha. No depende por FK del borrador, porque ese borrador se elimina dentro del
  mismo commit del ingreso.

En una venta cloud (ruta de la variante retirada el 24 de septiembre de 2026, que el código
compartido conserva inactiva), una confirmación remota definitiva puede completar el documento
congelado aunque después se hayan archivado su producto, unidad o almacén. El commit exige que la confirmación
remota corresponda a la intención pendiente y mantiene la identidad y los datos históricos de
las líneas. Esa excepción no permite vender catálogo archivado mediante una venta local.
Si la respuesta remota es incierta, el carrito sigue bloqueado hasta resolver el mismo intento;
un error de transporte no prueba que el servidor haya rechazado la operación.

En OCR, la transacción exige todas las líneas originales y valida también los extras manuales
con identidad estable. Cantidad, costo, moneda o unidad incompletos bloquean el lote entero. Los
movimientos antiguos `invoice-match:v1` solo se reconocen si coinciden con la revisión; un conflicto
se conserva para reconciliación. El alcance y los límites del ingreso se detallan en
[`INVOICE_PRODUCT_SCANNER.md`](INVOICE_PRODUCT_SCANNER.md).

`CheckoutDurabilityMigrationTest` comprueba preservación de una fila de negocio v27, creación
vacía de ambas tablas y comprobaciones de integridad y claves foráneas. Las pruebas de repositorio
comprueban los estados pendientes, el rollback y los reintentos sobre Room real.

## Política de apertura y durabilidad

El builder productivo selecciona `WRITE_AHEAD_LOGGING` de forma explícita, habilita foreign keys y
usa `FailClosedSQLiteOpenHelperFactory`. WAL mejora la convivencia entre lecturas y una escritura y
evita depender del modo `AUTOMATIC` de Room, que puede elegir otro journal según el dispositivo.

El open helper de producción fija `synchronous=FULL` en `onOpen`, que corre sobre cada conexión
que abre (incluida la que escribe). En modo WAL, `FULL` solicita sincronizar el WAL al confirmar
cada transacción. Esto refuerza la durabilidad frente a un apagado abrupto, bajo las garantías de
sincronización del almacenamiento. No protege frente a avería física, borrado de datos ni un
dispositivo que incumpla esas garantías. El coste de sincronización depende del equipo y debe
medirse en él; no se presupone una latencia imperceptible.

Además, todo guardado de producto —alta, edición, lote del escáner de facturas y precio de venta—
verifica por relectura, dentro de su misma transacción, que la fila quedó exactamente como se
intentó escribir. Una discrepancia falla de forma explícita (`StorageError.Unavailable`) en lugar de
anunciar un éxito sin evidencia en disco: "guardado" solo puede significar "legible y exacto".

## Verificación reproducible

```bash
./gradlew --offline --no-daemon --max-workers=1 \
  spotlessCheck ciStaticAnalysis :app:verifyRoomSchemaPolicy test

ANDROID_SERIAL=emulator-5554 ./gradlew --offline --no-daemon --max-workers=1 \
  :app:connectedLocalDebugAndroidTest
```

Las pruebas focales son:

- `DatabaseHardeningMigrationTest`: migración histórica v22→v23, índices, aislamiento entre negocios y
  rollback.
- `DebtMigrationTest`: migración v26→v27, preservación de datos y estructura/invariantes de deudas.
- `CheckoutDurabilityMigrationTest`: migración v27→v28, preservación del negocio y creación vacía
  de las tablas de confirmación.
- `FullPathMigrationTest`: cada esquema histórico v1…v27 llega a v28 conservando su grafo máximo.
- `LocalDatabaseOperationalPolicyTest`: WAL, foreign keys, `foreign_key_check` y `quick_check` en
  el builder productivo.
- `FailClosedSQLiteOpenHelperFactoryTest`: apertura normal y preservación de la base/`-wal`/`-shm`
  frente a corrupción.
- `RoomSaleRepositoryTest`: venta idempotente que sobrevive a checkpoint WAL, cierre y reapertura;
  pérdida de respuesta cloud, congelación SQL del carrito, reintento exacto y confirmación con
  catálogo archivado sin alterar las líneas históricas. También rechaza ese catálogo en ventas
  locales.
- `RoomInvoiceMatchingCommitRepositoryTest`: lote mixto incompleto sin escrituras, conversión de
  unidades, rollback de productos/outbox/inventario/recibo ante fallo al cerrar el borrador,
  respuesta perdida, reintento divergente, compatibilidad con movimientos antiguos y líneas
  originales/manuales.
- `ConfirmInvoiceMatchingUseCaseTest`, `MatchScannedInvoiceLinesUseCaseTest` e
  `InvoiceMatchingViewModelTest`: conservación de unidad/moneda, validación de la revisión,
  restauración acotada del estado y recuperación del resultado confirmado.
- Suites de compra, anulación, catálogo, inventario y outbox: concurrencia, límites y rollback
  transaccional.

La evidencia histórica del 24 de agosto de 2026 está en
[`test-evidence/2026-08-24-base-datos-solida.md`](test-evidence/2026-08-24-base-datos-solida.md).
Ese informe acredita la revisión y las pruebas de esa fecha; no acredita por sí solo las suites
añadidas para v28 ni sustituye su ejecución sobre el código vigente.

## Límites honestos

Una base local robusta no equivale a recuperación ante pérdida del dispositivo:

- Desinstalar, borrar los datos de la app o perder el equipo elimina Room. `allowBackup=false`
  excluye la copia automática de Android.
- No hay copia en la nube: la variante cloud se retiró el 24 de septiembre de 2026. La única copia
  completa es el respaldo `adb run-as` que se hace desde la Mac de desarrollo antes de cada
  actualización ([`RUNBOOK.md`](RUNBOOK.md)).
- El JSON `ACCOUNTING_LEDGER` v4 es una exportación manual legible. No es un snapshot completo, no
  incluye cabeceras/líneas de venta y la app todavía no puede importarlo.
- Ante corrupción, la base se conserva y la app falla cerrada; aún no hay una reparación o
  restauración automática.
- Room no usa SQLCipher. El archivo queda protegido por el sandbox y el cifrado del dispositivo;
  el cifrado AES-GCM propio se aplica a imágenes retenidas, no a todas las tablas.

Antes de borrar datos, desinstalar o cambiar de equipo se debe hacer el respaldo `run-as` y
contactar a soporte; la exportación JSON sola no basta. Existe una base interna de `FULL_DEVICE_SNAPSHOT` con formato ZIP, validación y
primitivas de recuperación, pero todavía no ofrece exportación/importación completa al usuario:
la activación sigue en `NOT_READY` y no reemplaza la base activa. El trabajo pendiente se detalla en
[`FULL_DEVICE_SNAPSHOT_FOUNDATION.md`](FULL_DEVICE_SNAPSHOT_FOUNDATION.md).
