# Evidencia — ventas y lector externo HID

Fecha: **24 de agosto de 2026** (`America/Lima`). Esta evidencia corresponde a la ampliación de
ventas posterior a la auditoría original de Fase G. Se ejecutó desde la raíz local del proyecto y
solo con datos sintéticos.

> Actualización posterior: los casos Android que aquí quedaron pendientes se ejecutaron después en
> una Pixel Tablet AVD. La evidencia vigente —473/473 Android, precio obligatorio y ganancias— está
> en [`2026-08-24-ventas-ganancias.md`](2026-08-24-ventas-ganancias.md). Esta página se conserva como
> registro de la corrida anterior y no debe leerse como el estado actual.

## Resultado

La ampliación implementa una venta local por búsqueda manual, código escrito/pegado o lector físico
que Android reconoce como teclado HID. Un código desconocido no crea ni selecciona productos por su
cuenta: exige asociarlo a un producto activo con existencia positiva y elegir el almacén concreto.
El checkout vuelve a validar el grafo y descuenta inventario una sola vez dentro de una transacción
Room.

| Control | Resultado |
| --- | --- |
| Compilación, formato y gates | `spotlessCheck`, política Room, límites de dominio/seguridad/offline/UI, `compileLocalDebugKotlin` y compilación de `androidTest`: **BUILD SUCCESSFUL** |
| JVM en todas las variantes | `./gradlew --offline --no-daemon --max-workers=1 test`: **6.322/6.322**, 0 fallos, 0 errores y 0 omitidas en `cloud/local × benchmark/debug/release` |
| JVM local y cobertura | `:app:testLocalDebugUnitTest :app:koverVerifyLocalDebug`: **1.015/1.015**; Kover crítico: 10.450/11.742 líneas = **89,00 %**, umbral de 80 % superado |
| Ventas/lector JVM dirigidas | 48/48: `BarcodeValueTest` 4, `SalesTest` 6, `SalesContractTest` 2, `SalesViewModelTest` 19 y `KeyboardWedgeAssemblerTest` + `KeyboardWedgeRouterTest` 17 |
| Persistencia de venta en AVD | Evidencia ejecutada previa: `RoomSaleRepositoryTest` **5/5**. El archivo ahora contiene **12** casos y los 12 compilan; los 7 añadidos de negocio archivado, múltiples almacenes, hash, conflicto tardío y desbordes no se ejecutaron tras cerrar el AVD |
| Invariantes SQL de venta | `SalesPersistenceInvariantsSqlTest` compila y cubre INSERT/UPDATE directos, límites decimales, totales, slot de borrador y rollback de publicación; **no ejecutada en AVD** en este cierre |
| Migración dirigida en AVD | `MigrationTest#migrate11To21PreservesLegacyUnicodeBarcodeAndKeepsStrictNewWriteGuard`: **1/1**; atraviesa v20→v21 y conserva el código legado sin habilitar escrituras Unicode nuevas |
| UI de venta en AVD | `SalesScreenTest`: **3/3** ejecutadas; entrada manual por código, producto–almacén exacto y segunda confirmación con precio |
| Navegación en AVD | `AppNavigationTest`: **8/8**, incluida la entrada a la nueva ruta desde Inicio |
| UI ampliada | `SalesScreenTest` contiene 10 pruebas y las 10 compilan; las 7 no incluidas en la corrida dirigida previa —incluido el aviso durable de asociación— no se ejecutaron en AVD después de cerrar el emulador |

La última salida de `adb devices` solo mostró `List of devices attached`: no quedó teléfono ni
tableta virtual conectado.

## Reglas acreditadas

- El precio de venta se escribe de forma explícita y debe ser mayor que cero; no se calcula desde
  el costo promedio.
- El catálogo de venta solo ofrece productos activos con saldo positivo y separa cada almacén.
- Un código conocido se compara exactamente dentro del negocio y conserva ceros iniciales y
  mayúsculas/minúsculas.
- Un código desconocido requiere selección humana. Si el producto tenía otro, el reemplazo tiene
  una confirmación independiente. Una vez guardada la asociación ya no aparece como cancelable;
  si falla el alta posterior al carrito, la interfaz anuncia ambas realidades por separado.
- Una lectura repetida usa la línea existente del mismo producto–almacén; no crea líneas duplicadas.
- Un saldo positivo menor que una unidad puede agregarse usando exactamente el saldo disponible.
- Una observación externa nula/publicada no elimina una edición local pendiente: primero intenta
  persistirla y, si el CAS falla, exige descarte explícito.
- Enter, Enter numérico y Tab completan una ráfaga HID. Con el buffer vacío, Tab/Enter siguen
  propagándose para navegación. Timeout, cambio de dispositivo, repeticiones y exceso de 128
  caracteres se resuelven sin entregar códigos parciales.
- El checkout comprueba negocio, producto, unidad, almacén, versión, hash, precio y stock; actualiza
  saldos por CAS, conserva el costo promedio, crea movimientos `SALE` negativos y auditoría, y marca
  la venta `POSTED` en una única transacción.
- Repetir la misma confirmación devuelve `AlreadyPosted` y no vuelve a descontar existencias.
- Room v21 añade `sales`, `sale_lines` y referencias de venta en movimientos sin migración
  destructiva ni reescritura de compras/saldos históricos.

## Pendientes no simulados

1. **Lector físico:** no se conectó una marca/modelo real. Falta registrar lector, dispositivo,
   versión Android, adaptador OTG, distribución de teclado y terminador siguiendo
   [`../SALES_AND_BARCODE_SCANNER.md`](../SALES_AND_BARCODE_SCANNER.md).
2. **Instrumentadas nuevas:** los 12 casos Room, el caso de invariantes SQL y las 10 pruebas UI
   compilan, pero no se reabrió el AVD después de que el usuario pidió cerrar sus emuladores. La
   evidencia ejecutada previa permanece en Room 5/5 y UI de ventas 3/3.
3. **Fuera del alcance v1:** escaneo de códigos por cámara, sync/restauración cloud de ventas,
   exportación restaurable de cabeceras/líneas y anulación/devolución de una venta publicada.

No se declara compatibilidad universal con lectores USB/Bluetooth. La implementación acredita el
contrato estándar de teclado HID/*keyboard wedge*; cada modelo físico debe pasar su checklist antes
de usarlo en producción.

## Trazabilidad

El snapshot recibido no contiene `.git`, por lo que no existe un commit SHA que pueda registrarse
sin inventarlo. Los resultados XML locales quedaron bajo `app/build/test-results/` y el reporte de
cobertura bajo `app/build/reports/kover/reportLocalDebug.xml`.
