# Entrada unificada de ventas — 1.0.7

## Cambio solicitado

Contado y crédito entran directamente a venta, sin elegir Escáner o Manual. Un solo campo nativo permite escribir el nombre o recibir el código del lector. Las sugerencias aparecen desde dos letras; la persona selecciona el producto. El botón Agregar por código mantiene la introducción explícita de códigos.

## Alcance

Se conserva el motor HID, el enrutador, el adaptador Android, la cola de lecturas, la deduplicación, los ceros iniciales, los terminadores y las reglas de carrito, precios, inventario, cobro y crédito. La búsqueda de facturas conserva su prefijo de tres letras por defecto; ventas solicita dos. No hay migración de base de datos.

El campo nativo añade búsqueda opcional: escribir publica una consulta, la vista previa HID no publica consultas y la acción Buscar del teclado no agrega por código. El estado del escáner sigue pausándose al editar deudor, cantidad o precio. La entrada permanece tocable para recuperar el foco, y las conexiones IME antiguas siguen invalidadas. Una selección guardada o una lectura aceptada limpia la consulta para la siguiente entrada.

Se conserva el estado de la venta al recrear la actividad y al retroceder. La selección antigua de modo se adapta a la entrada unificada. Las comprobaciones de salida con ediciones pendientes siguen vigentes.

## Validación

- Compilación local y APK de pruebas aprobados; versión 1.0.7, código 8.
- 1.657 pruebas unitarias local y 1.835 nube aprobadas (3.492 en total).
- 80 casos Android aprobados: 78 en la ejecución inicial; dos se repitieron tras corregir únicamente el test (esperar el nuevo carrito antes del snapshot de historial y usar el texto correcto del botón de crédito). La repetición pasó ambos casos. Incluye campo nativo, foco, permisos, composición IME, prefijos, sugerencias, navegación/recreación y ventas con SQLite real.
- Lint local/nube aprobados. El control de formato pidió cambios de estilo en tres archivos de esta tarea; tras aplicarlos, compilación y `ciStaticAnalysis` aprobaron. Sin cambios funcionales posteriores a las pruebas unitarias.
- La tablet Huawei ejecuta 1.0.7 (código 8). El SHA-256 del APK instalado coincide con el respaldado.
- Verificado directamente en la tablet: `Ar` muestra productos en contado y crédito; un único campo de producto, sin selector de modalidad. La prueba de interfaz no agregó productos ni confirmó ventas reales.
- Respaldo completo antes y después en `/Users/gustavo/Desktop/Backup8/Actualizacion_1.0.7/`. Esquema 29, integridad correcta, cero errores de relaciones y las 35 tablas de datos idénticas. Ambas capturas contienen 2.872 filas incluidas las tablas internas.

Evidencia: `build/reports/unified-sales-input-2026-09-12/`. Los ensayos HID/IME se ejecutaron en un emulador aislado; en la tablet se comprobó la búsqueda y la interfaz sin realizar ventas de prueba.
