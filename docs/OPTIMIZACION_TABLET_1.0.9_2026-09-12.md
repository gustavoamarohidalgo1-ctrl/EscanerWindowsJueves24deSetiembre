# Optimización para tablet — 1.0.9

Fecha: 12 de septiembre de 2026.

Estado: versión 1.0.9 instalada y abierta en la tablet física Huawei AGS6_W09 por Wi-Fi. Compilación, pruebas, respaldo y conservación de datos verificados. Se mantuvo la variante localDebug del dispositivo y Room 29.

## Base de comparación y alcance

La comparación corresponde al código conservado al iniciar esta sesión en `build/reports/optimizacion-tablet-2026-09-12/source-before.tar.gz`, acompañado de `source-before-sha256.json` y `git-status-before.txt`. El árbol ya contenía cambios sin commit; este documento describe las optimizaciones posteriores a esa captura, no todo el diff contra Git HEAD ni las correcciones de auditorías anteriores.

El trabajo reduce consultas, conversiones decimales, asignaciones de objetos y ordenamientos en inventario, selección de productos de ventas y sugerencias de coincidencia. Conserva las reglas de stock, moneda, autorización, escritura y confirmación. No requiere otra versión de Room ni modifica tablas, índices, triggers o migraciones.

## 1. Lectura breve de inventario por producto

`InventoryReadRepository.observeProductItem(businessId, productId)` devuelve únicamente `InventoryReadItem`: cabecera, unidad, posiciones, cantidades, costos de la proyección y alertas. Su implementación predeterminada obtiene `observeProduct(...).map { it?.item }`, manteniendo compatibilidad con fakes y otros adaptadores.

El override de `RoomInventoryReadRepository` utiliza `InventoryDao.observeReadProductPositions`: una sola sentencia por producto con uniones de producto, unidad, saldos y almacenes. La misma sentencia proporciona una instantánea coherente. No consulta ni cuenta `stock_movements` y no materializa el historial de compras o ventas. Reutiliza el mapeo de la lista de inventario.

La consulta conserva:

- El producto aunque todavía no tenga saldos, mediante `LEFT JOIN`.
- `null` para un identificador inexistente o perteneciente a otro negocio.
- El orden de almacenes por nombre normalizado y después identificador.
- Cantidades negativas, monedas separadas, alertas de archivo y precisión decimal persistida.
- Actualizaciones reactivas de producto, unidad, saldo y almacén.

Ventas sustituye dos lecturas del detalle completo por esta API: recuperación puntual de opciones después de una lectura de código/alta y comprobación del producto al confirmar una venta por peso. Esos flujos ya consumían solamente `item`; mantienen las comprobaciones de negocio, moneda, estado y cantidad.

El detalle completo conserva su lectura anterior, dentro de una transacción. No se deduplica por contadores ni timestamps, pues podrían ocultar cambios reales de saldo, unidad o almacén. Se descartó la caché del snapshot completo durante la revisión de memoria: retener simultáneamente filas crudas y modelos de dominio aumentaría la memoria ocupada por historiales largos. La deduplicación permanece en la API breve, cuyo tamaño depende de los almacenes del producto.

## 2. Agregaciones de inventario en una pasada

`InventoryReadItem` calcula la cantidad total, valores por moneda, promedios y alertas en una pasada por las posiciones. Evita los dos `groupBy` y varias colecciones auxiliares de la implementación anterior.

Se conserva el orden de primera aparición de cada moneda, la secuencia de sumas con `BigDecimal.ZERO`, la escala decimal resultante, el redondeo de promedio a 18 decimales con `HALF_EVEN` y el promedio indefinido si una moneda tiene posiciones negativas o cantidad total no positiva. Continúan rechazándose ubicaciones duplicadas.

Las alertas conservan su orden y se exponen mediante un conjunto no modificable. La validación cronológica de `InventoryProductDetail` usa un iterador en lugar de construir la lista de pares de `zipWithNext`; mantiene las comparaciones de fecha, creación e identificador.

El diagnóstico estructural de `InventoryAggregationOptimizationTest` especifica **6.000 lecturas del contenedor original frente a 1.000** para una tarjeta sintética de 1.000 posiciones. Este conteo se refiere al contenedor fuente: no incluye los recorridos adicionales de las listas agrupadas del algoritmo anterior y no es una medición de tiempo, memoria o FPS de la tablet.

## 3. Proyección incremental del catálogo de ventas

`SalesCatalogProjection.kt` prepara el mapa de productos cuando cambia el catálogo y mantiene un `SalesCatalogProjector` independiente por contexto de negocio y moneda. Un cambio de stock compara cada producto con su snapshot previo y reconstruye únicamente sus opciones afectadas.

Los cambios de cantidad o precio que conservan las claves de orden sustituyen las filas necesarias en el orden global existente. Los cambios de costo o versión que no afectan las opciones visibles actualizan el inventario disponible para el carrito y reutilizan las mismas listas de opciones. Los cambios de nombre, almacenes disponibles, estado o secuencia de productos con claves empatadas vuelven a ordenar.

El orden conserva la semántica anterior: opciones por almacén ordenadas por nombre en minúsculas e identificador, y orden global estable por nombre de producto y de almacén con `Locale.ROOT`. El orden de origen relevante procede del inventario antes del `flatten`. La preparación inicial calcula una vez las claves en minúsculas, evitando repetir esa normalización en cada comparación.

La preparación comprueba cancelación durante los recorridos y antes de publicar un snapshot completo en la caché. La instancia se descarta al cambiar el contexto. Las escrituras del carrito continúan en los mismos flujos y la mutación de estado de pantalla permanece en su collector.

Los fixtures de `SalesCatalogProjectionTest` especifican catálogos de 1.000 y 10.000 productos. Ante un cambio de cantidad de un solo producto, verifican la reutilización de 999 y 9.999 grupos de opciones respectivamente, con salida equivalente a reconstruir y ordenar el catálogo completo. Esto reduce reconstrucciones y ordenamientos; no convierte todo el procesamiento en O(1), pues todavía se recorre la proyección para detectar cambios y, cuando corresponde, sustituir filas.

## 4. Selección de cinco candidatos y puntuación

En coincidencia difusa de nombres, `ProductMatchingUseCase` conserva los cinco mejores candidatos mediante inserción acotada. Evita materializar y ordenar todos los candidatos puntuados. Mantiene el límite de productos válidos que se puntúan, el umbral, los filtros de negocio/archivo y el desempate por puntuación descendente, nombre e identificador. Si los exactos ya completan las cinco opciones, no solicita la fase difusa.

En sugerencias de códigos incompletos, `SalesBarcodeSuggestions` también mantiene hasta cinco candidatos ordenados sin ordenar la lista después de cada coincidencia. Conserva el orden por dígitos omitidos, código e identificador, las condiciones de disponibilidad y la cancelación. Un código que no puede generar sugerencias se rechaza antes de recorrer el catálogo.

La puntuación de nombres añade reducciones de trabajo que conservan el resultado esperado: omite normalización Unicode para texto ASCII, evita reemplazos de espacios cuando el texto ya está normalizado, recorre tokens sin listas intermedias y reserva las filas de Levenshtein sólo cuando hacen falta. Si una cota basada en la diferencia de longitudes demuestra que la puntuación de prefijo ya fija el resultado, devuelve esa misma puntuación sin calcular la matriz. Las semillas de búsqueda mantienen orden, variantes y límites.

La revisión corrigió una diferencia entre plataformas: el regex `\s+` de Android reconoce espacios Unicode que la JVM del host no reconoce de igual manera. El atajo de espacios queda limitado a ASCII; ante cualquier carácter desde U+0080 se utiliza el regex original de la plataforma. `ProductNameSimilarityAndroidTest` comprueba NBSP (U+00A0), U+202F y U+2003, sus puntuaciones y prefijos, para cubrir esta frontera en Android real.

## Pruebas y diagnósticos

La tabla describe la cobertura añadida o ampliada. Los resultados de ejecución y sus límites aparecen en la sección final.

| Prueba | Contrato comprobado |
| --- | --- |
| `RoomInventoryProductReadTest` — instrumentada | Equivalencia con detalle completo usando dos almacenes, monedas distintas, cantidades negativas y 36 decimales; 1.500 movimientos sintéticos; `QueryCallback` sin consultas al historial; plan por índices; producto sin saldos, inexistente y ajeno al negocio. |
| `RoomInventoryProductReadTest` — emisiones | Cambios de cantidad/costo, unidad, almacén y producto; detalle actualizado aunque los contadores y timestamps de revisión coincidan; aparición de nuevos movimientos; la prueba ignora duplicados idénticos en su collector sin alterar el contrato del repositorio. |
| `InventoryAggregationOptimizationTest` | Comparación contra el algoritmo previo con ejemplos deterministas y aleatorios; orden de monedas/alertas, escala exacta, ubicaciones duplicadas y conteo de lecturas de posiciones. |
| `SalesCatalogProjectionTest` | Equivalencia con reconstrucción completa a través de cambios y empates; reutilización por identidad; 1.000/10.000 productos; cancelación sin publicar una caché parcial; diagnóstico JVM alternado sin umbral temporal. |
| `SalesBarcodeSuggestionsTest` | Inserción acotada equivalente al orden completo en catálogos mezclados; códigos imposibles sin recorrido; cancelación sin sugerencias parciales. |
| `ProductMatchingUseCaseTest` | Cinco mejores con puntajes/desempates/corte de página equivalentes; cinco exactos sin fase difusa; semillas acotadas sin cambiar orden ni variantes. |
| `ProductNameSimilarityTest` | Puntajes comparados con la referencia previa, incluyendo Unicode, límites de longitud y prefijos. |
| `ProductMatchingPerformanceDiagnosticTest` | Diagnóstico JVM con páginas de 200 candidatos; alterna referencia y optimización, verifica equivalencia y registra tiempos sin imponer umbrales. |

Se ejecutaron pruebas existentes de ventas, escáner, peso, inventario, navegación y repositorios para validar la integración. Se añadió también ProductNameSimilarityAndroidTest, que comprueba los espacios Unicode en el runtime de Android.

## Evidencia SQL en el host

Se conservaron los siguientes artefactos en `build/reports/optimizacion-tablet-2026-09-12/`:

- `facturastock-inventory-read-sql-20260912.py`: extrae las consultas del DAO actual, crea el esquema Room 29 exportado en SQLite en memoria y prepara datos sintéticos.
- `facturastock-inventory-read-sql-20260912.json`: salida del diagnóstico, equivalencia de cabecera/posiciones y plan de consulta.

El fixture contiene 1.000 productos de catálogo, dos posiciones del producto consultado y 100.000 movimientos. Se miden cinco lecturas de cada variante; los tiempos incluyen la consulta SQL y la materialización de filas en Python, sin mapeo Kotlin, Compose ni dispositivo Android.

| Dato | Detalle completo previo | Lectura breve |
| --- | ---: | ---: |
| Sentencias `SELECT` por consulta | 4 | 1 |
| Filas devueltas por sentencia | 1 + 1 + 2 + 100.000 | 2 |
| Total de filas devueltas | 100.004 | 2 |
| Mediana registrada de consulta y lectura de filas en el host | 121,765 ms | 0,010 ms |

El JSON registra cabecera y posiciones equivalentes. El plan de la lectura breve utiliza las claves de producto, unidad y almacén y el índice compuesto de saldos por negocio/producto. La ordenación temporal queda limitada a las posiciones del producto; no recorre el catálogo ni el historial.

Estos valores son un diagnóstico de SQLite en el host. No representan una aceleración medida de la aplicación en la tablet, su memoria, autonomía o fluidez. Los tiempos JVM que produzcan las pruebas de proyección y matching también deben etiquetarse como mediciones del host.

## Diagnóstico de catálogo de ventas en JVM sin cobertura

Tres JVM nuevas, dos rondas de calentamiento y una medida en cada JVM; cada ronda compara ocho muestras alternadas y comprueba la equivalencia de resultados. La tabla muestra la mediana de las medianas por JVM. Se midió un cambio de stock de un producto; la preparación inicial del catálogo queda fuera de esta medición.

| Productos | Reconstrucción anterior | Proyección incremental |
| --- | ---: | ---: |
| 1,000 | 1.021 ms | 0.302 ms |
| 10,000 | 8.791 ms | 3.821 ms |

Evidencia: `sales-uninstrumented.log`, `sales-uninstrumented-summary.json` y `SalesPerformanceProbe.java`. Se reutilizan respectivamente 999 y 9.999 grupos de opciones. Hubo otros procesos del host en ejecución; estas cifras describen el diagnóstico sintético y no el tiempo de respuesta, FPS ni porcentaje de mejora de la tablet.

## Diagnóstico de matching en JVM sin cobertura

Se ejecutaron tres JVM nuevas sin `javaagent`. En cada una, `MatchingPerformanceProbe` descartó cuatro rondas completas de calentamiento y conservó únicamente `PROBE_ROUND=4 measured`. Cada ronda del diagnóstico alterna nueve muestras de referencia y optimización, con cuatro páginas por muestra. La tabla muestra la mediana de las tres medianas medidas, una por JVM; no incluye rondas de calentamiento ni tiempos con Kover.

| Escenario, página de 200 candidatos | Scorer anterior | Scorer optimizado |
| --- | ---: | ---: |
| Prefijo de dos letras | 0,314 ms | 0,118 ms |
| Prefijos no contiguos | 0,356 ms | 0,123 ms |
| Acentos y prefijos | 0,451 ms | 0,242 ms |
| Error de OCR | 0,523 ms | 0,453 ms |
| Catálogo mixto | 0,449 ms | 0,270 ms |

Los checksums de puntuación coincidieron en todas las ejecuciones. Las cinco medianas mejoraron en este diagnóstico del scorer. El host compartía recursos con otros procesos; no se aisló su carga y hubo variación entre JVM. Estos tiempos no miden consultas SQL, Compose ni la tablet y no permiten inferir un porcentaje de mejora de la aplicación en el dispositivo.

El [log completo](/Users/gustavo/Desktop/ProyectoMayda/build/reports/optimizacion-tablet-2026-09-12/facturastock-optimization-matching-uninstrumented-20260912.log) conserva argumentos JVM, rondas y muestras resumidas; el [probe reproducible](/Users/gustavo/Desktop/ProyectoMayda/build/reports/optimizacion-tablet-2026-09-12/MatchingPerformanceProbe.java) conserva el procedimiento de calentamiento y ejecución.

## Validación completada

| Comprobación | Resultado |
| --- | --- |
| Formato y `ciStaticAnalysis` | Aprobados. |
| Unitarias localDebug | 1.700 aprobadas; 0 fallos, errores u omisiones. |
| Unitarias cloudDebug | 1.878 aprobadas; 0 fallos, errores u omisiones. Comparten casos con local; no sumar como pruebas únicas. |
| Compilación localDebug y AndroidTest | Aprobadas. |
| Lint localDebug / cloudDebug | 0 errores; 71 advertencias por variante. |
| Kover localDebug | Compuerta aprobada; 87.23% de líneas y 65.97% de ramas en el ámbito configurado. |
| Regresión Android en emulador aislado API 35 | 149 pruebas aprobadas: repositorios, ventas, anulación, códigos, peso, inventario, matching, Unicode y navegación. |
| Revalidación del APK final tras retirar la caché del historial | 20 pruebas aprobadas: lectura por producto, compatibilidad de inventario y pantallas de inventario. Son casos repetidos de la ronda anterior. |
| Instalación física | Huawei AGS6_W09, Android 10/API 29, conexión Wi-Fi; actualización conservando el paquete y su firma. |
| Optimización de Android | `cmd package compile -m speed -f com.facturastock.app`: Success. |
| Arranque físico | MainActivity abrió correctamente; pantalla de ventas visible, sin diálogo de error ni excepciones fatales en el proceso recién iniciado. |

Las dos incidencias iniciales de pruebas fueron fixtures: un UUID nulo en el catálogo sintético y un wrapper que retrasaba la API de detalle anterior. Se corrigieron conservando las aserciones, y las suites finales pasaron. La revisión independiente detectó y corrigió el tratamiento de whitespace Unicode y el límite de recorrido antes de la entrega. No se ejecutó la suite instrumentada completa del proyecto: esta validación cubre los flujos afectados; los fixtures ajenos señalados en la revisión profunda siguen fuera de esta ejecución.

La lectura completa del historial se restauró a la implementación anterior para evitar retener a la vez todas las filas crudas y el modelo completo. Después de este ajuste se recompilaron y verificaron ambas variantes, y se repitieron las 20 pruebas Android directamente relacionadas. El APK de esta última ejecución coincide byte por byte con el instalado.

### Artefacto y datos conservados

- Paquete: `com.facturastock.app`; variante: `localDebug`; versión: `1.0.9`, código `10`.
- SHA-256 del APK instalado: `1f6a957616c4acb9a0817690f8545937ff29784a1f7f7f2242703e08b751753b`.
- Certificado de firma: coincide con la versión 1.0.8 que tenía la tablet.
- Respaldo privado: `/Users/gustavo/Desktop/BackupsFacturaStock/2026-09-12_11-42-36_antes_de_1.0.9`. Incluye los APK anterior/nuevo, archivos privados de la app, comprobaciones y estado visible antes/después.
- Se detuvo la app para tomar una copia consistente antes de instalar; no se desinstaló ni se borraron sus datos.
- Comparación inmediatamente después de instalar, antes del primer arranque: mismo esquema Room 29, mismas **37 tablas y 2.872 filas** de la base principal, incluidos sus metadatos. Los hashes lógicos de todas las tablas coinciden, así como los archivos persistentes y las demás bases SQLite.
- `PRAGMA integrity_check`: `ok`; `foreign_key_check`: 0 errores. Preferencias y archivos guardados idénticos antes y después de la instalación.
- El respaldo técnico conserva los datos de esta instalación; los elementos protegidos por Android Keystore siguen dependiendo de las claves del dispositivo original.

No se ejecutaron pruebas que borren datos ni benchmarks de carga en la tablet del usuario. El emulador temporal quedó cerrado. Se guardaron lecturas de memoria/cuadros antes y después, pero las cargas y estados de arranque difieren: no permiten atribuir un porcentaje de aceleración, memoria o batería al dispositivo. El resultado demostrado es la reducción de consultas, recorridos, reconstrucciones y cálculos descrita arriba, junto con la regresión funcional aprobada y la conservación de los datos guardados.

Evidencias de esta entrega: `build/reports/optimizacion-tablet-2026-09-12/`, especialmente `final-validation.log`, `unit-summary.json`, `instrumentation-initial.log`, `instrumentation-final.log`, `tablet-data-preservation.json`, `tablet-installation.json` y `tablet-post-install-smoke.json`.
