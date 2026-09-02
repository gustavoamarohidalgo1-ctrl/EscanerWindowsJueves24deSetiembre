# Inventario y trazabilidad

La lista y el detalle leen exclusivamente `Flow` de Room. `inventory_balances` es la proyección
cacheada por `(businessId, productId, locationId)` y `stock_movements` es el libro append-only. La
confirmación de una compra inserta exactamente un movimiento `PURCHASE` por línea y actualiza el
saldo en la misma transacción; por eso la primera emisión posterior al commit nunca ve un grafo
parcial.

El detalle de producto relee cabecera, posiciones y movimientos dentro de una sola transacción de
lectura cada vez que Room invalida el producto, el saldo o el libro. Un movimiento con
`purchaseId` abre el detalle de esa compra; un `ADJUSTMENT` no inventa un origen.

## Diagnóstico

“Recalcular y comparar” no repara ni reescribe datos. Abre una instantánea Room, suma cantidades
con `BigDecimal` y compara el resultado exacto con la proyección. Para costo reproduce lotes
`PURCHASE` v13+ usando `purchase_lines.appliedCostTotal`, la cantidad convertida y la escala/modo
congelados. No usa `movement.unitCost × quantity`, porque el unitario puede estar redondeado.

El costo se declara **no verificable**, y nunca como divergencia demostrada, cuando hay historia
legacy, `VOID`, `ADJUSTMENT`, moneda inconsistente, saldo inicial no explicado o empate causal de
dos lotes. Una diferencia de cantidad anula también la comparación de promedio. El reporte lleva
la versión y timestamp del saldo observado; cualquier nueva emisión lo invalida para que una
alerta antigua no sobreviva a una compra posterior.

Los movimientos no exponen `UPDATE`/`DELETE` en el DAO y triggers SQLite rechazan ambos. Cambiar la
unidad de inventario de un producto o el código de una unidad después de crear historia también se
rechaza: hacerlo reinterpretaría cantidades históricas sin una conversión explícita.

La corrección de una compra usa movimientos `VOID` compensatorios y la política negativa documentada
en [PURCHASE_VOID.md](PURCHASE_VOID.md); nunca modifica ni elimina el movimiento `PURCHASE` original.
