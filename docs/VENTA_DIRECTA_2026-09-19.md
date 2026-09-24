# Cierre de venta con un solo toque

«Concluir venta» registra el carrito al contado directamente. «Registrar deuda» registra la venta a crédito con el deudor indicado. Se eliminó el diálogo adicional de confirmación de ambos recorridos. Escanear productos sigue agregándolos al carrito; el cierre ocurre al pulsar el botón.

El callback captura el identificador, negocio, versión, contenido y términos del carrito que se muestra. Bloquea de inmediato toques repetidos y ediciones posteriores, y vuelve a comprobar la vigencia del carrito dentro de la operación exclusiva. El caso de uso y el repositorio conservan sus validaciones de pertenencia al negocio, cantidades, precios, stock e idempotencia.

Los reintentos de una venta pendiente conservan el deudor y vencimiento persistidos. Se permite verificar una operación pendiente aunque falle la primera carga del catálogo. Términos persistidos inconsistentes producen un error visible sin registrar la venta.

Se mantienen los diálogos de otras operaciones, como descartar cambios y reemplazar códigos. La recuperación del lector sigue incorporada.

## Verificación

Los resultados de pruebas JVM, Android, formato, arquitectura, lint e instalación se conservan en `build/reports/direct-checkout-2026-09-19/`. Las pruebas cubren efectivo, crédito, peso, doble toque, stock, ediciones pendientes, lecturas en cola, cambio de carrito y reintentos pendientes sin catálogo.

La prueba de Android `reportVoidCancelsOnlyAfterConfirmationReturnsStockAndSurvivesRecreation` se excluyó por un fallo preexistente de reapertura de Reportes, reproducido anteriormente con el APK original. No se considera corregida ni aprobada.

La instalación usa `adb install -r`, después de respaldar los datos privados y el APK anterior en `/Users/gustavo/Desktop/BackUpPlis`. El informe `tablet-install/installation.json` registra el resultado y la comparación del contenido de todas las tablas antes y después. No se realizan ventas de prueba en la tablet.

## Instalación verificada

Actualizada y abierta en la Huawei AGS6-W09 (Android 10) el 2026-09-19T12:10:24.682960-05:00. Las 37 tablas conservaron exactamente las mismas filas y valores después de instalar; no se ejecutaron ventas reales.

Respaldo previo: `/Users/gustavo/Desktop/BackUpPlis/Antes_instalar_venta_directa_2026-09-19_12-09-51`. Incluye archivo de datos privados y APK anterior; ambos hashes verificados.

APK instalado: SHA-256 `8221295087e64b6ac53deea7ecc055f79ed9ea5c29d26144f17b9a6c90b4c744`. Pruebas JVM: 1.823 local y 2.001 cloud, sin fallos. Android: 72 pruebas seleccionadas y repetición de contado/doble toque y crédito sobre el APK final, ambas aprobadas. Lint: 0 errores y 75 advertencias por variante.
