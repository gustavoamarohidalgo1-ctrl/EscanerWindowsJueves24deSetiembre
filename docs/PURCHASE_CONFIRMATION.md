# Confirmación atómica e idempotente de compras

La confirmación productiva parte exclusivamente de un `PreparedPurchase` cuyo borrador está en
`READY_TO_POST`. La pantalla entrega el `draftId` y el `logicalHash` exacto mostrado en el resumen;
el caso de uso captura el negocio activo y la política de costo, y el adaptador Room vuelve a leer
y validar esa misma instantánea dentro de la transacción que publica el libro de compras. Si otra
instancia reabre y prepara contenido distinto, la orden obsoleta devuelve `PreparedChanged` sin
escribir artefactos.

## Unidad de commit

La transacción coordinada por `RoomPurchasePostingRepository`, cuyo lote final ejecuta
`PurchasePostingDao.postAtomically`, escribe como un único grafo SQLite:

1. proveedor faltante, productos nuevos staged y alias confirmados;
2. encabezado de compra en estado transitorio `DRAFT`;
3. líneas con la decisión de costo y la procedencia del producto congeladas;
4. enlace del borrador y su hash preparado;
5. un movimiento `PURCHASE` por cada línea inventariable;
6. saldos y costo promedio con versión optimista;
7. auditoría `PURCHASE_POSTED` y outbox `SYNC_PURCHASE`;
8. compra `POSTED` y borrador `COMMITTED`.

Un fallo en cualquier punto, incluido el último cambio a `COMMITTED`, revierte el grafo completo.
Una compra `POSTED` no admite edición destructiva: las líneas y movimientos son append-only y una
corrección futura debe usar anulación/movimientos compensatorios.

## Idempotencia y concurrencia

La identidad de la orden es `draftId + PreparedPurchase.logicalHash`. Las claves de compra,
movimientos y outbox se derivan de esa identidad y los identificadores persistidos son UUID
deterministas. Antes de insertar, la transacción busca una compra por `sourceDraftId` y por clave
idempotente. Un reintento posterior al commit devuelve el mismo `purchaseId`; no vuelve a crear
líneas, movimientos, auditorías ni operaciones outbox.

El mismo hash esperado también se compara al resolver `AlreadyPosted`: un comando obsoleto no
puede reclamar como propio un posting creado desde otra instantánea del borrador.

La UI combina estado `isConfirming` y un `Mutex.tryLock`: dos toques sobre el botón solo inician una
llamada. Esta protección mejora la experiencia, pero la garantía real está en Room y sus índices,
por lo que también cubre recreación de pantalla, dos instancias y reintentos después de un cierre.

## Resoluciones contables

- Un producto existente y su unidad quedan congelados como `EXISTING`. Crear desde la vinculación
  solo guarda un `StagedPurchaseProduct` con UUID estable dentro del borrador; todavía no toca el
  catálogo. La confirmación revalida ID, negocio, unidades, nombre, SKU y barcode, y lo inserta como
  `CREATED_IN_DRAFT` dentro del mismo commit que la compra. Una carrera o un fallo tardío revierte
  también ese producto y sus alias.
- El destino es la ubicación activa del producto o, si no existe, la única ubicación activa del
  negocio. Cero o varias opciones bloquean la confirmación.
- La revisión exige escoger por línea `INCLUDED`, `EXCLUDED` o `EXEMPT`; `UNKNOWN` bloquea.
  `INCLUDED`/`EXCLUDED` también exigen un importe de IGV explícito (el usuario puede escribir `0`),
  por lo que un campo vacío nunca se transforma en evidencia cero. El cálculo usa la política
  `NET`/`GROSS` vigente, aplica el impuesto una sola vez y congela tratamiento, evidencia y resultado.
- La política técnica de persistencia es escala 18 con `HALF_EVEN`; cualquier redondeo queda
  registrado como advertencia de costo.
- Cuando la suma revisada de líneas no coincide con el total objetivo, preparación exige
  aceptación explícita y un motivo de 10 a 500 caracteres. El ajuste usa la convención
  `suma de líneas + ajuste = total`, queda congelado junto con su motivo en el hash lógico y se
  replica en el payload común de `PURCHASE_POSTED`/`SYNC_PURCHASE`.
- Una nota de crédito se bloquea hasta que el modelo pueda referenciar y revertir la compra y los
  movimientos originales.

El proveedor se vuelve a resolver por ID/RUC dentro de la transacción. Si no existe, solo se crea
cuando el snapshot conserva RUC y razón social suficientes; nunca se inventa un nombre. Los alias
confirmados se insertan idempotentemente y no sobrescriben un alias que ya pertenece a otro
producto. Sus UUID son estables por borrador y clave natural: un reintento conserva la misma
identidad, pero editar después el RUC o el texto de un alias no reserva para siempre el UUID de la
clave liberada.

## Duplicados

La previsualización de la UI es informativa. La transacción repite la detección por negocio,
proveedor o RUC, tipo, serie y correlativo antes de insertar. Una coincidencia exacta devuelve la
compra existente y no mueve stock. Fecha, total y hash de imagen siguen siendo señales
secundarias; el hash nunca es una identidad única.

Una excepción exacta no se audita antes de publicar. La autorización revalidada (actor, rol,
motivo y hash preparado) viaja en el comando y Room vuelve a comprobar que corresponda a la misma
instantánea y que la compra objetivo siga siendo el mismo documento `POSTED` o `VOIDED`. El esquema
v14 conserva una identidad `PRIMARY` única y asigna a la excepción un slot estable por borrador;
sus metadatos y el evento `PURCHASE_DUPLICATE_OVERRIDE` se insertan junto con compra, stock y
outbox. Si falla cualquier parte, tampoco queda autorización huérfana.
