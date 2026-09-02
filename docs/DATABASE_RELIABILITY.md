# Confiabilidad de la base de datos local

FacturaStock usa Room sobre SQLite como fuente de verdad **en el dispositivo**. El esquema vigente
es v27 y contiene 32 tablas para negocio, catálogos, borradores OCR, compras, ventas, deudas, pagos,
inventario, auditoría y sincronización. Este documento separa las garantías implementadas de los límites que
todavía requieren una fase distinta de respaldo y restauración.

## Garantías implementadas

| Riesgo | Control |
| --- | --- |
| Escritura parcial de una compra o venta | Las cabeceras, líneas, saldos, movimientos, auditoría y outbox correspondientes se confirman en una sola transacción Room. Un fallo tardío revierte el lote completo. |
| Doble toque o reintento | Claves únicas, claves idempotentes y compare-and-set (CAS) hacen que el segundo intento devuelva el resultado ya publicado o falle sin repetir inventario. |
| Concurrencia sobre existencias | `inventory_balances.version` protege cada cambio con CAS; los movimientos son append-only y permiten recalcular y comparar el saldo. |
| Referencias inválidas | Foreign keys, índices únicos y triggers verifican negocio, catálogo, borrador, compra, venta, deuda, pago, producto, unidad y almacén, incluso cuando una FK simple no puede expresar el aislamiento entre negocios. |
| Edición de historia publicada | Compras, ventas, deudas, pagos, líneas, movimientos y auditoría publicados tienen invariantes SQL que bloquean reemplazos, cambios o borrados no autorizados. La anulación de compra crea movimientos compensatorios; los pagos de deuda avanzan el saldo mediante CAS y permanecen append-only. |
| Cierre de proceso durante uso normal | WAL está fijado explícitamente; Room reabre la base y los borradores, outbox y operaciones confirmadas permanecen en disco. |
| Actualización de la app | Hay una migración explícita por cada salto v1→v27. No existe `fallbackToDestructiveMigration`. Todas las rutas históricas se validan contra los JSON exportados y ejecutan `foreign_key_check` e `integrity_check`. |
| Corrupción detectada al abrir | La factory productiva falla cerrada: no autoriza `allowDataLossOnRecovery` ni delega al handler que puede borrar la base y sus sidecars. Conserva el original para diagnóstico/recuperación explícita. |
| Cambio de esquema accidental | `verifyRoomSchemaPolicy` exige el JSON nuevo, la migración declarada y registrada, WAL explícito y la factory no destructiva antes de compilar. La CI además protege el historial append-only. |
| Consultas que crecen con el negocio | v27 conserva los índices compuestos de borradores, compras, ventas, auditoría, outbox, movimientos, inventario y espejo remoto, y añade los índices de cuentas por cobrar por estado, nombre y actualización. |

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
prueba full-path recorre por separado cada origen v1…v22 hasta v23.

## Cambios v23 → v27

- v23 → v24 añade recibos append-only de publicación de páginas capturadas.
- v24 → v25 añade el índice de salud de la outbox por tenant.
- v25 → v26 añade a `remote_sync_states` el cursor independiente del feed compartido de
  inventario y ventas.
- v26 → v27 crea vacías `debts` y `debt_payments`, con claves foráneas, índices y triggers que
  exigen una deuda por venta a crédito publicada y una transición CAS por cada pago. La migración no
  inventa cuentas por cobrar a partir de ventas históricas.

`DebtMigrationTest` verifica preservación de las filas v26 e instalación del ledger nuevo;
`FullPathMigrationTest` recorre cada origen histórico v1…v26 hasta v27.

## Política de apertura y durabilidad

El builder productivo selecciona `WRITE_AHEAD_LOGGING` de forma explícita, habilita foreign keys y
usa `FailClosedSQLiteOpenHelperFactory`. WAL mejora la convivencia entre lecturas y una escritura y
evita depender del modo `AUTOMATIC` de Room, que puede elegir otro journal según el dispositivo.

La configuración Android habitual de WAL usa `synchronous=NORMAL`: mantiene consistencia y resiste
un cierre de la app, pero un apagado abrupto del sistema o pérdida de energía todavía puede revertir
la transacción más reciente. No se simula `FULL` con una PRAGMA aplicada a una sola conexión del
pool. Adoptarlo exige configurar todas las conexiones de manera uniforme y medir la latencia en el
dispositivo objetivo.

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
- `FullPathMigrationTest`: cada esquema histórico v1…v26 llega a v27 conservando su grafo máximo.
- `LocalDatabaseOperationalPolicyTest`: WAL, foreign keys, `foreign_key_check` y `quick_check` en
  el builder productivo.
- `FailClosedSQLiteOpenHelperFactoryTest`: apertura normal y preservación de la base/`-wal`/`-shm`
  frente a corrupción.
- `RoomSaleRepositoryTest`: venta idempotente que sobrevive a checkpoint WAL, cierre y reapertura.
- Suites de compra, anulación, catálogo, inventario y outbox: concurrencia, límites y rollback
  transaccional.

La evidencia fechada de la última ejecución está en
[`test-evidence/2026-08-24-base-datos-solida.md`](test-evidence/2026-08-24-base-datos-solida.md).

## Límites honestos

Una base local robusta no equivale a recuperación ante pérdida del dispositivo:

- Desinstalar, borrar los datos de la app o perder el equipo elimina Room. `allowBackup=false`
  excluye la copia automática de Android.
- Firebase cubre compras, catálogo, inventario, ventas publicadas, deudas y pagos de negocios
  enlazados. El pull materializa saldos, ventas y cuentas por cobrar compartidas, pero no restaura
  compras completas, imágenes, borradores ni ajustes.
- El JSON `ACCOUNTING_LEDGER` v4 es una exportación manual legible. No es un snapshot completo, no
  incluye cabeceras/líneas de venta y la app todavía no puede importarlo.
- Ante corrupción, la base se conserva y la app falla cerrada; aún no hay una reparación o
  restauración automática.
- Room no usa SQLCipher. El archivo queda protegido por el sandbox y el cifrado del dispositivo;
  el cifrado AES-GCM propio se aplica a imágenes retenidas, no a todas las tablas.

Por tanto, antes de borrar datos, desinstalar o cambiar de equipo se debe conservar la exportación y
contactar a soporte. Una fase de recuperación total tendría que añadir un formato completo y
firmado, importación validada y cobertura del resto del estado solo local, con conflictos y pruebas
de restauración; no debe afirmarse que esa capacidad ya existe.
