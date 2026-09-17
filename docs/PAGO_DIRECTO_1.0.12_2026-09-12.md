# Pago directo de deudas — 1.0.12

**Registrar pago** registra todo el saldo pendiente con un toque, sin formulario. Usa la fecha actual y, al terminar, abre **Reportes → Día**, donde el importe aparece en **Cobros de deudas de hoy**. El importe que se registrará se muestra debajo del botón. **Registrar abono** conserva el formulario para importes parciales y datos adicionales.

La venta a crédito ya descuenta sus productos del inventario cuando se crea la deuda. Cobrarla no genera otra venta ni otro movimiento de stock. Se conservan la venta original, sus productos y los abonos anteriores; sólo se añade el pago del saldo restante y se marca la deuda como pagada. El pago directo usa el método **Otro**, porque no solicita ni presupone efectivo, Yape, Plin o transferencia.

La pantalla y el PDF diario incluyen pagos y abonos por su fecha de cobro, aunque la venta corresponda a otro día. Los importes se agrupan por moneda. **Total vendido** mantiene las ventas de su fecha original; los cobros de deudas se muestran por separado para evitar contar dos veces el mismo ingreso. El PDF exclusivo de deudores continúa mostrando únicamente los saldos pendientes de todas las fechas.

El botón bloquea toques repetidos mientras guarda y conserva la identidad del intento ante una respuesta incierta. Los cambios de negocio, saldo o versión se comprueban antes de registrar; los errores se muestran directamente en el detalle. El pago parcial y la eliminación de deudas conservan sus recorridos respectivos.

No hay migración: se mantiene Room 29. La nueva lectura utiliza el índice existente de pagos por negocio y fecha. Las escrituras de cobros siguen utilizando el repositorio de pagos con su transacción y control de versión.

## Validación y entrega

Validación completada: 1.760 pruebas unitarias locales, 1.938 de la variante cloud y 38 pruebas Android en un emulador desechable, todas aprobadas. Formato, análisis estático y cobertura aprobados. Lint: cero errores y 75 advertencias en cada variante. Se corrigió un fixture de observación completada para que tanto ventas como cobros terminaran antes de comprobar la nueva suscripción; no requirió cambios de producción.

El recorrido con Activity y Room reales comprobó una deuda de 10 con abono previo de 4: un toque registró los 6 restantes, abrió el reporte de hoy incluso viniendo de un reporte semanal y mantuvo intactos venta, líneas y movimientos de stock. Las demás pruebas cubren doble toque, reintento, cambio de negocio, fecha del cobro, monedas y compatibilidad con pagos parciales y eliminación de deudas.

El PDF de prueba tiene dos páginas, ambas revisadas visualmente. La extracción verificó las 16 referencias de pago exactamente una vez y los totales separados PEN 136.65 y USD 62.15. La captura del recorrido muestra el pago recibido en Reportes → Hoy.

Instalada directamente por Wi-Fi en la Huawei AGS6_W09 el 12/09/2026: versión 1.0.12, código 13. La firma coincide con la instalación anterior y el APK instalado coincide con SHA-256 `c0049fd78ec57f48e260d5625a59c2060d0bb6207e781afabe595a7a4fd37785`. Copia privada previa en `/Users/gustavo/Desktop/BackupsFacturaStock/2026-09-12_13-56-09_antes_de_1.0.12`.

La comparación inmediatamente después de instalar, antes de abrir la aplicación, confirmó las mismas 37 tablas y 2.872 registros, Room 29, integridad correcta y cero errores de claves foráneas; tampoco cambiaron los archivos persistentes comparados. Después se verificó el arranque sin excepción fatal, se completó la compilación AOT `speed` y se dejó abierta la lista de deudores. No se cobró ni eliminó ninguna deuda real durante la comprobación.

Base de comparación: `build/reports/pago-directo-1.0.12-2026-09-12/source-before-sha256.json`. Evidencia, registros de validación, PDF sintético, captura y APK en el mismo directorio. Los 29 archivos de esquema permanecen idénticos; las escrituras de checkout, inventario y registro de pagos conservan su implementación previa.
