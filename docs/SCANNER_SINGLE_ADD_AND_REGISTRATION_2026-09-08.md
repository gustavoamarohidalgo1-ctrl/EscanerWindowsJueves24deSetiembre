# Un producto por escaneo y registro desde Ventas

Fecha: 2026-09-08. Revisión validada e instalada en la tablet Huawei AGS6_W09 mediante
actualización con conservación de datos.
No sustituye ni modifica los reportes históricos de las revisiones anteriores.

## Comportamiento

En **Vender → Escáner físico**, un producto se incorpora una sola vez mientras esté en el carrito.
La primera lectura añade una unidad, o el saldo fraccionario disponible si es menor. Las unidades
que se venderán se ajustan manualmente en **Cantidad**. Las relecturas muestran **Ya está en la
venta** y conservan cantidad, precio y ubicación.

La comprobación se realiza por `productId` en todas las líneas del carrito. Por ello, leer el SKU
del mismo producto, elegir una sugerencia o encontrarlo en otro almacén no añade otra unidad. Al
retirar el producto por completo se permite añadirlo con una nueva lectura. El modo de venta manual
conserva la posibilidad de aumentar la cantidad al seleccionar el mismo producto y almacén.

Un código desconocido ofrece **Registrar producto**. Se reutiliza el formulario de Inventario con
nombre, stock inicial, precio de compra y precio de venta; al guardar se vuelve a la misma venta.
Cancelar no crea el producto ni mueve existencias. Si el registro se guardó pero la línea de venta
falló, se informa **Producto registrado** y se pide revisar el aviso y reescanear; no se revierte
el alta ni se duplica su stock.

## Implementación

- **Cola de lecturas:** `SalesViewModel` agrupa únicamente códigos consecutivos iguales que aún
  estén pendientes o en proceso. Conserva los códigos diferentes y el orden de secuencias como
  `A → B → desconocido → A`. Mantiene el límite de 32 pendientes y la cancelación por sesión.
- **Producto ya presente:** se consulta el carrito actual antes de resolver stock o pedir almacén,
  y se verifica otra vez en el punto de incorporación. No existe una caché permanente de códigos
  vendidos que impida volver a añadir un producto retirado.
- **Confirmación visible:** `LastScanAdded.alreadyInCart` distingue una incorporación guardada de
  una relectura. La cantidad mostrada sigue las emisiones persistidas del carrito; desaparece al
  eliminarse la línea. Una emisión de versión inferior del mismo carrito y negocio no retrocede
  el estado ya confirmado.
- **Registro y navegación:** una `ProductRegistrationRequest` contiene `requestId`, código,
  negocio y carrito. El formulario devuelve el producto y negocio efectivamente guardados.
  Solicitud y resultado se conservan mediante `SavedStateHandle` hasta resolver el retorno.
- **Retorno seguro:** se espera a que el carrito y el catálogo estén disponibles, se relee el
  producto y se refresca su detalle de inventario. Se comprueba el negocio y la moneda actuales
  después de las lecturas suspendidas. Un resultado obsoleto o repetido no agrega otra línea.
- **Fallos independientes:** `productRegisteredWithoutCartAdd` informa que el alta de inventario
  se confirmó pero la incorporación al carrito no terminó. Abrir el formulario no confirma la
  venta; el stock de salida sigue cambiando únicamente en el checkout existente.

Se conservan las reglas de asociación explícita y de sugerencias de códigos incompletos. Elegir
una coincidencia parecida no reemplaza el código guardado. El registro desde Ventas aplica las
mismas restricciones de inventario cloud que el registro desde Inventario.

## Cobertura añadida

Las pruebas incluyen ráfaga de 32 lecturas iguales con un solo guardado, códigos distintos en
orden, barcode y SKU del mismo producto, cantidades editadas, eliminación y relectura, almacenes,
sugerencias, fallos y reintentos. La integración de registro cubre cancelación, stock y precio
disponibles antes de emitir el catálogo, entrega duplicada, cambio de negocio/carrito, restauración
del estado y producto guardado sin incorporación a la venta.

El contrato funcional actualizado está en
[`SALES_AND_BARCODE_SCANNER.md`](SALES_AND_BARCODE_SCANNER.md).

## Estado de cierre

| Comprobación de esta revisión | Estado |
| --- | --- |
| Pruebas unitarias local | 1629 correctas, sin fallos ni omisiones |
| Pruebas unitarias cloud | 1807 correctas, sin fallos ni omisiones |
| `ciStaticAnalysis` | Correcto |
| Android Lint local y cloud | 0 errores y 66 advertencias por variante |
| Instrumentación en emulador Android 15 | 86 correctas |
| Ráfaga en emulador | 32 lecturas, 1 unidad guardada, 117 ms |
| Instrumentación en tablet Huawei Android 10 | 64 correctas |
| Ráfaga en tablet con dispositivo `SCANNER`, id 6 | 32 lecturas, 1 unidad guardada, 227 ms |
| Instalación de producción y hash del APK | Correctos, 2026-09-08 14:25:45 |
| Datos previos de la aplicación | Conservados; mismo inode y fecha original de instalación |
| Lectura óptica disparada manualmente | No ejecutada; la instrumentación envía eventos de entrada Android |

Las cifras de la tabla corresponden a esta revisión. La medición incluye el envío de eventos,
el guardado y la actualización de la interfaz; no mide la velocidad óptica del escáner.
Las evidencias se conservan en `build/reports/single-scan-sales-registration-2026-09-08/`.

## Instalación verificada

Paquete `com.facturastock.app`, versión `1.0.4` (código 5), actualizado con `adb install -r`.
La firma y el esquema Room 28 coinciden con la versión anterior. El hash SHA-256 del APK
instalado coincide exactamente con el artefacto validado:

```text
0a652f3419512b7ce44d193083ae7998e87ad584076d160d99e1d4e9470011e9
```

Se preservaron el inode de datos `126747` y la fecha original de instalación
`2026-09-04 11:20:53`. Las dos aplicaciones temporales de QA se eliminaron al terminar.
Los productos y ventas ficticios de las pruebas se mantuvieron en su paquete aislado.
