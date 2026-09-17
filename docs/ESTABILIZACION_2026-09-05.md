**Estabilización de FacturaStock — 5 de septiembre de 2026**

Se completaron las correcciones de la revisión del 4 de septiembre y su validación automatizada. Se conservaron los cambios locales anteriores del proyecto. La compilación final terminó correctamente con pruebas JVM, cobertura, Lint y 105 pruebas instrumentadas.

**Cambios implementados**

- La revisión de facturas conserva la unidad y moneda originales. Permite corregir cantidades, costos y unidad de compra/inventario; exige completar todas las líneas. Un costo desconocido ya no se convierte silenciosamente en cero. La conversión de cajas conserva el costo total exacto incluso cuando la división por unidad no termina.
- Productos, existencias, recibo de confirmación y cierre del borrador se guardan en una transacción Room. Los reintentos verifican el contenido; no repiten movimientos después de perder una confirmación. Los ingresos parciales del flujo anterior se comprueban y las divergencias requieren reconciliación explícita.
- La revisión recupera decisiones y formularios mediante un estado acotado a 96 KiB, revalidando factura, negocio y catálogo. Las líneas manuales tienen identidad estable. Volver desde la revisión deja disponible la acción de continuar; el OCR no ofrece navegación mientras guarda.
- Las ventas conservan una intención pendiente antes de contactar al servidor. Mientras su resultado sea incierto, mantienen productos, importes y términos de crédito. El reintento o la sincronización pueden terminar la operación original. Un catálogo archivado después del envío no impide aplicar una confirmación remota válida; el checkout local continúa exigiendo catálogo activo.
- Volver atrás desde una venta pendiente ya no abre un selector bloqueado. Una lectura secundaria fallida no oculta una venta confirmada. La interfaz muestra cuándo corresponde verificar una venta pendiente.
- El borrado de cuentas con Functions deja un trabajo privado recuperable, con exclusión temporal y fases separadas para limpieza y Auth. La respuesta distingue una solicitud pendiente de una eliminación completa. Spark oculta esa capacidad no disponible y pagina negocios más allá de los primeros 100.
- CI ejecuta las reglas Spark por separado. Se corrigió el formato exigido por Spotless y se actualizaron las descripciones de los recorridos y las garantías de persistencia.

**Base de datos y conservación**

Room pasa de v27 a v28. La migración crea vacías `invoice_inventory_receipts` y `pending_sale_checkouts`, sus índices y protecciones SQL. No reconstruye ni modifica registros históricos. Se conservaron los JSON de los esquemas 1–27 y se añadió el esquema 28.

Se guardaron el APK original y los archivos privados de la aplicación en un directorio local con acceso restringido:

`/Users/gustavo/FacturaStock-respaldos/2026-09-05_073456/`

La base original pasó `integrity_check` y `foreign_key_check`. Contenía 1 negocio, 121 productos, 4 ventas, 126 movimientos, 0 deudas y 0 borradores de factura. Estos valores son conteos de filas, no una auditoría contable del negocio.

Se ensayó la apertura de una copia con el APK final en un emulador dedicado. La comparación mediante representaciones canónicas y SHA-256 confirmó contenido idéntico en las 32 tablas comerciales originales; las dos tablas nuevas estaban vacías. Solo se excluyeron los metadatos de Room y el idioma de Android: el emulador usa `en_US` y la tablet `es_PE`. La copia migrada mantuvo integridad y claves foráneas válidas.

**Estado de la tablet:** actualización completada por USB sobre la instalación existente, mediante `adb install -r`, sin desinstalar ni borrar datos. Antes de instalar se guardó un respaldo fresco y se confirmó que sus tablas seguían idénticas a la copia original. Después se abrieron ventas, inventario y reportes en la tablet y se comprobó la base v28: las 32 tablas comerciales y `android_metadata` conservaron todo su contenido; las dos tablas nuevas estaban vacías. `integrity_check` y `foreign_key_check` aprobaron. El hash SHA-256 del APK instalado coincide con el artefacto validado. Un cotejo independiente confirmó también tipos SQLite, filas duplicadas y las 34 estructuras originales (columnas, claves e índices), sin diferencias. La aplicación quedó abierta en Inventario. Se conservaron 121 productos, 4 ventas y 126 movimientos. Las pruebas instrumentadas y la limpieza de datos de prueba se ejecutaron exclusivamente en `emulator-5556`; la copia temporal de ese emulador se eliminó al terminar. La instalación y la comprobación final usaron USB. Posteriormente se restableció ADB por Wi-Fi reiniciando el servidor de la Mac y autorizando la conexión en la tablet; se verificó respuesta en `192.168.18.27:5555` con el cable USB desconectado. En una prueba posterior, cinco comprobaciones ADB pasaron, incluida una tras 180 segundos en reposo sin enviar comandos a la tablet y otra después de reactivar la pantalla; no se ejecutó reconexión automática ni se cambiaron ajustes permanentes. Esta comprobación acotada no garantiza continuidad si cambia la red, se suspende la Mac o se reinician los equipos.

**Validación final**

| Comprobación | Resultado |
| --- | --- |
| JVM `localDebug` | 1.504 pruebas; 0 fallos, errores u omisiones. |
| JVM `cloudDebug` | 1.682 pruebas; 0 fallos, errores u omisiones. |
| Android, emulador API 35 | 105 pruebas; 0 fallos, errores u omisiones. |
| Migraciones completas | Orígenes v1–v27 hasta v28, más la prueba específica v27→v28. |
| Android Lint | 0 errores y 43 advertencias en cada variante. |
| Cobertura de dominio | 87,15 % de líneas y 65,47 % de ramas; supera el mínimo configurado del 80 % de líneas. |
| Formato y arquitectura | `ciStaticAnalysis` y `git diff --check` correctos. |
| Functions | 176 pruebas correctas, 0 fallos; 1 omisión correspondiente a Spark. |
| Spark separado | 14 pruebas correctas, incluida paginación de 101 negocios con nombres repetidos. |
| CI y contratos | Actionlint y contratos Ruby correctos. |

Los casos JVM compartidos se ejecutan en ambos flavors: estas cifras no representan casos todos distintos. La cobertura corresponde al dominio, no a toda la aplicación. Las primeras ejecuciones detectaron fallos de formato, un estado de navegación OCR y supuestos desactualizados de pruebas; la ejecución final incluye sus correcciones.

El intento del día anterior agotó memoria al solicitar cobertura de todas las variantes. La validación final limitó Gradle a dos trabajadores, compilación Kotlin dentro del proceso y cobertura `LocalDebug`, sin reducir los controles de CI. Las dependencias se utilizaron en modo offline. Functions se probó con Node 24; el runtime declarado de producción es Node 22.

Evidencia local ignorada por Git: [ejecución final](../build/reports/stabilization/2026-09-05/verification-final.log), [resumen](../build/reports/stabilization/2026-09-05/summary.json), [JUnit Android](../build/reports/stabilization/2026-09-05/instrumented-final.xml), [Functions](../build/reports/stabilization/2026-09-05/backend-functions.log) [Spark](../build/reports/stabilization/2026-09-05/backend-spark.log) y [comparación de los datos de la tablet](../build/reports/stabilization/2026-09-05/tablet-data-comparison.json); [cotejo independiente con tipos y estructura](../build/reports/stabilization/2026-09-05/tablet-typed-comparison.json).

**Límites y publicación**

La variante instalada en esta tablet es `localDebug` 1.0.4, igual que la instalación original, con firma compatible. Los cambios de Functions no se desplegaron: el backend nuevo debe publicarse antes de distribuir el cliente cloud que envía `responseVersion: 2`. Los bloqueos de borrado antiguos que nunca crearon un trabajo recuperable requieren una nueva solicitud autenticada.

La restauración completa y portable sigue sin estar implementada. El respaldo conservado para esta actualización no sustituye esa función ni exporta claves del Android Keystore. La revisión guardada mediante SavedState no es un respaldo permanente frente a borrar la tarea o los datos de Android. No se midió rendimiento con benchmarks físicos ni se registraron ventas o movimientos de prueba en la tablet.
