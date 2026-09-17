# Eliminar una deuda anotada por error — 1.0.11

Fecha: 12 de septiembre de 2026.

Desde **Ver deudores**, abre la deuda correspondiente y pulsa **Eliminar deuda**. Antes de confirmar, revisa el nombre, los importes y los productos que volverán al inventario.

Cada deuda del proyecto nace de una venta a crédito. Corregir una deuda ingresada por error utiliza la anulación de esa venta: retira su saldo de Deudores y de los dos PDFs, excluye la venta anulada de los reportes y repone sus productos una sola vez. Conserva la venta original, la deuda y sus abonos como historial, junto con el registro de la anulación.

La confirmación explica estas consecuencias. Si existen abonos, muestra el importe que corresponde devolver; la aplicación no realiza transferencias ni devoluciones de efectivo. Si la venta fue correcta y el cliente ya pagó, utiliza **Registrar pago**.

**Cancelar** cierra la confirmación sin modificar datos. Si aparece un abono o cambia el inventario después de revisar el detalle, es necesario revisar la información actual y confirmar de nuevo. Las autorizaciones y restricciones para anular ventas siguen siendo las existentes, incluidos los negocios compartidos y el historial inconsistente.

Esta versión conserva el esquema Room 29 y las opciones **Guardar PDF de hoy**, **Guardar PDF de deudores** y **Ver deudores** de la versión 1.0.10.

## Entrega y validación

APK preparado: [FacturaStock-1.0.11.apk](/Users/gustavo/Desktop/ProyectoMayda/build/reports/eliminar-deudores-1.0.11-2026-09-12/FacturaStock-1.0.11.apk), variante localDebug, código 12. Conserva la firma de la versión anterior.

| Comprobación | Resultado |
| --- | --- |
| Formato y análisis estático | Aprobados. |
| Unitarias localDebug | 1.745 aprobadas; sin fallos, errores ni omisiones. |
| Unitarias cloudDebug | 1.923 aprobadas; sin fallos, errores ni omisiones. Comparten casos con local; no sumar como pruebas únicas. |
| Cobertura Kover localDebug | Compuerta aprobada; 87,33% de líneas y 66,00% de ramas en el ámbito configurado. |
| Compilación localDebug y AndroidTest | Aprobada. |
| Lint localDebug y cloudDebug | 0 errores y 75 advertencias por variante. |
| Android en emulador aislado API 35 | 54 pruebas aprobadas: pantalla de eliminación, recorridos con Activity real, anulación/abonos/inventario, restricciones SQL, datos de ambos PDF, reportes y navegación. |

Las pruebas nuevas incluyen cancelación sin cambios en ninguna tabla, crédito con abono parcial, devolución única de inventario, persistencia del historial y regreso correcto después de recrear la Activity. Verifican también doble toque, cambios de pago/versión/negocio, errores recuperables y nueva revisión obligatoria antes de confirmar información que cambió. La pantalla fue comprobada con el tamaño de texto duplicado.

La comparación utiliza el archivo `source-before-sha256.json` de esta entrega, pues el proyecto ya contenía cambios sin commit. Los archivos capturados de persistencia, esquemas, repositorios y sincronización conservan sus hashes. No se ejecutaron migraciones ni se modificaron datos reales durante este añadido. La nueva advertencia de Lint corresponde a una cadena de texto sin uso; no afecta la operación.

Se repitieron los dos recorridos reales para revisar visualmente la [confirmación](/Users/gustavo/Desktop/ProyectoMayda/build/reports/eliminar-deudores-1.0.11-2026-09-12/ui/confirmacion.png) y la [lista después de corregir la deuda](/Users/gustavo/Desktop/ProyectoMayda/build/reports/eliminar-deudores-1.0.11-2026-09-12/ui/lista-despues.png). Ambos aprobaron; son repeticiones de la batería de 54. Las capturas contienen únicamente datos sintéticos: el fixture habilita capturas sólo en el emulador y la protección de pantallas de la aplicación permanece intacta.

La entrega inicial se completó dentro del proyecto porque la tablet estaba apagada. Posteriormente, el usuario volvió a conectarla y autorizó instalar la versión 1.0.11 por Wi-Fi; la actualización física se completó como se detalla a continuación.

Evidencias y APK: `build/reports/eliminar-deudores-1.0.11-2026-09-12/`. La documentación de los botones de PDF permanece en [REPORTES_PDF_Y_DEUDORES_1.0.10_2026-09-12.md](/Users/gustavo/Desktop/ProyectoMayda/docs/REPORTES_PDF_Y_DEUDORES_1.0.10_2026-09-12.md).

## Instalación física autorizada posteriormente

Instalada por Wi-Fi en la Huawei AGS6_W09 el 12 de septiembre de 2026. Android confirma versión 1.0.11, código 12. La lectura del APK instalado en la tablet arroja SHA-256 `f455571d66e07286c166f27c77af9d344b215feb667df749b60acb0311a6dda3`, idéntico al artefacto validado. Se mantuvieron el paquete, el usuario Android y la firma anterior; no se desinstaló la app ni se borraron sus datos.

Respaldo privado: `/Users/gustavo/Desktop/BackupsFacturaStock/2026-09-12_13-27-00_antes_de_1.0.11`. Contiene el APK 1.0.10 leído de la tablet, el APK nuevo y copias de los archivos privados antes y después de instalar. La base principal conserva exactamente el esquema 29, las 37 tablas y las 2.872 filas, incluidos los hashes de sus registros. Integridad `ok` y cero errores de claves foráneas.

La app ya había iniciado un proceso antes de la comprobación explícita de pantalla. Por eso la comparación de archivos registró tres diferencias técnicas: la caché `profileInstalled` y los archivos WAL/SHM de WorkManager. La revisión lógica del scheduler confirmó mantenimiento de privacidad completado y reprogramación de trabajos de arranque, sin cambios en los registros de negocio. Los demás archivos persistentes y preferencias coinciden con el respaldo anterior.

Se comprobó la pantalla de ventas, la lista de deudores y el botón **Eliminar deuda** visible y habilitado en un detalle real, sin pulsarlo ni anular registros. La app quedó abierta en **Deudores**. No hubo excepciones fatales en el log del proceso comprobado. La compilación de Android mediante `cmd package compile -m speed -f` finalizó con `Success`.
