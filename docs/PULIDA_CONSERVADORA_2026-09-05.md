# Pulida conservadora — 5 de septiembre de 2026

Esta iteración corrige problemas concretos de entrada, presentación y lectura encontrados después de incorporar la edición de productos desde Inventario. El alcance está acotado: conservar los flujos que ya funcionan y hacer explícitos sus límites. La versión local corregida está compilada, probada e instalada en la tablet conservando los datos, con las evidencias detalladas al final.

## Hallazgos y correcciones

| Hallazgo | Corrección acotada |
| --- | --- |
| Al pegar `"5" + 24 espacios + "9"` como precio de venta, el editor conservaba los primeros 25 caracteres y el parser eliminaba los espacios. Una entrada inválida podía terminar aceptada como `5`. | El parser numérico compartido comprueba la longitud del texto recibido **antes** de `trim`. Inventario conserva un carácter adicional sobre el límite de 24 para que el exceso siga siendo inválido. No se añade otra estructura de SavedState. Se mantienen coma, punto y espacios exteriores dentro del límite. |
| Los campos de proveedor, unidad y ubicación podían seguir recibiendo cambios mientras se guardaba el formulario. | Deshabilitar esos campos durante `isSaving`, junto con sus acciones de confirmación/cancelación existentes. La operación mantiene los valores que se enviaron a guardar. |
| Un precio de venta `0`, con demasiados decimales o superior al límite monetario deshabilitaba Guardar sin explicar claramente el motivo junto al campo. | Añadir error y ayuda en el campo de venta de Inventario utilizando la misma conversión `Money.fromMajor` y `ProductSalePricePolicy` que gobiernan la validación. El precio vacío conserva su significado opcional; cero continúa siendo inválido. |
| La consulta del detalle utilizaba `UnitCost` con precisión 38/18 para un promedio que la persistencia de inventario admite con precisión 128/36. Un promedio derivado válido podía impedir abrir el detalle. | Utilizar `InventoryCostAmount` para la lectura de posiciones y promedios. Se conserva el decimal almacenado y su moneda. Los costos **nuevos** ingresados mantienen el límite 38/18; esta corrección de consulta no amplía la política de captura. |
| Abrir el editor consultaba el historial de movimientos incluso cuando los costos almacenados eran distintos de cero. | Omitir esa consulta al cargar el editor cuando ningún saldo tiene costo cero. Si existe un cero, se conserva la consulta necesaria para distinguir un costo cero conocido de un costo desconocido. La validación y la trazabilidad del guardado permanecen vigentes. |
| El mensaje de conflicto pedía revisar la versión actual sin explicar cómo salir del borrador que conserva el CAS original. | Mostrar una instrucción concreta para cancelar y volver a abrir el producto. No renovar automáticamente las versiones esperadas ni aplicar el borrador sobre saldos que cambiaron. |

Además se corrigió un fallo de consulta con existencias negativas: el detalle conserva las cantidades y los costos individuales, muestra como no disponible el promedio de esa moneda y permite consultar las otras. No altera saldos ni fórmulas de guardado.

## Comportamiento protegido

- No se cambian la detección del lector, su foco, los eventos del hardware, los sufijos de teclado ni la asociación por código de barras. La validación numérica compartida afecta únicamente a entradas numéricas que exceden su límite.
- Se conservan identidad del producto, separación por negocio, moneda, unidades y versiones esperadas. Cantidad sigue significando **total actual** de la ubicación seleccionada; costo de compra corresponde al **costo unitario promedio actual almacenado**, no al precio de una factura histórica.
- Abrir o cancelar no modifica datos. Se mantiene el guardado atómico de metadatos y saldos editados, el control de concurrencia por versión y la ausencia de movimientos en un guardado sin cambios. Los saldos que el usuario no editó no se reenvían como objetivos antiguos.
- Esta pulida no incorpora dependencias, migraciones de esquema, despliegues de servicios ni cambios en las políticas de guardado, sincronización o conservación de datos.

## Regresiones y validación realizada

Las pruebas de precio cubren el pegado de 26 caracteres con espacios interiores, exceso de dígitos, un importe superior al máximo permitido, recuperación del borrador y un precio válido como ` 6,50 `. También comprueban la validación compartida de formularios manuales y de registro. La cobertura de lectura contempla promedios almacenados con precisión extendida; la de UI contempla campos bloqueados durante el guardado y explicación del precio inválido.

| Comprobación de esta iteración | Estado |
| --- | --- |
| Compilación local e integración | APK localDebug y APK de pruebas compiladas |
| Pruebas unitarias | 1.537 aprobadas; cero fallos, errores u omisiones |
| Pruebas instrumentadas de Room, UI y escáner | 32 de la pulida y el editor + 60 de regresión aprobadas, en emulador aislado API 35 |
| Lint y comprobaciones estáticas finales | ciStaticAnalysis aprobado; Android Lint sin errores y con 44 advertencias |
| Instalación y verificación en tablet | Actualizada por Wi-Fi; APK instalada coincide en SHA-256 con la APK probada |
| Accionamiento físico del lector | No realizado por esta tarea |

Las pruebas automatizadas pueden enviar acciones del ViewModel, eventos de teclado o interacciones de UI y verificar su resultado. **Eso no equivale a accionar físicamente el lector** ni certifica su óptica, gatillo, conexión o entrega real de datos. La tarea principal no activa el escáner físico; cualquier comprobación manual del lector debe consignarse por separado, con su alcance real.

La comparación con la copia de código creada al iniciar esta tarea verificó 17 archivos centrales del escáner idénticos, 35 archivos de configuración, cálculo y esquemas idénticos, y las implementaciones de escritura de existencias sin cambios. La mejora de carga tiene una prueba con QueryCallback que comprueba cero consultas al historial cuando no hay costos cero, manteniendo la consulta cuando es necesaria.

Gradle se ejecutó con JBR 21, --offline, --max-workers=2 y compilación Kotlin en proceso: spotlessApply, ciStaticAnalysis, :app:testLocalDebugUnitTest, :app:assembleLocalDebug, :app:assembleLocalDebugAndroidTest y :app:lintLocalDebug. El baseline local de Spotless fue HEAD porque el repositorio tiene un solo commit. No se actualizaron dependencias ni se desplegaron servicios cloud. Las 44 advertencias Lint incluyen dos de recursos de esta pulida (texto anterior de conflicto sin uso y sugerencia de plural para la ayuda de decimales); no son fallos funcionales.

La respuesta de adb install -r excedió el tiempo de espera aunque la actualización ya había ocurrido. No se repitió la instalación: se verificó la huella SHA-256 del APK instalado, se inició de nuevo la Activity y se abrió el editor. Cantidad, compra y venta aparecieron habilitadas y con valores idénticos al respaldo; se canceló sin escribir cambios de prueba.

Evidencias: build/reports/pulida-conservadora/2026-09-05/summary.json, preserved-behavior.json, validation.log, instrumented-polish.log, instrumented-scanner-regression.log y tablet-data-comparison.json. El diff de esta iteración frente al estado inicial está en this-turn.diff. El emulador temporal fue eliminado al concluir.


La comparación de datos entre 2026-09-05T10:55:47-05:00 y 2026-09-05T11:02:25-05:00 confirmó 36 tablas idénticas y configuración idéntica, con integridad SQLite correcta y cero infracciones de claves foráneas. Se conservan 121 productos, 121 saldos, 4 ventas, 5 líneas y 126 movimientos. No se modificó ningún producto real durante la comprobación.

Respaldo local fechado: `/Users/gustavo/Desktop/Backup/FacturaStock_2026-09-05_10-44-28_antes_pulida`, con aplicación original/actualizada, datos privados completos, SQLite consolidada y sumas SHA-256.
