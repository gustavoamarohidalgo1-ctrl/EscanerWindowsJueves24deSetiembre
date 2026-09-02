# Mejora continua de diseño y rendimiento — 2026-08-28

## Resultado

La aplicación conserva la interfaz minimalista validada en la iteración anterior y mejora su
comportamiento con historiales y catálogos grandes. Las optimizaciones mantienen separados los
flujos exhaustivos usados por exportación/sincronización de las lecturas acotadas usadas por la UI.

## Cambios verificados

- El historial de facturas carga 30 compras por bloque mediante cursor estable, muestra un conteo
  explícito de compras cargadas y evita materializar todas las líneas históricas en Compose.
- La búsqueda del historial tiene debounce cancelable de 250 ms, conserva visibles los controles
  mientras filtra y mantiene la comparación literal histórica de proveedor, RUC, comprobante y
  fecha. Los filtros exactos de estado y sincronización se ejecutan antes del límite SQL.
- La exportación, sincronización y privacidad siguen usando la lectura completa de compras; la
  paginación de pantalla no puede truncar esos procesos.
- Los adjuntos técnicos de una factura solo se descifran al expandirlos. Al cerrar, cambiar de
  factura, cancelar o destruir el ViewModel, los arreglos de bytes en memoria se sobrescriben.
- Los reportes recorren las ventas confirmadas por lotes keyset de 100 y solo conservan un lote de
  filas contables crudas a la vez. Los totales siguen usando `BigDecimal` y se agregan en una pasada
  por moneda.
- El reporte abierto se actualiza automáticamente al cruzar el límite del día, semana o mes del
  negocio, sin depender de un proceso destructivo cada 24 horas.
- Inventario observa solo la proyección visible (`Existencias` o `Ganancias`), cancela la consulta
  oculta y conserva una caché para volver de inmediato. El selector es horizontal, compacto y tiene
  objetivos táctiles accesibles de 48 dp.
- Catálogos, detalle/historial de compras y ajustes usan filas planas y menos contenedores. Los
  detalles técnicos permanecen plegados hasta que realmente se solicitan.
- Se corrigieron avisos de accesibilidad y API en Compose, recursos y launcher adaptativo.

## Evidencia automatizada

- JVM local: **1,239/1,239**, 0 fallos, 0 errores, 0 omitidas.
- JVM cloud: **1,339/1,339**, 0 fallos, 0 errores, 0 omitidas.
- Android completo en Pixel Tablet API 35: **610/610**, 0 fallos, 0 errores, 0 omitidas.
- Kotlin AndroidTest cloud: compilación correcta.
- Lint local: **0 errores**, 27 avisos exclusivamente de versiones disponibles.
- Lint cloud: **0 errores**, 27 avisos exclusivamente de versiones disponibles.
  - 3 `AndroidGradlePluginVersion`
  - 12 `GradleDependency`
  - 11 `NewerVersionAvailable`
  - 1 `OldTargetApi`
- Release local y cloud: manifiestos, lint vital, R8, reducción de recursos, Compose mapping y
  perfiles ART validados.

Total ejecutado: **3,188 pruebas aprobadas** entre JVM y dispositivo.

## Revisión manual en tablet

- Primer inicio completado con negocio y almacén de prueba.
- Inicio, Vender, Facturas, Historial de facturas, Inventario/Existencias,
  Inventario/Ganancias y Reportes Hoy/Semana/Mes inspeccionados mediante jerarquía accesible.
- Rangos comprobados: día, semana `24–30 ago. 2026` y mes `1–31 ago. 2026`.
- Sin `FATAL EXCEPTION`, ANR ni muerte del proceso después del recorrido final.
- `com.facturastock.app/.MainActivity` quedó activa en Inicio sobre `emulator-5554`.

## Artefactos

- `app/build/outputs/apk/local/release/app-local-release-unsigned.apk`
  - 47,588,758 bytes
  - SHA-256 `b0ee29013e7ee342dd564cc0f0182d2edd7875b57bf4dbdf87227ef9485cde4d`
- `app/build/outputs/apk/cloud/release/app-cloud-release-unsigned.apk`
  - 49,061,804 bytes
  - SHA-256 `7a43fb2e2f8f9fb3952d17d2e532e977674742017eb2f289ac5bb2975a591803`
- APK instalado en tablet: `app/build/outputs/apk/local/debug/app-local-debug.apk`
  - 67,177,714 bytes
  - SHA-256 `b4a175e3160b9de03dbb0f824058683bb8ce0b6560e96e0930f3f6fdfb9dd724`
