# Costeo de inventario

`InventoryCostingService` es la única regla numérica para convertir una línea de compra en
cantidad y costo de inventario. Trabaja exclusivamente con `BigDecimal`: no usa `Float`,
`Double`, topes que recorten valores ni redondeos implícitos.

## Decisiones obligatorias

Cada cálculo recibe de forma explícita:

- cantidad y unidad leídas en el comprobante;
- factor de conversión hacia la unidad de inventario (`1` cuando ambas unidades coinciden);
- costo unitario leído y descuento total de la línea;
- tratamiento tributario `INCLUDED`, `EXCLUDED`, `EXEMPT` o `UNKNOWN`;
- evidencia tributaria: importe explícito, tasa explícita o ausencia explícita;
- política de costo `NET` o `GROSS`;
- saldo y costo promedio anteriores;
- escala y `RoundingMode` que gobiernan toda división.

`UNKNOWN` nunca calcula un costo. `INCLUDED` y `EXCLUDED` requieren una tasa o un importe;
`EXEMPT` exige que no exista impuesto positivo. Una evidencia faltante o contradictoria devuelve
`DecisionRequired`, por lo que la tasa configurada de la aplicación jamás reemplaza en silencio
lo leído o confirmado para una línea.

En la revisión móvil, elegir `INCLUDED` o `EXCLUDED` no convierte un campo de IGV vacío en cero:
el usuario debe confirmar un importe, incluido `0` cuando corresponda. Los snapshots anteriores
se decodifican como `UNKNOWN` y deben reabrirse; no se reconstruye una decisión fiscal histórica.

## Fórmulas

Para cantidad de compra `q`, factor `f`, costo unitario leído `c` y descuento de línea `d`:

```text
cantidadInventario = q × f
totalLeído          = q × c
baseDescontada      = totalLeído - d
```

Un descuento mayor que el total leído bloquea el cálculo. Sobre `baseDescontada`, el tratamiento
tributario se aplica una sola vez:

```text
INCLUDED + importe: bruto = base; impuesto = importe; neto = bruto - impuesto
INCLUDED + tasa:    bruto = base; neto = bruto / (1 + tasa); impuesto = bruto - neto
EXCLUDED + importe: neto = base; impuesto = importe; bruto = neto + impuesto
EXCLUDED + tasa:    neto = base; impuesto = neto × tasa; bruto = neto + impuesto
EXEMPT:             neto = bruto = base; impuesto = 0
```

La política selecciona exactamente uno de esos totales:

```text
totalAplicado = neto   cuando CostPolicy = NET
totalAplicado = bruto  cuando CostPolicy = GROSS
costoAplicadoPorUnidad = totalAplicado / cantidadInventario
```

Así, una línea con precio bruto 118 e IGV incluido de 18 conserva 118 bajo política `GROSS`; no
vuelve a sumar el impuesto. Bajo `NET`, aplica 100.

Para saldo anterior positivo `q0` con promedio `c0`, y una o más entradas que comparten el mismo
producto y almacén:

```text
promedioNuevo = (q0 × c0 + suma(totalAplicadoExacto)) /
                (q0 + suma(cantidadInventario))
```

El numerador usa los totales aplicados exactos, no
`cantidadInventario × costoAplicadoPorUnidad` después de redondearlo. Si `q0 <= 0`, la
valorización heredada no representa existencias disponibles: el promedio se reinicia al costo
promedio exacto de la entrada (o entradas) y se registra
`PREVIOUS_NON_POSITIVE_BALANCE_REBASED`. La cantidad resultante sí conserva el saldo anterior,
incluso si permanece negativa.

## Precisión, redondeo y límites

Las multiplicaciones y sumas conservan su valor exacto. Las divisiones y el impuesto calculado
desde una tasa explícita en un precio sin impuesto pueden aplicar la escala y el modo indicados
en `InventoryCostRoundingPolicy`. Cuando una reducción no es exacta se registra
`CALCULATION_ROUNDED`; con `RoundingMode.UNNECESSARY`, esa misma situación bloquea el cálculo en
lugar de perder precisión.

`InventoryCostingLimits` define umbrales razonables de cantidad, factor, costos, impuesto y
saldo. Excederlos agrega advertencias al resultado, pero nunca modifica el número calculado.

## Persistencia y auditoría

Desde Room v13, cada `purchase_line` conserva por separado:

- `unitCost` (mapeado como `readUnitCost`), el costo leído y preparado;
- `appliedUnitCost` y `appliedCostTotal`, el costo efectivamente incorporado;
- factor, cantidad convertida y descuento;
- tratamiento y evidencia tributarios;
- política, escala, modo de redondeo y advertencias.

`stock_movements.unitCost` repite el costo aplicado por unidad, y el coordinador transaccional
recalcula el resultado antes de aceptar líneas, movimientos y saldo. La migración v12 → v13
conserva `unitCost` y deja los campos nuevos en `NULL` para compras históricas: no inventa una
decisión tributaria o de costos retroactiva.
