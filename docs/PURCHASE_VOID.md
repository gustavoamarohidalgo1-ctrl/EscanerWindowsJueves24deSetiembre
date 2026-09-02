# Anulación compensatoria de compras

Una compra `POSTED` nunca se borra ni se reescribe. La anulación conserva encabezado, documento,
líneas, snapshot preparado e imágenes bajo la misma política de retención
([`PRIVACY_DATA_LIFECYCLE.md`](PRIVACY_DATA_LIFECYCLE.md)); únicamente añade hechos
compensatorios y cambia el ciclo de vida a `VOIDED`.

## Autorización y confirmación

`PreviewPurchaseVoidUseCase` obtiene el negocio activo y el principal desde la política local de
acceso. La UI no envía actor ni rol. Solo `OWNER` y `MANAGER` pueden continuar. El motivo se recorta
y debe tener entre 10 y 500 caracteres.

El preview muestra por producto/almacén el saldo actual, el delta inverso y el saldo resultante.
También entrega un SHA-256 que sella actor/rol, estado/timestamp de la compra, cada movimiento
`PURCHASE` original y cantidad, costo, moneda, versión y timestamp de cada saldo. `VoidPurchaseUseCase` exige
confirmación explícita y Room recalcula ese sello dentro de la transacción; si cambió, devuelve el
nuevo impacto para volver a revisarlo.

## Política de saldo negativo

La política v1 es `ALLOW_WITH_VISIBLE_WARNING`. Anular corrige la historia incluso cuando unidades
de la compra ya fueron consumidas y la reversa deja existencia negativa. El saldo no se recorta a
cero ni se oculta: el impacto negativo aparece antes de confirmar, se devuelve tras el commit y se
conserva como alerta de inventario pendiente de conciliación.

La cantidad de cada `VOID` es el opuesto decimal exacto de su `PURCHASE`. El costo unitario se copia
solo como evidencia congelada. La proyección resta cantidad y conserva `averageUnitCost` vigente,
incluso si el resultado es cero o negativo: reconstruir un promedio anterior restando costo histórico
sería incorrecto después de consumos, redondeos u otros movimientos.

## Commit e idempotencia

Una sola transacción Room:

1. vuelve a validar estado `POSTED`, rol, libro, líneas, sello y saldos;
2. actualiza cada saldo mediante compare-and-set de versión y valores de apertura;
3. inserta un `VOID` append-only por movimiento `PURCHASE`;
4. inserta auditoría `PURCHASE_VOIDED` y outbox `SYNC_PURCHASE_VOID` con payload canónico;
5. ejecuta al final el CAS `POSTED -> VOIDED`.

Los UUID y claves idempotentes se derivan de `purchaseId + originalMovementId`. Una repetición sobre
un grafo completo devuelve `AlreadyVoided` y no añade otra reversa. Cualquier excepción antes del
último paso revierte saldos, movimientos, auditoría y outbox juntos.

Los triggers comparan cardinalidad, ubicación, producto, costo, moneda y el texto decimal opuesto de
cada par. No usan `CAST(... AS NUMERIC)`, porque SQLite aproximaría cantidades grandes y podría
aceptar como cero una diferencia real.
