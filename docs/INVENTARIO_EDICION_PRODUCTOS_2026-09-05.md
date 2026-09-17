# Edición y eliminación de productos desde Inventario

**Actualización del editor:** la omisión de cantidad y costo descrita en este informe pertenece a la implementación inicial y ha sido reemplazada por la [Corrección de cantidad y precios del 5 de septiembre de 2026](CORRECCION_CANTIDAD_PRECIOS_2026-09-05.md). El comportamiento vigente en el código permite editar cantidad total, costo promedio unitario y precio de venta; la evidencia de instalación y pruebas que sigue corresponde a la versión inicial.

Implementado e instalado por Wi-Fi en la tablet el 5 de septiembre de 2026, sobre la aplicación existente con `adb install -r`. Se conservan `com.facturastock.app`, la firma original, versión 1.0.4 y esquema Room 28. No se desinstaló ni se limpiaron datos.

La lista y el detalle ofrecen **Editar** y **Eliminar**. Editar abre por ID estable el formulario de nombre, SKU, código de barras y precio; conserva unidad, moneda original, identidad e historial. En aquella implementación inicial, el formulario no cambiaba existencias; esta limitación queda reemplazada por la corrección enlazada arriba. La recuperación de pantalla conserva los campos editados y la versión revisada; un producto desaparecido nunca se recrea al guardar.

Eliminar exige una confirmación que identifica el producto y explica que será archivado. **Activos** y **Archivados** permiten consultar los productos y restaurarlos. Archivo y restauración verifican negocio y versión dentro de la transacción, y conservan saldos, movimientos, ventas y deudas. El lector se suspende durante la confirmación y en Archivados; se reactiva al volver a Activos, incluso después de cerrar un diálogo restaurado.

Validación: 1.519 pruebas JVM locales, 1.697 de cloud y 52 instrumentadas en un emulador dedicado, todas sin fallos. Se comprobaron edición sin barcode, cancelación y recreación, archivo/restauración con historial, cambios concurrentes, rollback ante fallo de outbox, y los recorridos existentes del lector y las ventas. Análisis estático y formato correctos; Lint local: 0 errores y 42 advertencias. El formato local comparó el árbol de trabajo con HEAD, ya que el repositorio solo tiene un commit.

En la tablet se abrieron el editor y la confirmación de eliminación y se cancelaron sin guardar. El SHA-256 del APK instalado coincide con el probado. La comparación entre las 09:32:37 y 09:44:24 (Lima) encontró las 36 tablas y la configuración idénticas: 121 productos, 121 saldos, 4 ventas, 5 líneas y 126 movimientos. Integridad y claves foráneas correctas.

Respaldo local: `/Users/gustavo/Desktop/Backup/FacturaStock_2026-09-05_09-16-10_antes_editar_inventario/`, con APK anterior y actualizado y capturas antes/después. Evidencia técnica: `build/reports/inventory-actions/2026-09-05/summary.json`, registros de pruebas y comparación de la tablet. Las pruebas destructivas de datos sintéticos se ejecutaron únicamente en el emulador dedicado, que se retiró al terminar. No se desplegó backend.
