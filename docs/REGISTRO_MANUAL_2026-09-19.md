# Registro manual de productos

El botón «Registrar manualmente» de Inventario abre una pantalla exclusiva con nombre, cantidad, precio de compra por unidad y precio de venta por unidad. No muestra pestañas de catálogos, listas, proveedores, unidades, código de barras ni SKU. Guardar y cancelar regresan a Inventario.

El producto se registra sin código de barras. Producto, existencias iniciales y costo de compra se guardan mediante la transacción existente de `ProductRegistrationRepository`, de modo que una falla revierte el conjunto. El precio de venta se conserva en el producto; el costo de compra se conserva en el saldo y el movimiento inicial de inventario.

El registro manual usa la unidad normal NIU, o el alias UND activo si ya existe. La selección no depende del orden del catálogo ni convierte estos productos en productos por kilogramo. El formulario conserva sus valores e identidad reservada al recrearse; los reintentos no vuelven a agregar existencias.

El modo manual comparte el guardado y la recuperación de identidad con el registro especial, conservando sus unidades y recorridos propios. No cambia el esquema de datos ni el cierre directo de ventas implementado anteriormente.

Los resultados de validación y la instalación se guardan en `build/reports/manual-registration-2026-09-19/`. Las pruebas de integración utilizan exclusivamente un emulador aislado.

## Validación e instalación

Instalada y abierta en la Huawei AGS6-W09 (Android 10) el 2026-09-19T12:32:54.481369-05:00. Las 37 tablas conservaron exactamente sus filas y valores después de instalar.

Pruebas JVM: 1.834 local y 2.012 cloud, todas aprobadas. Android: 35 pruebas aprobadas, con registro manual, recreación, doble pulsación, cancelación, registro escaneado, edición de inventario, ventas por peso y transacción Room. Arquitectura/formato aprobados. Lint sin errores; advertencias: {'local': {'Warning': 75}, 'cloud': {'Warning': 75}}.

Respaldo previo verificado: `/Users/gustavo/Desktop/BackUpPlis/Antes_instalar_registro_manual_2026-09-19_12-32-27`. Contiene los datos privados y el APK anterior. No se registraron productos ni ventas de prueba en la tablet.

APK instalado: SHA-256 `490a7cdc6a77730d951742064c13259c3532b3bdc6dd7ede3e388a4dd21f5add`.
