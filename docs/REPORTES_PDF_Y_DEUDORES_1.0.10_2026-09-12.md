# Reportes PDF y acceso a deudores — 1.0.10

Fecha: 12 de septiembre de 2026.

En la pantalla inicial de **Vender**, debajo de **Contado** y **A crédito**, **Ver deudores** abre el listado existente. También está disponible en **Reportes**, dentro de **Deudores y reportes PDF**. Volver conserva la venta en curso. La salida desde Vender mantiene la protección de las ediciones pendientes: si no se pueden guardar, el diálogo permite **Cancelar** para conservarlas o confirmar su descarte con **Descartar y salir**.

Para exportar, abre **Reportes** y elige:

- **Guardar PDF de hoy**: incluye todas las ventas confirmadas vigentes del día, al contado y a crédito, con sus productos, cantidades e importes. No aplica el límite de ventas visibles en pantalla y excluye las anuladas. Añade los deudores pendientes de todas las fechas.
- **Guardar PDF de deudores**: incluye únicamente las cuentas pendientes de todas las fechas, sin incorporar el historial de ventas del día.

“Hoy” corresponde al día de la zona horaria configurada para el negocio: desde su inicio, incluido, hasta el inicio del día siguiente, excluido. El día y los datos se fijan al solicitar el PDF; consultar semana o mes en Reportes no cambia este alcance. El documento indica la zona y el momento de generación.

Las ventas separan total vendido, contado y crédito por moneda. Una venta a crédito no representa dinero ya cobrado, aunque una cuenta posteriormente pagada conserva su clasificación como crédito. Los deudores muestran cada venta pendiente por separado, con importe original, abonado y saldo; **abonado = original − saldo**. Los saldos se totalizan por moneda y no se vuelven a sumar al total vendido del día. Se excluyen cuentas ya pagadas y ventas anuladas.

El selector de archivos de Android (SAF) permite elegir el nombre y la ubicación. Después de guardar aparece **PDF guardado en la ubicación elegida.** y el botón **Abrir PDF**. Si no hay un visor disponible, el archivo sigue guardado y puede buscarse desde Archivos. Si la app advierte que quedaron datos parciales, elimina ese archivo antes de intentarlo otra vez.

La exportación consulta una instantánea y no altera ventas, deudas, abonos ni inventario. Esta actualización conserva el **esquema de base de datos 29**, sin migración nueva.

## Validación e instalación

La validación final de **1.0.10, código 11**, obtuvo estos resultados:

| Comprobación | Resultado final |
| --- | --- |
| Pruebas JVM, variante localDebug | 1729 aprobadas; 0 fallos, errores u omitidas |
| Pruebas JVM, variante cloudDebug | 1907 aprobadas; 0 fallos, errores u omitidas |
| Pruebas Android en emulador | 71 aprobadas; 0 fallos o errores |
| Lint localDebug y cloudDebug | 0 errores y 74 advertencias en cada variante |

Se revisaron las **9 páginas de 3 PDF sintéticos** generados con el renderizador nativo: ventas con deudores, sólo deudores y reporte vacío. Sus PNG coinciden exactamente con los generados por el APK final. La comprobación del contenido incluyó todas las referencias y líneas esperadas, monedas PEN/USD y continuidad de filas y totales. Evidencia: [resumen final](/Users/gustavo/Desktop/ProyectoMayda/build/reports/reportes-pdf-1.0.10-2026-09-12/validation-summary.json), [71 pruebas Android](/Users/gustavo/Desktop/ProyectoMayda/build/reports/reportes-pdf-1.0.10-2026-09-12/android-71-tests.log), [compilación y lint finales](/Users/gustavo/Desktop/ProyectoMayda/build/reports/reportes-pdf-1.0.10-2026-09-12/build-and-lint.log) y [verificación del texto PDF](/Users/gustavo/Desktop/ProyectoMayda/build/reports/reportes-pdf-1.0.10-2026-09-12/pdf-qa/text-verification.json).

El APK instalado tiene SHA-256 `2c1143b92f0fbdb1a504a185b512cb46b00b45aa56c412304916c71dc26d3f0b`. Se actualizó la tablet física mediante instalación con `-r`, conservando los datos. Antes de instalar se verificaron **37 tablas y 2872 registros**, esquema **29**, integridad **ok** y **0 errores de claves foráneas**. Inmediatamente después de instalar, **antes de abrir la app**, todas las bases de datos y archivos persistentes coincidían exactamente con el respaldo previo. El [respaldo anterior a 1.0.10](/Users/gustavo/Desktop/BackupsFacturaStock/2026-09-12_12-25-05_antes_de_1.0.10) conserva el APK anterior y los datos verificados.

En la tablet física se comprobó **Ver deudores → volver a Vender** y **Guardar PDF de hoy** mediante SAF. El archivo quedó en `/sdcard/Download/ventas-del-dia-2026-09-12.pdf`; sus **2 páginas** se revisaron en el equipo y resultaron completas y legibles. Se conserva una [copia del PDF generado en la tablet](/Users/gustavo/Desktop/BackupsFacturaStock/2026-09-12_12-25-05_antes_de_1.0.10/pdf-tablet/ventas-del-dia-2026-09-12.pdf).

**Límite de la comprobación física:** no se confirmó **Abrir PDF** ni la exportación separada **Guardar PDF de deudores** en la tablet. La pantalla cambió y el equipo se apagó, según confirmó el usuario, por lo que se detuvo esa comprobación. El PDF de sólo deudores sí quedó validado en el emulador.
