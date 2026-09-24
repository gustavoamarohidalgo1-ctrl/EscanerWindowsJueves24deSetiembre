# Optimización de rendimiento — 19 de septiembre de 2026

Se redujo el trabajo repetido del escaneo y de las actualizaciones de inventario. Las mediciones comparan el APK instalado del registro manual con el nuevo APK, usando el mismo APK de pruebas y datos sintéticos.

## Cambios

- Un código exacto con GTIN válido evita la consulta adicional del catálogo completo. La misma condición se comparte con la política que decide si un exacto puede estar truncado; los códigos sospechosos conservan la revisión de competidores.
- La recuperación de códigos incompletos calcula el checksum cuando hace falta. El cálculo recorre los dígitos sin crear una cadena invertida ni rellenar códigos inválidos.
- Cada colector del inventario conserva los modelos de productos cuyas filas no cambiaron. Compara todos los campos crudos, posiciones y orden; cambiar un producto reconstruye sólo ese producto. Las consultas y sus invalidaciones de Room permanecen iguales.
- El índice de búsqueda reutiliza el nombre normalizado si negocio, producto y nombre coinciden. Cambios de stock, costo o diagnóstico no vuelven a normalizarlo. Un renombre, eliminación o cambio de negocio actualiza el índice; una preparación cancelada no sustituye la instantánea aceptada.

Los cálculos monetarios, validaciones de stock, selección ante códigos ambiguos y validación atómica al recuperar un producto mantienen su comportamiento.

## Comparación medida

Emulador ARM64 API 37, variante `localDebug` en ambas versiones, orden anterior/nueva/nueva/anterior (ABBA), sin Gradle ejecutándose durante la medición. Son tiempos de estas operaciones en este entorno, no una estimación de aceleración global de la tablet.

| Operación | Anterior, mediana | Nueva, mediana | Reducción |
| --- | ---: | ---: | ---: |
| Cambiar stock y recibir inventario: 2.000 productos, 6.000 posiciones | 95,879 ms | 52,105 ms | 45,7 % |
| Recuperación de código incompleto, 2.000 productos: CPU por llamada | 2,009 ms | 0,721 ms | 64,1 % |
| Recuperación de código incompleto, 5.000 productos: CPU por llamada | 5,177 ms | 1,763 ms | 65,9 % |

Inventario: cinco calentamientos y veinte muestras por ejecución, cuarenta muestras por versión. La base de prueba es Room en memoria. La latencia incluye escritura, invalidación, consulta y entrega del resultado; no mide la composición de pantalla. El percentil 95 pasó de 121,621 a 62,271 ms. Cada actualización reconstruyó 1 producto en lugar de 2.000 y reutilizó los otros 1.999.

La primera carga tuvo resultados similares: 151,636/137,653 ms antes y 142,280/146,540 ms después. Dos observaciones por versión no permiten afirmar una mejora de carga inicial. No se midió ni se atribuye una mejora al arranque de la aplicación.

Recuperación: treinta calentamientos, treinta muestras de diez llamadas por tamaño y ejecución. El caso simula dos dígitos iniciales ausentes y un único candidato válido; todas las llamadas recuperaron el producto esperado. Para 5.000 productos, el percentil 95 de CPU pasó de 5,722 a 1,930 ms. El tiempo real mediano de la función pasó de 5,664 a 1,781 ms; no incluye adquisición física del lector ni la consulta del catálogo.

Además, las pruebas con 5.000 nombres verifican 0 normalizaciones al cambiar stock/costo/diagnóstico y 1 al renombrar un producto. Los exactos GTIN válidos hacen 0 consultas adicionales a `listForBusiness`; los exactos sospechosos mantienen esa consulta.

## Coste y límites

La caché de lectura retiene por colector las filas de los productos actuales y sus modelos. No guarda un historial ni vistas `subList` que retengan catálogos anteriores completos; elimina los productos ausentes. Consume memoria adicional acotada al inventario actual. No se midió el consumo de memoria.

Room continúa consultando el inventario cuando cambia una tabla relevante. Esta mejora reduce conversiones, cálculos y normalizaciones posteriores a la consulta; no promete eliminar toda consulta ni volver constante la búsqueda completa.

## Evidencia reproducible

Pasaron 3.864 pruebas unitarias (1.843 local y 2.021 cloud) y 84 pruebas Android seleccionadas. Cubren datos actualizados, aislamiento de negocios/colectores, cancelación, recuperación y ambigüedad del escáner, venta sin duplicados, cantidades, costos y el registro manual. Las mediciones ABBA ejecutaron además los dos benchmarks en cada una de las cuatro rondas. Todas las pruebas con escrituras de ejemplo se hicieron en el emulador, sin registrar ventas de prueba en la tablet.

En `build/reports/performance-2026-09-19/` se conservan:

- `baseline.json` y `baseline-source/`: APK de referencia y código previo.
- `compare_performance.py`, `benchmark-*.log`, `performance-runs.json` y `performance-summary.json`: mediciones, muestras, orden y hashes de los APK.
- `build-tests.log`, `static-lint.log`, `android-tests.log` y resúmenes: validación.
- `install_tablet.py`, `tablet-install/installation.json` y `database-logical-comparison.json`: comprobación de instalación y preservación de datos.

El APK de referencia tiene SHA-256 `490a7cdc6a77730d951742064c13259c3532b3bdc6dd7ede3e388a4dd21f5add`; el APK optimizado medido tiene SHA-256 `1d4c2e647834ae4b7451fa0d68fbb824568134a182060d2f7bf7d62ad1fac47e`.

## Entrega en la tablet

Instalado con `adb install -r` en la Huawei AGS6-W09, conservando el paquete, firma, permisos y datos. El APK instalado coincide por SHA-256 con el medido. La app abrió y su proceso continuó activo sin excepciones fatales detectadas durante la comprobación de lanzamiento.

El respaldo inmediato quedó en `/Users/gustavo/Desktop/BackUpPlis/Antes_instalar_optimizacion_2026-09-19_12-56-57`. Su base pasó `quick_check`; se compararon todas las filas y valores de las 37 tablas antes y después de instalar y no hubo diferencias. No se borraron ni restauraron datos.

El análisis estático de CI y lint local/cloud finalizaron correctamente. Lint conserva 75 advertencias en cada variante y no reportó errores.
