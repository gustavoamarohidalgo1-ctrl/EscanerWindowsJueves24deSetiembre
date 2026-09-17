# Productos especiales — 1.0.8

Documento de uso, alcance y verificación de la función, actualizado el 12 de septiembre de 2026.

La opción **Producto especial** permite registrar productos que se venden por kilo y no tienen código de barras, como comida preparada o productos a granel. Se usa el inventario y el proceso de ventas existentes. No se crea una categoría persistente ni una lista exclusiva de productos especiales.

## Registrar el producto

1. Abrir **Inventario → Producto especial**.
2. En **Producto por kilo sin código**, ingresar el nombre del producto.
3. Ingresar los **kilos iniciales (kg)**: deben ser mayores que cero. Se admiten cantidades decimales, por ejemplo `2,5` o `2.5`.
4. Ingresar el **costo por kilo**: puede ser cero y no admite valores negativos.
5. Ingresar el **precio de venta por kilo**: debe ser mayor que cero y respetar la precisión monetaria de la moneda configurada.
6. Seleccionar un almacén activo existente y guardar.

El formulario no solicita código de barras ni activa un lector. El producto se guarda sin barcode, con unidad kilogramo y con las existencias iniciales en el almacén seleccionado. Producto y entrada de existencias se registran mediante la operación atómica de alta: un fallo en esa operación no debe dejar un producto parcialmente registrado con respecto a su stock.

Se reutiliza una unidad activa `KGM` o `KG`. Si falta, la unidad se crea al guardar, después de validar los campos y el almacén. No se crea al abrir o cancelar el formulario. Si ambos códigos existen archivados, el alta se rechaza; no se restauran automáticamente. La creación de la unidad pertenece al catálogo: si la posterior operación de alta falla, esa unidad puede permanecer disponible, aunque el alta de producto y existencias no se haya confirmado.

El borrador conserva los campos y un identificador de registro al recrear la pantalla. Ese identificador evita volver a ingresar los kilos si el registro ya se había confirmado. Cancelar cierra el alta; cambiar de negocio descarta el borrador del negocio anterior. Los productos guardados continúan editándose desde Inventario mediante el editor existente, conservando su unidad.

## Vender al contado o a crédito

1. Abrir una venta **al contado** o **a crédito** y buscar el producto por su nombre en el campo habitual.
2. Seleccionar el producto por kilo sin código. El editor abre inicialmente la opción de **importe**.
3. Ingresar el dinero que se desea cobrar. La aplicación calcula los kilos utilizando el precio por kilo del producto y muestra la cantidad y el total.
4. Como alternativa, seleccionar la opción de **kilos** e ingresar la cantidad. La aplicación calcula el importe correspondiente.
5. Confirmar la línea y completar la venta con el flujo habitual. En una venta a crédito se mantienen los datos y controles habituales del deudor.

Por ejemplo, con un precio de **S/ 8,00 por kilo**, cobrar **S/ 7,00** corresponde a:

`7 ÷ 8 = 0,875 kg`

La línea representa **0,875 kg**, y esa es la cantidad que se descuenta del almacén al registrar la venta. También se puede ingresar directamente `0,875` kg y obtener un total de S/ 7,00. Añadir o editar una línea en el carrito no constituye por sí solo la confirmación de la venta.

Los importes se calculan con la precisión y el redondeo monetario del motor de ventas. La conversión desde un importe solo se acepta si el cálculo de la línea conserva el importe solicitado. No se admite una cantidad que produzca un total monetario nulo. La división por el precio conserva hasta 18 decimales de kilos; la pantalla muestra hasta seis e indica `≈` si la presentación es aproximada. Guardar usa la cantidad completa calculada, no la presentación redondeada.

## Edición y control de existencias

Al editar una línea, **mantener el mismo importe conserva los kilos originales** cuando siguen produciendo ese importe con el precio de la línea. Esto evita que dividir nuevamente un importe ya redondeado cambie una cantidad que el usuario había elegido expresamente. La conservación también se aplica al cambiar entre kilos e importe sin modificar el importe equivalente. Si se introduce un importe distinto, se calcula la cantidad correspondiente.

El editor comprueba que los kilos no superen las existencias disponibles. Antes de aceptar la línea vuelve a validar el producto, el almacén, el precio, el negocio y el estado del carrito. La venta conserva sus controles habituales de stock y persistencia. Un cambio que invalide esos datos requiere revisar la línea; no debe confirmarse con una selección desactualizada.

## Compatibilidad y verificación

La función utiliza productos, unidades, líneas de venta y movimientos de inventario existentes. **No introduce una migración de base de datos: el esquema permanece en la versión 29.** El alta especial se distingue por su origen en la interfaz; los productos por kilo sin código usan `KGM` o `KG` y barcode nulo. El registro por escáner y los productos convencionales conservan sus flujos.

Pruebas ejecutadas:

- **3.542 pruebas JVM sin fallos:** 1.682 de la variante local y 1.860 de la variante cloud. Incluyen conversión monetaria exacta, comas decimales, importes inválidos, sobreventa, cambios de precio, contexto de negocio, recuperación del carrito y conservación de kilos al editar.
- **82 pruebas Android sin fallos**, en un emulador aislado. Incluyen alta por kilos desde Inventario y recreación de la pantalla, editor por importe y por kilos, ventas completas al contado y a crédito, cancelación sin cambios, edición de la misma línea y regresiones del escáner y formularios convencionales.
- En los dos recorridos de venta completa se registraron **S/ 7,00**, se descontaron **0,875 kg** y quedaron **9,125 kg** de 10 iniciales. La venta a crédito también registró la deuda de S/ 7,00.
- Compilación del APK y análisis estático local/cloud completados, sin errores de lint; se mantienen 71 advertencias en cada variante. `ciStaticAnalysis` pasó.

La comprobación en la tablet detectó y corrigió una etiqueta: el selector de los productos especiales dice **Almacén de existencias**, ya que el registro necesita un almacén. Los 3.542 y 82 casos anteriores corresponden al comportamiento previo a esa corrección exclusivamente de texto; la versión final se recompila, analiza y comprueba directamente en la tablet. Las evidencias de cada ejecución se conservan en `build/reports/weight-special-products-2026-09-12/`.

## Instalación y conservación de datos

El APK final **1.0.8, código 9** se instaló en la tablet **Huawei AGS6_W09**. Se volvió a ejecutar compilación, lint local/cloud y `ciStaticAnalysis` después del ajuste de etiqueta: todos completaron correctamente. Se comprobaron en la tablet el botón **Producto especial**, los cuatro campos de entrada, la unidad kilogramo, el selector **Almacén de existencias** y el cierre del formulario. No se guardaron productos o ventas de prueba en el dispositivo real; los recorridos de venta con descuentos de stock se verificaron en el emulador aislado.

El APK instalado coincide por SHA-256 con el archivo final:

`8fed815d7d1693cabb481267f9e01c6daed8e101673d75dc80ae0cd486f91398`

El respaldo de antes y después está en `/Users/gustavo/Desktop/Backup8/Actualizacion_1.0.8/`. Ambos mantienen esquema **29**, **37 tablas y 2.872 filas** incluidas las tablas internas. La comparación canónica confirma las **35 tablas de datos de negocio idénticas**, integridad SQLite correcta y cero violaciones de claves foráneas. El dispositivo quedó en Inventario, con el formulario de prueba cerrado.

Archivos de evidencia: `unit-tests-summary.json`, `android-tests-summary.json`, `checks-summary.json`, `tablet-ui-verification.json`, `tablet-update-comparison.json` e `installed-apk-verification.json` dentro del respaldo. El historial de compilación conserva también el fallo inicial de contrato de navegación y el de construcción de identificadores en las pruebas, ambos corregidos antes de las ejecuciones satisfactorias.
