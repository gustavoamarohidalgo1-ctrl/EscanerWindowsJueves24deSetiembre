# Escáner de productos desde factura

El recorrido principal de **Comprobantes** permite fotografiar una factura, revisar los productos
extraídos y confirmar su ingreso al inventario local:

1. **Abrir cámara** crea un borrador privado y abre CameraX.
2. **Tomar foto** guarda la captura e inicia automáticamente el OCR y el parser locales.
3. **Revisar y Emparejar** muestra las líneas persistidas del borrador y sus coincidencias de
   catálogo. La persona puede vincular un producto existente, cambiar la coincidencia o preparar
   un producto nuevo, y corregir cantidad, costo unitario, unidad y moneda.
4. **Guardar en Almacén** confirma el lote completo y termina en **Productos**.

Si el OCR no encuentra productos, se puede reintentar o continuar manualmente. Las rutas de
cabecera, revisión de compra y confirmación de compra siguen disponibles para otros recorridos;
esta entrada usa la pantalla de matching.

## Revisión de cada línea

El OCR aporta datos para revisar; no confirma una entrada por sí solo. Cada línea necesita un
producto activo del negocio, cantidad positiva, costo unitario conocido y no negativo, moneda
coincidente con la del negocio y una unidad resuelta. El costo `0` solo expresa un producto
gratuito; un costo o una cantidad desconocidos conservan su estado pendiente.

La unidad y moneda leídas se conservan desde el borrador. Si el código de unidad coincide con la
unidad de inventario o la unidad de compra configurada para el producto, se puede resolver esa
correspondencia. Si falta o no coincide, la persona debe declarar explícitamente en qué unidad
están **tanto la cantidad como el costo**:

- **Unidad de inventario:** se ingresan la cantidad y el costo sin aplicar el factor de compra.
- **Unidad de compra:** se multiplica la cantidad por el factor configurado y se divide el costo
  por ese mismo factor. Por ejemplo, 2 cajas a S/120 por caja, con 12 unidades por caja, ingresan
  24 unidades a S/10 por unidad.

Una conversión cuyo costo unitario tenga decimales periódicos se redondea a 18 decimales con
`HALF_EVEN`; la valoración del ingreso conserva el total original cantidad × costo. Los valores
fuera de los límites decimales admitidos se rechazan.

No hay conversión automática de divisas. Si la factura está en otra moneda, primero hay que
convertir el costo y después declararlo en la moneda del negocio desde el editor. Cambiar el
código de moneda no calcula un tipo de cambio.

El botón de guardado exige que **todas** las líneas estén completas. La confirmación vuelve a
validarlas contra la base y exige que estén presentes todas las líneas originales: una fila con
cantidad desconocida no se omite para guardar las demás. Esta comprobación cubre las filas
extraídas al borrador; la persona debe comparar la revisión con la factura, porque el parser puede
no reconocer alguna fila de la imagen.

## Productos nuevos y líneas manuales

Los productos nuevos quedan preparados en la revisión, sin publicarse todavía en el catálogo.
El formulario admite nombre, código de barras y precio de venta opcional; este precio no sustituye
al costo de ingreso. Un código impreso elegible puede reservarse como SKU si está libre; no se
interpreta automáticamente como código de barras.

**+ Agregar producto** añade una línea manual con identidad estable, incluso si el OCR dejó el
borrador sin líneas. Estas líneas también necesitan cantidad, costo, moneda y unidad válidos.
Se añaden al conjunto original y no pueden sustituir ni ocultar una línea extraída pendiente de
revisión. La misma identidad se conserva al restaurar la pantalla y al reintentar.

El almacén debe estar activo y pertenecer al negocio. Se usa el asignado al producto; si no tiene
uno, el repositorio solo puede elegir automáticamente cuando hay un único almacén activo.
El formulario actual de producto nuevo prepara la primera unidad y el primer almacén activos del
catálogo; todavía no ofrece selectores para esas asignaciones. Cuando se necesita otra
configuración, se debe preparar el producto en el catálogo y vincularlo en la revisión.

## Confirmación y reintentos

`RoomInvoiceMatchingCommitRepository` realiza una sola transacción Room que crea los productos
nuevos y su outbox, añade saldos y movimientos de inventario de tipo `ADJUSTMENT`, guarda un recibo
en `invoice_inventory_receipts` y elimina el borrador y sus datos derivados. Un fallo, incluso al
cerrar el borrador después de escribir stock, revierte todo ese lote.

El recibo conserva el negocio, la identidad del borrador, una huella del contenido revisado y el
número de líneas aplicadas. Repetir exactamente la misma confirmación devuelve `AlreadyApplied`
sin sumar stock. Cambiar productos, cantidades u otras decisiones en un reintento de ese borrador
se rechaza. Si el proceso termina después del commit y antes de mostrar el éxito, al recrear la
pantalla se consulta primero el recibo y se recupera el resultado aunque el borrador ya no exista.
El recibo identifica ese borrador: volver a fotografiar la misma factura crea otra identidad y no
constituye una deduplicación comercial por número de comprobante.

También se comprueban movimientos de la implementación anterior con claves `invoice-match:v1`.
Si coinciden exactamente con las líneas revisadas, no vuelven a sumarse y se completa el resto del
lote. Si discrepan en producto, cantidad, costo, moneda o almacén, se conserva el borrador y se
exige reconciliar la entrada previa; no se intenta corregirla añadiendo más stock.

Este flujo ingresa existencias y costo, pero no genera una compra publicada ni un documento
tributario. El código conserva el rechazo para negocios enlazados a un negocio cloud, pero desde el
24 de septiembre de 2026 la única variante, `local`, no puede crear ese enlace, así que el ingreso
siempre se confirma en Room.

## Recuperación de la revisión y privacidad

La foto, el OCR y las líneas fuente permanecen en el borrador hasta que la transacción se confirma.
Las decisiones y los formularios pendientes usan un snapshot acotado de `SavedStateHandle`
(máximo 96 KiB), con referencias a productos y los datos mínimos de productos preparados. Antes
de restaurarlo se comprueban el negocio, la huella de las líneas fuente y el catálogo vigente.
Si el origen cambió, se avisa y se requiere revisar de nuevo; si se supera el límite, la última
edición no se aplica y se muestra un mensaje.

Ese estado permite la recreación de pantalla/proceso cuando Android restaura el estado de la
tarea; no equivale a una revisión íntegramente persistida en Room ni a un respaldo frente a borrar
la tarea o los datos de la app. Una ejecución OCR interrumpida conserva su token durable y su
recuperación usa CAS para no limpiar una ejecución distinta.

Después del commit se intentan guardar los alias del proveedor y borrar el árbol privado de la
captura. Son efectos posteriores: un fallo no deshace el inventario ya confirmado. La eliminación
física verifica que los archivos no sigan referenciados; los directorios huérfanos privados quedan
sujetos al mantenimiento de privacidad. Esta foto no se publica como documento de compra.

## Cobertura de regresión

- `ConfirmInvoiceMatchingUseCaseTest` y `MatchScannedInvoiceLinesUseCaseTest`: conservación de
  unidad/moneda/identidad, lote completo, datos desconocidos y conversión exacta del total.
- `InvoiceMatchingViewModelTest`: edición de datos, bloqueo de lotes incompletos, doble toque,
  restauración de decisiones/formularios y recuperación mediante recibo.
- `RoomInvoiceMatchingCommitRepositoryTest`: rollback tardío de productos/outbox/stock/recibo,
  reintento exacto o divergente, movimientos antiguos, cobertura de líneas originales y extras
  manuales con identidad estable.
