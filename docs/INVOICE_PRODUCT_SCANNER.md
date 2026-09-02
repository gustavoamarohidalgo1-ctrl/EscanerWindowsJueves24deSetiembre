# Escáner de productos desde factura

La entrada principal de **Comprobantes** tiene solo dos acciones de la persona:

1. **Abrir cámara** crea un borrador privado y abre CameraX directamente.
2. **Tomar foto** guarda la captura y arranca el OCR local automáticamente.

No hay selección de origen, vista previa, cabecera, revisión de líneas, vinculación ni confirmación
en este recorrido. Las rutas anteriores siguen registradas para recuperar estados históricos, pero
no forman parte del flujo nuevo.

## Qué se guarda

El OCR y el parser trabajan en el teléfono. El importador considera exclusivamente filas de
producto: exige una descripción resuelta con confianza alta y, además, una señal estructural fiable
de la misma fila (cantidad, costo, total, código o código de barras). Una lectura dudosa se omite en
lugar de convertirse silenciosamente en un producto.

Por cada nombre elegible:

- normaliza espacios y mayúsculas para no duplicar un producto ya existente;
- consolida líneas repetidas dentro de la misma factura;
- guarda solo el producto de catálogo con su unidad segura; si la unidad leída no está configurada,
  usa la unidad activa `NIU`;
- no copia automáticamente proveedor, RUC, comprobante, importe, costo, precio de venta, código,
  código de barras, almacén ni cantidad.

En particular, este flujo **no crea una compra ni movimientos de inventario**. El producto aparece
en **Productos** con existencia cero hasta que una operación de inventario válida lo afecte.

El lote de productos y sus operaciones de outbox se escriben en una sola transacción Room. Si el
negocio usa la variante `cloud`, tiene una cuenta enlazada y activó **Respaldar registros en la
nube**, esa outbox sincroniza los productos; sin conexión quedan guardados localmente y pendientes
de envío. El flavor `local` nunca intenta usar la red.

## Foto, OCR y reintentos

Después de guardar o reconocer como existentes todos los productos seguros, se elimina el borrador
con su foto, snapshot OCR y proyección temporal. La foto de este escaneo de catálogo no se publica
como documento de compra. Si no se detecta ninguna fila segura o falla el guardado, el borrador se
conserva para reintentar; una repetición es idempotente por nombre y no duplica productos que ya se
hubieran publicado antes de una interrupción.

Una ejecución OCR interrumpida conserva su token durable. Al reanudar, la app limpia por CAS solo
esa ejecución y vuelve a procesar la captura. El éxito siempre termina en **Productos**; nunca en
una pantalla de confirmación de compra.
