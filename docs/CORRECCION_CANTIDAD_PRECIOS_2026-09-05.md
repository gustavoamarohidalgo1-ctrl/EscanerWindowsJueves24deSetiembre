# Corrección del editor de cantidad y precios de Inventario

Esta corrección del 5 de septiembre de 2026 reemplaza la limitación del editor inicial descrita en [Edición y eliminación de productos desde Inventario](INVENTARIO_EDICION_PRODUCTOS_2026-09-05.md). Editar desde la lista o el detalle permite revisar y modificar la cantidad actual, el precio de compra por unidad y el precio de venta por unidad, precargados con la información existente. La corrección está instalada y verificada en la tablet por Wi-Fi desde el 5 de septiembre de 2026.

| Campo | Significado al guardar |
| --- | --- |
| Cantidad actual | Es el total final en la ubicación elegida, expresado en la unidad de inventario del producto: cambiar 10 por 7 deja 7 unidades; no agrega 7. |
| Precio de compra por unidad | Es el costo promedio unitario actual del inventario de esa ubicación. No representa el precio de una factura concreta ni modifica facturas anteriores. |
| Precio de venta por unidad | Es el precio del catálogo para ventas posteriores. Las ventas ya registradas conservan sus importes históricos. |

El editor muestra nombre y los tres valores antes de SKU y código de barras. Los precios identifican su moneda y se expresan por unidad de inventario. La ventana aprovecha el alto disponible y un ancho de hasta 960 dp; el contenido se desplaza y Guardar, Cancelar y los errores de guardado permanecen visibles.

Cuando hay varias ubicaciones, se muestra el total si todas las cantidades son conocidas; si falta alguna, se indica que el total requiere revisión. Para editar un saldo se exige elegir su ubicación. Cada opción identifica su cantidad, costo y moneda; los costos de distintas monedas no se combinan en un promedio. Una cantidad sin registrar se presenta como pendiente de revisión. Cambiar de ubicación conserva las ediciones pendientes de cada una. En inventarios compartidos donde los ajustes locales están deshabilitados, cantidad y compra se muestran para consulta; los datos del producto y el precio de venta siguen su flujo de edición.

Guardar confirma los datos del producto y los saldos editados dentro de una sola transacción. Los cambios de cantidad o costo generan ajustes nuevos en el libro de movimientos; los movimientos previos, las compras, las ventas y sus importes históricos se conservan. Cambiar únicamente los datos del producto o el precio de venta no altera saldos ni agrega movimientos. Un formulario sin cambios no avanza versiones.

La confirmación verifica la identidad del producto, el negocio y la versión revisada del producto y de cada saldo modificado, incluida la ausencia original de un saldo. Una edición concurrente o un fallo impide confirmar parcialmente el formulario. El usuario debe revisar de nuevo un registro que cambió; no se sustituye automáticamente la versión original para forzar el guardado. Los borradores conservan los valores pendientes y la lectura original para revalidarlos al recuperar el editor.

Un costo desconocido permanece vacío y no se convierte automáticamente en cero. Para aumentar cantidad cuando no hay costo registrado, debe ingresarse un costo explícito: 0 es válido y significa un costo conocido de cero. Se puede conservar un costo desconocido intacto al editar solo los datos del producto. Los promedios existentes mantienen su precisión almacenada cuando no se modifican.

No hay cambio de esquema ni nueva migración: continúa el esquema Room 28. La corrección utiliza el producto, los saldos y los movimientos de ajuste existentes, con el puerto `ProductEditingRepository` y su implementación transaccional. La validación preparada incluye comprobaciones del editor y un recorrido que modifica los tres valores, vuelve a abrirlos y compara el saldo resultante y la conservación de los movimientos anteriores; sus resultados se registran a continuación.

## Validación y aplicación en la tablet

Compilación local, análisis estático y 1.529 pruebas unitarias aprobados. Pasaron 19 pruebas instrumentadas del editor y la persistencia, y 52 de regresión de inventario, catálogos, registro y ventas con escáner. Android Lint terminó sin errores y con 42 advertencias. Las pruebas que modifican datos se ejecutaron exclusivamente en un emulador temporal dedicado, eliminado al concluir. No cambió el esquema Room 28.

La APK se instaló en `192.168.18.27:5555` mediante actualización conservando datos; se verificaron la compatibilidad de la firma y la huella SHA-256 del paquete efectivamente instalado. En la tablet se abrió el editor y se comprobaron los tres campos visibles, habilitados y precargados con valores idénticos al respaldo. Se canceló sin guardar cambios de prueba.

La comparación entre las 10:24:51 y las 10:28:04 (America/Lima) verificó las 36 tablas SQLite: ninguna cambió. La configuración también permaneció idéntica y la base pasó integridad y claves foráneas. Se conservan 121 productos, 121 saldos, 4 ventas, 5 líneas de venta y 126 movimientos.

El respaldo local está en `/Users/gustavo/Desktop/Backup/FacturaStock_2026-09-05_10-19-11_antes_corregir_cantidad_precios`, con APK original y actualizada, datos completos, SQLite consolidada, capturas antes/después e integridad SHA-256. Las evidencias técnicas de este correctivo están en `build/reports/inventory-stock-editor/2026-09-05/summary.json` y sus archivos relacionados.


## Precio de compra legible

La precarga del costo de compra elimina únicamente ceros decimales finales (7.500000000000 → 7.5), sin redondear ni modificar el costo almacenado. Se conservan borradores anteriores y texto mientras se escribe. Las 44 pruebas de CatalogsViewModel y el análisis estático pasaron. Se instaló la APK y se comprobó en la tablet el producto Ace 700 G con compra 7.5, cantidad y venta sin cambios. Las 36 tablas y la configuración resultaron idénticas antes y después. Evidencia: build/reports/precio-compra-legible/2026-09-05/summary.json.
