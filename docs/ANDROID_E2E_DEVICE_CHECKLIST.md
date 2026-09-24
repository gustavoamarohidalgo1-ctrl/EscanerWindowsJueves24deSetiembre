# Checklist Android E2E en teléfono físico

Este checklist complementa las pruebas Compose y Room. No sustituye la automatización: valida
únicamente las fronteras que dependen de hardware real (cámara, rotación, suspensión del proceso y
conectividad del dispositivo).

## Política de evidencia

- Usar exclusivamente la factura sintética de demostración de 38 líneas incluida en la app.
- No fotografiar comprobantes, RUC, nombres, correos ni inventario de personas o empresas reales.
- No guardar el número de serie, Android ID, IMEI, cuenta Google ni ubicación del teléfono.
- Las capturas deben mostrar solo FacturaStock. Cerrar notificaciones y otras aplicaciones antes de
  capturar.
- Guardar la evidencia bajo `evidence/android/<fecha>-<modelo-sanitizado>/`; este directorio no se
  sube automáticamente a ningún servicio.

## Preparación

1. Conectar por USB un teléfono Android físico de pruebas con cámara posterior y depuración USB
   autorizada. Nunca usar la tablet del negocio: contiene datos reales.
2. Instalar `localDebug` (`local` es el único flavor); no usar datos ni cuentas reales.
3. Ejecutar:

   ```bash
   ./scripts/real-device-evidence.sh start evidence/android/AAAA-MM-DD-modelo
   ```

4. Confirmar que el comando rechaza emuladores y registra únicamente fabricante, modelo, versión
   Android, nivel SDK, versión de la app y presencia de cámara.

## Recorrido manual

Marcar cada punto como `PASS` o `FAIL` en `checklist.tsv`, creado por el script. Completar también
`verified_by` con un alias del probador y `verified_at` con fecha y hora ISO 8601; no usar correos ni
otros datos personales.

1. **Onboarding:** completar negocio, almacén y unidad con nombres sintéticos. Cerrar y abrir la app;
   Inicio debe conservar la configuración.
2. **Cámara real:** iniciar una compra, abrir la cámara, enfocar una hoja con contenido sintético,
   alternar flash si el equipo lo soporta y capturar. La vista previa debe mostrar la página correcta.
3. **Rotación:** girar el teléfono en Vista previa, Cabecera y Líneas. El destino, los campos editados y
   el número de páginas deben conservarse.
4. **Muerte del proceso:** editar un campo de cabecera y una línea, enviar FacturaStock al fondo,
   ejecutar `adb shell am force-stop com.facturastock.app`, abrir de nuevo y reanudar el borrador. Las
   revisiones guardadas deben reaparecer.
5. **Imagen borrosa/OCR fallido:** capturar una hoja deliberadamente desenfocada. Debe aparecer la
   advertencia de calidad y debe ser posible recapturar; un fallo OCR debe ofrecer reintento sin perder
   el borrador.
6. **Factura demo de 38 líneas:** activar el modo demo e iniciar su escenario. Confirmar
   38 líneas, resolver el producto ambiguo, crear el producto faltante y aceptar explícitamente el
   ajuste de redondeo de S/ 0.03. Desde el 24 de septiembre de 2026 Ajustes ya no ofrece el modo
   demo; este punto queda pendiente hasta que exista otro acceso al escenario.
7. **Doble toque:** tocar dos veces rápidamente `Registrar` y luego confirmar. Debe existir una sola
   compra, un solo conjunto de movimientos PURCHASE y un solo incremento de stock.
8. **Historial e inventario:** abrir el detalle desde Éxito, volver a Historial y luego Inventario.
   Verificar 38 líneas y que el saldo coincida con la suma de movimientos mostrada.
9. **Modo avión:** con una operación de respaldo pendiente, activar modo avión. La compra local debe
   seguir disponible y la cola debe permanecer pendiente, sin pérdida ni duplicado.
10. **Reconexión:** desactivar modo avión y comprobar que nada cambia: la app no usa red y la
    operación de respaldo sigue pendiente, sin pérdida ni duplicado. Conflicto y sesión vencida ya no
    aplican: la variante cloud se retiró el 24 de septiembre de 2026.

Después de cada checkpoint visible ejecutar, por ejemplo:

```bash
./scripts/real-device-evidence.sh capture evidence/android/AAAA-MM-DD-modelo 01-onboarding
./scripts/real-device-evidence.sh capture evidence/android/AAAA-MM-DD-modelo 02-camera-preview
./scripts/real-device-evidence.sh capture evidence/android/AAAA-MM-DD-modelo 03-review-restored
./scripts/real-device-evidence.sh capture evidence/android/AAAA-MM-DD-modelo 04-demo-38-success
./scripts/real-device-evidence.sh capture evidence/android/AAAA-MM-DD-modelo 05-inventory
```

Al terminar:

```bash
./scripts/real-device-evidence.sh finish evidence/android/AAAA-MM-DD-modelo
```

La ejecución se considera válida solo si `checklist.tsv` contiene responsable, fecha y resultado de
los diez puntos, y `sha256.txt` corresponde a las capturas y metadatos de esa misma sesión.
