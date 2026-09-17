# Optimización adicional — 5 de septiembre de 2026

La actualización local está instalada en la tablet. Esta iteración evita repetir conversiones y agregaciones del inventario cuando Room entrega exactamente las mismas filas y aclara el error al dejar vacío un costo de compra que antes era conocido.

## Cambios y alcance

- `RoomInventoryReadRepository.observeInventory` compara las filas antes de ejecutar el mapeo. Las filas incluyen cantidades, costos, estado y versiones; los cambios reales siguen propagándose. La consulta SQL continúa ejecutándose. No se midió un porcentaje de mejora ni se cambió `observeProductRevision`.
- El editor explica que debe introducirse el costo de compra conocido y que cero es válido. Un costo originalmente desconocido conserva su ayuda y comportamiento. Las reglas de validación y guardado no cambiaron.
- La ayuda de decimales del precio de venta tiene una redacción que elimina una advertencia de pluralización.

Frente a la copia de código al inicio de esta iteración, cambiaron tres archivos de producción y tres de pruebas. Los 17 archivos protegidos del escáner, los esquemas, los repositorios de escritura, las dependencias y la configuración permanecen idénticos. No se alteró el tratamiento de cantidades, precios, movimientos, ventas o borradores.

## Validación

| Comprobación | Resultado |
| --- | --- |
| Pruebas unitarias locales | 1.537 aprobadas, sin fallos, errores ni omisiones |
| Pruebas instrumentadas en emulador aislado API 35 | 33 aprobadas: lectura de inventario, editor, ciclo de productos, navegación, ventas con escáner y confirmación de compra |
| Comprobaciones estáticas y compilación | `ciStaticAnalysis`, APK local y APK de pruebas aprobadas |
| Android Lint | Cero errores; 43 advertencias, una menos que antes |
| Lectura continua de inventario | Una misma suscripción refleja alta, cambio de costo, cambio de cantidad y archivado |
| Ayuda del costo | Costo conocido vacío muestra error; cero se admite; costo originalmente desconocido conserva su comportamiento |

Una prueba de recreación falló inicialmente por exigir que el nombre fuese visible pese a que el desplazamiento del editor se había restaurado hasta SKU. Se corrigió la prueba para esperar el formulario y desplazar cada campo antes de verificarlo. Conserva las comprobaciones del nombre, código y SKU pendientes, cancelación, producto original, existencias e historial. El registro confirma que el nombre estaba fuera de vista antes de desplazarlo; la ejecución final de las 33 pruebas pasó. No se modificó la lógica de recuperación para resolver ese fallo de la prueba.

Se utilizó JBR 21, Gradle sin red, dos trabajadores y Kotlin en proceso. Las pruebas físicas del gatillo o conexión del lector no se realizaron; las regresiones del escáner fueron automatizadas. El emulador temporal se retiró al concluir.

## Instalación y conservación de datos

Instalación por Wi-Fi el **05/09/2026 a las 11:52:52, hora de Lima**, con `adb install --no-streaming -r`, conservando el paquete y su firma. La huella SHA-256 de la aplicación instalada coincide con la APK probada:

`2e3c0eab7b78a781f372c2aa5cfb6f1ef29fac99dc66d0a90aded25f1c8cda55`

En la tablet se abrió el editor de Ace 700 G: cantidad `12`, compra `7.5` y venta `8.50`, todos habilitados. Se canceló sin guardar cambios de prueba.

La comparación entre las **11:51:52** y las **11:54:51** confirmó las **36 tablas y la configuración idénticas**, esquema 28, integridad SQLite correcta y cero infracciones de claves foráneas. Se conservaron 121 productos, 121 saldos, 4 ventas —3 confirmadas y 1 borrador—, 6 líneas y 126 movimientos.

Respaldo local con aplicación anterior y actualizada, datos privados, SQLite consolidada, verificaciones y sumas SHA-256:

`/Users/gustavo/Desktop/Backup/FacturaStock_2026-09-05_11-39-06_optimizacion_adicional`

Las evidencias, comparación contra la copia inicial y registros de las pruebas están en `build/reports/optimizacion-adicional/2026-09-05/`. Los registros de las ejecuciones iniciales fallidas se conservan junto a `instrumented-final.log`, que contiene el resultado final aprobado.
