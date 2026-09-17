# Optimización profunda — 8 de septiembre de 2026

**Estado: compilación, pruebas funcionales e instalación en tablet aprobadas, con datos
conservados.** Pasaron 1.569 pruebas JVM locales, 1.747 cloud y 161 casos Android distintos,
con las correcciones de fixtures detalladas al final. Los controles de arranque no detectaron
una regresión persistente. Las mediciones siguen siendo diagnósticas y mantienen las
limitaciones de Perfetto: no certifican presupuestos físicos ni una mejora de toda la app.

La referencia de código es la copia previa de esta ronda en
`/tmp/facturastock-deep-performance-20260908/baseline`.

La revisión abarca presentación, consultas Room, observaciones reactivas, edición de productos,
procesamiento de imágenes, arranque, cámara, OCR y sincronización. La implementación se concentra
en trabajo redundante identificado en el código. Conserva la búsqueda desde dos letras y el
editor compartido de Inventario y registro por escáner que ya estaban presentes en la copia previa.

## Cambios implementados

### Inventario: búsqueda y diagnóstico fuera del hilo de dibujo

`InventoryViewModel` publica de inmediato el texto escrito en Main y ejecuta el recorrido del
inventario o la rentabilidad en `DispatcherProvider.default`. Cada consulta nueva cancela la
anterior; el filtro comprueba cancelación cada 64 elementos. Antes de publicar resultados se
verifican generación, consulta, sección e identidad de la lista de origen. Una búsqueda antigua
no puede sustituir resultados de una consulta o una emisión más reciente.

La preparación de emisiones usa `collectLatest`, y la validación del diagnóstico y aplicación
de sus alertas también se realizan en Default. Si cambia la consulta o el informe durante el
cálculo se reevalúa la instantánea antes de publicar. El resultado tardío de un diagnóstico se
descarta cuando ya no corresponde a las existencias actuales.

La decoración de alertas conserva los objetos `InventoryReadItem` cuya alerta no cambió y
reutiliza la lista completa cuando no hay cambios. Construir estos objetos vuelve a agregar
cantidades y costos; evitar copias idénticas elimina ese cálculo repetido. `InventoryRoute`
recuerda la relevancia del panel de diagnóstico según sus entradas, para no recorrer el mismo
inventario por cambios de texto o foco que no afectan al panel.

Las nuevas regresiones retienen deliberadamente Default mientras Main continúa recibiendo texto,
usan 1.001 productos y cuentan lecturas. También intercalan emisiones, búsquedas y diagnósticos,
y comprueban la conservación de objetos sin alertas modificadas. Son contratos de trabajo
eliminado y coherencia, y pasaron en la validación final.

### Catálogos: cancelar observaciones que dejaron de ser necesarias

`CatalogsViewModel` sitúa la espera de 250 ms dentro de `collectLatest`. Así una nueva solicitud
cancela de inmediato la observación anterior, antes de esperar el siguiente debounce. La señal
`null` también cancela; ya no se filtra antes del colector.

Al abrir el editor desde Inventario o desde un código escaneado se cancela la búsqueda y la
paginación del catálogo oculto. Guardar correctamente desde esos orígenes vuelve al lector sin
solicitar una página y opciones que no se van a mostrar. El catálogo abierto de forma normal
conserva la actualización posterior al guardado.

Las regresiones añadidas cuentan suscripciones activas, verifican su liberación al escribir
otra consulta y comprueban que ambos accesos al editor no observan ni refrescan el catálogo
oculto. No se cambiaron la validación de campos ni la persistencia del producto.

### Room: evitar conversiones y consultas derivadas repetidas

- `RoomProductRepository` y `RoomProductProfitRepository` comparan las filas con
  `distinctUntilChanged` **antes** de convertirlas a objetos de dominio. Se conservan las
  comparaciones posteriores existentes. Esto evita reconstruir el catálogo y calcular valores
  decimales cuando Room invalida una tabla pero entrega exactamente las mismas filas. La
  consulta SQL de origen sí continúa ejecutándose.
- `RoomPurchaseReadRepository` compara la cabecera antes de `flatMapLatest`. Una cabecera
  idéntica ya no cancela y reconstruye las cinco lecturas del detalle. Los flujos interiores
  siguen observando sus propios cambios de líneas, auditoría e imágenes; los cambios reales de
  cabecera, proveedor o sincronización siguen provocando la actualización correspondiente.
- `SaleDao.observeRecentPosted` usa el índice de ventas para recorrer el historial en su orden
  y limitar resultados, y cuenta las líneas mediante una subconsulta por venta. El `EXISTS`
  conserva la exclusión anterior de ventas sin líneas. Se mantienen el negocio, estado,
  desempate por ID, límites y recuentos, sin agrupar y ordenar primero todas las líneas del
  historial. Actualmente esta consulta no tiene un consumidor en la UI de producción: su
  mejora de trabajo SQL no se presenta como una aceleración visible de las ventas actuales.

Se añadieron contratos con 1.000 filas para contar materializaciones y conservar cambios reales
de nombre, precio, estado, costo y cantidad. Las pruebas Room cubren orden, límites, reactividad,
aislamiento entre negocios y plan de consulta, además de un detalle de compra que recibe cambios
de auditoría y sincronización mientras conserva lectores interiores. Los fixtures de lectura no
sustituyen las pruebas transaccionales de confirmación y sus triggers.

### Imágenes: menos llamadas nativas y menos barridos de muestras

`LocalInvoiceImagePreprocessor.applyGrayscaleAndContrast` lee y escribe bloques de 16 filas en
lugar de una fila por llamada. En una página de 2.048 filas pasa de 4.096 llamadas
`getPixels/setPixels` a 256. El scratch máximo a 2.048 px de ancho es 128 KiB. La receta mantiene
la composición del canal alfa sobre blanco, luminancia, contraste, resolución y JPEG; se
comprueba cancelación por fila y antes de escribir cada bloque.

`LocalImageQualityAnalyzer.readLuminance` también lee bloques de 16 filas, con scratch máximo de
48 KiB sobre una muestra de hasta 768 px. Se mantiene la matriz gris que requiere el análisis;
no se conserva una segunda página ARGB completa.

La estimación de inclinación identifica las muestras de tinta una vez, almacena sus coordenadas
en un `IntArray` y reutiliza un histograma para los 21 ángulos. Las coordenadas se empaquetan y
desempaquetan mediante shifts y máscaras. Se conservan orden, umbrales, redondeo, puntuaciones y
selección del ángulo. A 768 × 768, el array de coordenadas tiene una cota de 478.864 bytes; no
crece con la resolución original de la foto. Se comprueba cancelación durante la recolección
por fila y cada 16.384 puntos durante la proyección.

Las pruebas comparan píxeles y luminancia exactamente con la implementación anterior, incluyendo
transparencia y alturas de 1, 15, 16, 17 y 33 filas. La equivalencia de inclinación cubre 30
combinaciones de dimensiones y contenido: blanco, oscuro, ruido, damero, renglones inclinados y
tinta densa. Se añadieron comprobaciones de cancelación antes de ejecutar los kernels.

El método instrumentado
`LocalImageQualityAnalyzerTest.recordSkewKernelDiagnosticWithTheSameSyntheticInvoice` registra
en logcat, con etiqueta `ImageKernelDiagnostic`, nueve muestras alternadas de cada algoritmo
después de tres calentamientos. Incluye casos disperso, denso y oscuro uniforme, cantidad de
muestras, puntos de tinta y scratch. No contiene un umbral artificial de latencia ni sustituye
Macrobenchmark.

## Áreas revisadas cuyos contratos se conservan

El arranque ya materializa dependencias mediante `Lazy`, comparte la observación de DataStore
y ejecuta mantenimiento sobre IO después del primer frame. La recuperación mantiene fronteras
de fallo independientes. No se identificó en esta revisión una modificación adicional del
arranque que justificase alterar esos contratos antes de obtener las trazas comparativas.

Cámara y OCR ya acotan resolución y muestreo, procesan las páginas secuencialmente y liberan
bitmaps, clientes y enlaces de CameraX. El preprocesador serializa lotes pesados mediante un
mutex. Se conserva la calidad de captura y el modelo OCR; las mejoras actúan sobre el trabajo
interno de píxeles sin cambiar el documento procesado.

La sincronización ya limita páginas y mantiene el orden causal de catálogo e inventario. Los
despertares de WorkManager y privacidad tienen reglas para no perder trabajo creado durante una
ejecución. Se conservan ese orden, las colas durables y sus políticas de reintento. Cambiarlas
solo para reducir actividad podría modificar garantías de recuperación; no hay una medida
actual que justifique ese cambio.

Esta ronda no cambia esquemas ni migraciones, algoritmos de stock y costos, escrituras de
ventas o compras, precios guardados, configuración de privacidad, dependencias, firma ni
backend. Los hallazgos funcionales del análisis previo siguen siendo trabajo separado.

## Diagnóstico local reproducible de SQLite

El archivo `/tmp/facturastock-deep-performance-20260908/recent-sales-query-comparison.json`
conserva consultas, planes y muestras crudas. El script correspondiente es
`/tmp/facturastock-deep-performance-20260908/recent-sales-query-comparison.py`.

Se ejecutó sobre SQLite 3.51.0, en memoria, con 10.000 ventas, 100.000 líneas y un límite de
20 resultados. El fixture incluye dos negocios y empates de fecha. Ambas consultas devolvieron
exactamente las mismas filas.

| Evidencia diagnóstica | Consulta anterior | Consulta actual |
| --- | ---: | ---: |
| Instrucciones aproximadas de la VM SQLite | 2.358.100 | 1.400 |
| Árbol temporal para `GROUP BY` | Sí | No |
| Árbol temporal para `ORDER BY` | Sí | No |

El contador se obtiene con un callback cada 100 instrucciones, por lo que su resolución es de
100 pasos. Se prioriza esta evidencia de trabajo sobre los tiempos bajo carga del equipo. El
JSON también conserva diez tiempos por consulta, pero no se presentan como velocidad de la app:
el motor, el dispositivo, la base en memoria y el historial son sintéticos. Las regresiones
Room en Android aprobaron orden, recuentos, límites, reactividad y ausencia de árboles temporales
en el plan. El diagnóstico SQL local no es una medición en la tablet ni una aprobación de los
presupuestos físicos definidos en `ANDROID_PERFORMANCE_ACCESSIBILITY.md`.

## Diagnóstico de imágenes sobre Android

La prueba alternada ejecutada sobre el emulador conserva las 54 muestras: tres fixtures por dos
algoritmos por nueve mediciones, después de tres calentamientos. Se compara el kernel actual
con la referencia del algoritmo previo dentro del mismo proceso, con entradas idénticas.

| Fixture de 768 × 768 | Puntos de tinta | p50 previo | p50 actual | p95 previo | p95 actual |
| --- | ---: | ---: | ---: | ---: | ---: |
| Disperso | 7.203 | 4,767459 ms | 2,705959 ms | 4,924833 ms | 2,804750 ms |
| Denso | 67.799 | 18,377959 ms | 17,866334 ms | 35,134166 ms | 18,179292 ms |

Se recalcularon percentiles nearest-rank sobre todas las muestras; con nueve valores, el p95 es
el máximo. El valor previo de 35,134166 ms del fixture denso permanece incluido. La diferencia
pequeña de medianas en el caso denso debe interpretarse junto con esa variabilidad. El oscuro
uniforme retorna temprano: 375 frente a 625 ns de mediana, insuficientes para atribuir una
regresión o mejora práctica. No reserva el array de coordenadas.

El detalle de nanosegundos, conteos, scratch y hash del log está en
`build/reports/deep-performance/2026-09-08/image-kernel-comparison.json` y `.md`. Es evidencia
complementaria del kernel; no mide decodificación, OCR completo o fluidez de la app, ni repone
muestras ausentes de Macrobenchmark. Las pruebas exactas de píxeles, luminancia, inclinación y
cancelación también pasaron.

## Macrobenchmark: metodología y limitación de captura

Las pruebas se ejecutan sobre `localBenchmark` optimizado en el emulador aislado
`emulator-5556`, API 35, con dos núcleos. El JSON AndroidX reporta 4.111.659.008 bytes de memoria.
Se conservan contexto, modo solicitado, trazas y todas las muestras crudas. Los percentiles
nearest-rank se calculan desde `runs`; los frames se aplanan entre iteraciones. RSS suma los
máximos anon/file emparejados por iteración antes de calcular percentiles; no representa el
máximo simultáneo ni la suma de percentiles independientes.

La primera apertura del pipeline falló porque el fixture guardaba la imagen en
`benchmark/demo-invoice.jpg`, fuera del namespace privado que admite el preprocesador. Se
corrigió únicamente el harness a `draft_images/<draftId>/demo-invoice.jpg` en ambas copias.
`PerformanceBenchmarkActivity.kt` es idéntico byte a byte en los lados previo y actual. La
versión original y el log del fallo se conservan en `baseline-benchmark-fixture-original.kt` y
`pipeline-baseline-crash-before-fixture-fix.log`, dentro del directorio de evidencia. La
corrección común de fixture no se presenta como una optimización de producción.

Arranque, cámara y lista previos conservan diez muestras por recorrido. En el pipeline, una
corrida conserva nueve y el único reintento ocho, aunque ambos pasan JUnit y producen diez
trazas. Perfetto devuelve `EXITCODE=2` y avisa que algunas fuentes no están listas; las
iteraciones afectadas no contienen las etapas `fs_*`. AndroidX 1.4.1 omite esas iteraciones al
faltar métricas: la 1 en la primera corrida, y la 1 y 8 en el reintento. No es un calentamiento
esperado. La fuente local del framework, logs y consultas de trazas sustentan el diagnóstico en
`before-pipeline-missing-sample.md`.

Se conservan separados `before-pipeline-capture-failure` (9/10) y `before-pipeline` (8/10).
No se repite nuevamente, no se mezclan sus muestras y no se reconstruyen los valores ausentes.
El comparador mantiene números crudos y marca el pipeline para revisión; no se utilizará para
atribuir una mejora global ni aprobar presupuesto. La corrida posterior terminó los cuatro
recorridos en JUnit, pero su pipeline vuelve a
conservar ocho de diez muestras: faltan las iteraciones 3 y 7 por el mismo aviso de Perfetto.
Las dos omisiones no se reparan por coincidir con el número de muestras del reintento previo.
La lista mantiene diez runs por lado, pero el capturador registra dos avisos `EXITCODE=2` en
cada serie; el arranque previo inicial también registra dos avisos aunque conserva diez
valores de TTID. Se asocian esas advertencias a sus métricas y se marcan para revisión. Los
controles posteriores de arranque, descritos abajo, no registran esos avisos.

El comparador reproducible es
`/tmp/facturastock-deep-performance-20260908/compare_benchmarks.py`; produce
`build/reports/deep-performance/2026-09-08/macrobenchmark-comparison.json` y `.md`. El contexto
`compilationMode` del JSON corresponde al runner; el target requiere su propia evidencia de
perfil y dexopt. Una serie en emulador no certifica los presupuestos del Pixel 6a físico.

## Comparación diagnóstica posterior y revisión del arranque

La comparación conserva las 26 métricas, sus máximos y muestras anidadas en
`macrobenchmark-comparison.json` y `.md`. El estado general es `NeedsReview` por las capturas
incompletas y los avisos del capturador; no se usa el pipeline para afirmar ganancias ni
cumplimiento de presupuesto. La tabla conserva el A/B inicial completo sin sustituirlo por
los controles posteriores.

| Recorrido / métrica | p50 antes | p50 después | p95 antes | p95 después |
| --- | ---: | ---: | ---: | ---: |
| Arranque: pantalla inicial | 955,549 ms | 1.298,740 ms | 1.193,632 ms | 1.802,821 ms |
| Cámara: captura | 2.497,278 ms | 2.391,065 ms | 3.474,049 ms | 2.829,971 ms |
| Cámara: primer frame | 1.079,495 ms | 1.038,893 ms | 1.556,153 ms | 1.606,000 ms |
| Lista: CPU de frame | 69,415 ms | 67,643 ms | 95,414 ms | 104,945 ms |
| Lista: exceso de tiempo de frame | 76,096 ms | 72,152 ms | 120,125 ms | 152,845 ms |
| Lista: RSS emparejado | 183.668 KiB | 183.740 KiB | 187.964 KiB | 188.520 KiB |

Arranque y cámara conservan diez muestras por lado. Las cifras de frames usan todos los frames:
180/176 en lista y 265/305 en cámara. Los avisos del capturador del arranque previo y ambas
series de lista siguen asociados a sus cifras. Son observaciones diagnósticas; diferencias de
un recorrido no se extrapolan a fluidez general.

El incremento de arranque (36 % en p50 y 51 % en p95) motivó una revisión antes de instalar.
Las veinte trazas muestran que Main pasó de 338,3 a 620,9 ms de espera para ejecutar (`R`/`R+`,
p50), mientras su tiempo realmente ejecutando CPU pasó de 132,0 a 159,3 ms. RenderThread
permanece cercano: 111,1 frente a 109,3 ms de CPU. SurfaceFlinger y otros procesos también
consumen más CPU en la ventana posterior, que también dura más. Es evidencia compatible con
mayor contención del emulador; por sí sola no basta para excluir una regresión de código.

El log posterior confirma instalación del perfil y dexopt del target en `speed-profile` con
resultado `PERFORMED`. La lectura de las trazas está conservada en `startup-trace-analysis.json`,
con SQL y hashes. En dos trazas previas el nombre del proceso está truncado a `acturastock.app`;
la consulta contempla tanto ese nombre como el completo. El intervalo usado es la ventana de
arranque de Perfetto, que puede diferir algunos milisegundos de TTID.

Para revisar si el incremento persistía se ejecutaron dos controles consecutivos en el mismo
emulador: diez arranques del APK previo y después diez del APK actual. En el segundo control
se reutilizaron el APK y R8 ya generados, sin una compilación pesada inmediatamente anterior.
Ambos conservan las diez métricas, pasan JUnit, reportan el mismo contexto y no contienen avisos
de captura Perfetto. Se guardan como `before-cold-control` y `after-cold-control`, separados del
A/B inicial tanto en el JSON como en el Markdown del comparador.

| Serie de control | Muestras | p50 TTID | p95 TTID |
| --- | ---: | ---: | ---: |
| A: APK previo | 10 | 929,872 ms | 1.218,569 ms |
| B: APK actual | 10 | 663,821 ms | 831,395 ms |

El control previo se aproxima a su serie inicial; el APK actual ya no reproduce el empeoramiento
observado en la corrida posterior completa. No se detectó una regresión persistente del
arranque en estos controles. La variación entre corridas y su orden fijo impiden atribuir los
menores tiempos a los cambios de código o prometer una ganancia general. Se conservan todas
las series y sus máximos, sin promediar corridas ni elegir solo el resultado más favorable.

## Validación y correcciones de fixtures

El resumen consolidado está en
`build/reports/deep-performance/2026-09-08/verification-summary.json`.

| Comprobación | Resultado |
| --- | --- |
| Análisis estático | `ciStaticAnalysis` aprobado |
| Compilación | `localDebug`, `cloudDebug` y `localDebugAndroidTest` aprobadas |
| Pruebas JVM locales | 1.569 aprobadas; cero fallos, errores y omisiones |
| Pruebas JVM cloud | 1.747 aprobadas; cero fallos, errores y omisiones |
| Cobertura de dominio | Gate Kover aprobado, mínimo 80 % de líneas |
| Android Lint local / cloud | Cero errores y 66 advertencias por variante |
| Regresiones Android en emulador aislado | 161 casos distintos aprobados; 160 iniciales y el restante tras corregir su fixture |
| Equivalencia de imágenes, cancelación y kernels | Aprobadas; diagnóstico alternado conservado por separado |
| Macrobenchmark posterior | Cuatro recorridos JUnit aprobados; pipeline 8/10 y advertencias de captura conservadas |
| Revisión de incremento de arranque | Dos controles consecutivos de 10/10; incremento inicial no reproducido, sin regresión persistente detectada en estos controles |
| Respaldo, firma e instalación en tablet | Respaldos antes/después; `localDebug` instalada, misma firma y SHA-256 instalado idéntico al APK verificado |
| Integridad y conservación de datos de tablet | `integrity_check=ok` antes/después; esquema 28, 36 tablas y 773 filas lógicamente idénticas |
| Buscador y editor en tablet física | 179 productos; `av` devuelve 9; código existente abre nombre, cantidad y ambos precios habilitados; cancelado sin guardar |

La ejecución JVM inicial falló en una nueva prueba que buscaba `ar` y esperaba solo Arroz:
Azúcar también contiene `ar` después de normalizar tildes. La producción filtraba correctamente.
Se corrigió la consulta del fixture a `rr`, manteniendo las comprobaciones de consulta e
instantánea nueva, y después pasaron la clase y la suite completa. Se conservan
`verification-initial.log`, `inventory-test-initial.xml` e `inventory-viewmodel-recheck.log`.

La ejecución Android inicial aprobó 160 de 161 casos. La nueva prueba del detalle de compra
intentaba agregar un evento de publicación a una compra ya confirmada, y el trigger rechazó
su grafo de auditoría. Se cambió el fixture a un evento válido de sincronización
(`SYNC_CONFLICT_RESOLVED`), manteniendo la verificación de dos eventos, cabecera sin cambios,
lectores conservados y actualización posterior a `SYNCING`. El caso repetido pasó sin cambios
en producción. Las salidas iniciales están en `android-initial/` y la repetición en
`android-purchase-recheck-results/` y `android-purchase-recheck.log`.

La validación cubre publicación ordenada de búsquedas, propagación de cambios reales en Room,
semántica de ventas recientes y equivalencia de imágenes. Quedan límites propios de la muestra
sintética y del emulador. La verificación física de la tablet se detalla a continuación; los
fixtures y recorridos de benchmark no se ejecutaron sobre sus datos comerciales.

## Instalación y comprobación en tablet

Se instaló la variante `localDebug` del paquete `com.facturastock.app` en la Huawei AGS6_W09,
conservando la firma existente. `apk-verification.json` confirma que el APK instalado coincide
con el archivo verificado:

- SHA-256 del APK: `2ccf1088ad8c5a9020d2ea9e8ac9379f1986b5e337c6889204fbc0b901c01767`.
- SHA-256 del certificado: `45517803615814af70b686d7a30cb2d01da4d693c3fe4b83a38a80e22bd94805`.

Los archivos `tablet-data-before.tar` y `tablet-data-after.tar` conservan los respaldos de
base de datos y archivos privados. Las instantáneas `tablet-database-before.json` y
`tablet-database-after.json` son iguales: esquema 28, 36 tablas, 773 filas e integridad `ok`
en ambos lados. La comparación usa por tabla el recuento y SHA-256 de todas sus filas
serializadas y ordenadas, incluyendo valores binarios; comprueba contenido lógico sin
depender de la disposición de páginas del archivo SQLite. El procedimiento reproducible se
conserva en `tablet_snapshot.py`.

En la tablet física, Inventario mostró 179 productos y la búsqueda `av` devolvió nueve.
El código existente `7750885027069` abrió el editor compartido con nombre `3 Ositos Avena`,
cantidad 31, precio de compra 1 PEN y precio de venta 1,20 PEN; los cuatro campos estaban
habilitados. Se canceló sin guardar. La evidencia está en `tablet-smoke.json` y los XML de
Inventario, búsqueda y editor, dentro del mismo directorio de informes.

No se ejecutó `pm clear`, ni se cargaron fixtures o recorridos de Macrobenchmark en la tablet.
Esta comprobación acredita instalación, conservación de datos y funcionamiento de esos flujos
en `localDebug`; no es una medición de rendimiento físico de la variante optimizada
`localBenchmark` ni una certificación de fluidez de todas las pantallas.
