# Rendimiento y accesibilidad Android

Este documento fija el dispositivo, los presupuestos y el procedimiento de aceptación antes de
interpretar una medición. Los tiempos publicados deben salir de los JSON/Perfetto de
Macrobenchmark; no se aceptan tiempos tomados a mano ni estimaciones visuales.

## Dispositivo físico de referencia

La aceptación se ejecuta en un **Google Pixel 6a físico** con estas condiciones:

| Propiedad | Valor de referencia |
| --- | --- |
| SoC / memoria | Google Tensor, 6 GB RAM |
| Pantalla | 1080 × 2400, 60 Hz |
| Sistema | Android 15 / API 35, parche estable disponible |
| Build | `localBenchmark`, optimizado y no depurable (único flavor desde el 24 de septiembre de 2026) |
| Estado térmico | `NONE` o `LIGHT`; abortar y enfriar si es mayor |
| Batería | 50–80 %, sin cargar durante la serie normal |
| Almacenamiento | al menos 5 GB libres durante la serie normal |
| Datos | factura sintética incluida; nunca un comprobante real |

El Pixel 6a es la cota de gama media: la medición no se sustituye por la de un teléfono más nuevo.
Un AVD sirve para regresión y diagnóstico, pero no aprueba los presupuestos físicos. CI aplica esos
mismos límites como una cota conservadora: un rojo bloquea el empaquetado y exige investigación;
un verde aún debe repetirse en el teléfono físico. Además se ejecutan configuraciones automatizadas
de pantalla compacta, horizontal y fuente 200 %.

## Presupuestos de aceptación

El percentil se calcula por **nearest rank** sobre las muestras crudas:
`sorted[ceil(0.95 × n) - 1]`. La aceptación física usa 30 iteraciones escalares; para frames se
aplanan los frames de esas iteraciones antes de calcularlo. CI/AVD usa diez como smoke diagnóstico.
Así no se oculta una cola larga detrás del promedio. Si una iteración falla o hay ANR/OOM, la serie
falla y no se elimina esa muestra. En cámara, pipeline y lista, una infracción StrictMode dentro del
recorrido también falla; el test separado de inicio frío mide arranque y no instala esa política.

| Recorrido / métrica | Presupuesto p95 en Pixel 6a |
| --- | ---: |
| Inicio frío, `timeToInitialDisplayMs` | ≤ 1 200 ms |
| Enlace de cámara, `cameraBindFirstMs` | ≤ 500 ms |
| Cámara hasta primer frame, `cameraFirstFrameFirstMs` | ≤ 1 500 ms |
| Captura CameraX hasta JPEG copiado, `cameraCaptureFirstMs` | ≤ 2 000 ms |
| Copia acotada del JPEG, `cameraJpegCopyFirstMs` | ≤ 150 ms |
| Render + JPEG sintético, `bitmapRenderEncodeFirstMs` | ≤ 900 ms |
| Decode del JPEG, `bitmapDecodeFirstMs` | ≤ 250 ms |
| Escritura privada, `bitmapWriteFirstMs` | ≤ 150 ms |
| Preprocesado real (resize/gris/contraste/JPEG), `imagePreprocessFirstMs` | ≤ 1 500 ms |
| OCR local ML Kit, `ocrFirstMs` | ≤ 3 000 ms |
| Parser, `parserFirstMs` | ≤ 250 ms |
| Drenado sync de 100 operaciones con transporte determinista, `sync100FirstMs` | ≤ 500 ms |
| Lista de 100 líneas, `frameDurationCpuMs` | ≤ 16,67 ms |
| Lista de 100 líneas, `frameOverrunMs` | ≤ 0 ms |
| Pipeline, RSS anon + file emparejado | ≤ 384 MiB |

Los límites son objetivos previos, no resultados. El reporte de cada ejecución debe poner al lado el
p95 calculado y el enlace al artefacto crudo. La ausencia de ANR/OOM en toda la serie y de
lecturas/escrituras/red en Main dentro de los tres recorridos funcionales instrumentados es un
requisito binario adicional.

## Automatización reproducible

El módulo `benchmark` mide un artefacto optimizado y separado de producción con
`CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)`: si el perfil no está
disponible, la serie falla. Antes de cada muestra de inicio frío, el harness limpia los datos,
completa onboarding fuera del bloque medido con el trabajo diferido suprimido y detiene ese
proceso. La apertura medida usa un Intent nuevo y conserva el comportamiento real, incluido el
trabajo diferido; así ninguna muestra hereda jobs de la anterior. Cada recorrido del Baseline/Startup
Profile esperan después el nodo `sales_screen` de **Vender**, la pantalla inicial. Esta señal
confirma el primer frame de la pantalla y no la carga completa del catálogo. Como el runner usa `com.android.test`
self-instrumenting, el campo global
`context.compilationMode` del JSON describe al paquete de instrumentación, no al target de
`measureRepeated`; por eso un valor crudo `run-from-apk` no certifica ni refuta el estado dexopt de
`com.facturastock.app`. El gate conserva ese JSON sin modificar y comprueba además tres evidencias
del target: `.prof/.profm` dentro del APK, `RESULT_INSTALL_SUCCESS` y el resultado ART
`actualCompilerFilter=speed-profile, status=PERFORMED` en cada prueba. Su actividad exclusiva usa la
misma receta
`ImageCapture`, copia JPEG y preprocesador que producción, además de ML Kit, parser, la lista Compose
de 100 líneas y el procesador de 100 operaciones de sync. El parser debe recuperar las 38 líneas del
fixture; trabajo incompleto hace fallar la iteración. En cámara, pipeline y lista, StrictMode se
instala antes de la primera composición, detecta disco/red en Main y recursos VM filtrados, registra
el stack y falla el recorrido mediante marcador UI y consulta logcat por PID. La política se
restaura en `onStop`. AndroidX puede ejecutar `DROP_SHADER_CACHE` entre muestras antes de ese
callback; solo se excluye una violación cuyo stack contiene exactamente
`androidx.profileinstaller.ProfileInstallReceiver.onReceive`, porque es preparación del harness y no
la sección medida. Cualquier otro origen sigue fallando. Inicio frío no usa la actividad exclusiva
y queda fuera de esta afirmación StrictMode.

La tarea instala y al terminar desinstala `com.facturastock.app`: se ejecuta con `ANDROID_SERIAL`
apuntando al emulador o al teléfono de referencia, nunca con la tablet del negocio conectada.

```bash
./gradlew --no-daemon --max-workers=1 \
  :benchmark:connectedLocalBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.facturastock.app.benchmark.FacturaStockMacrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.iterations=10

benchmark_json="$(find benchmark/build/outputs/connected_android_test_additional_output \
  -name '*benchmarkData.json' -print -quit)"
FACTURASTOCK_MIN_BENCHMARK_SAMPLES=10 \
  ruby scripts/summarize-macrobenchmark.rb \
    "$benchmark_json" scripts/macrobenchmark-budgets.json
```

CI ejecuta un único job de matriz (`local`) y publica `android-performance-local-*`; la serie
`cloudBenchmark` se retiró con la variante cloud. En un teléfono físico hay que archivar el
directorio de salida entre corridas, porque AndroidX puede reutilizar el mismo nombre de
`benchmarkData.json`.

## Generación de Baseline y Startup Profile

La medición y la generación no comparten build type. `benchmark` replica R8 y resource shrinking de
release para medir el artefacto real; `profile` mantiene `isMinifyEnabled=false`,
`isShrinkResources=false`, `isDebuggable=false` y `<profileable android:shell="true">` para que el
HRF exportado conserve símbolos fuente estables:

```bash
ANDROID_SERIAL=emulator-5556 ruby scripts/run-local-profile-capture.rb <run-id>
```

Selecciona explícitamente el serial de un emulador dedicado a pruebas (`adb devices`). El wrapper
rechaza un serial ausente, ambiguo o físico, comprueba ese dispositivo y lo fija para Gradle.
El harness borra los datos de FacturaStock en el emulador elegido antes de cada captura. La
condición de arranque exige el selector de ventas visible y el botón Contado habilitado y pulsable.

Antes de materializar una salida se comprueba que todas las reglas pertenezcan a
`com/facturastock/app`, que no contengan firmas residuales del mapping de R8 y que el diff corresponda
al recorrido hasta Vender visible. No se copia la salida de `localBenchmark`: sus métodos obfuscados son
válidos únicamente para ese APK. Los archivos fuente revisados siguen en
`app/src/main/baseline-prof.txt` y `app/src/main/baselineProfiles/startup-prof.txt`; R8 los transforma
al construir cada release optimizada.

La variante `profile` desactiva recuperación, retention/stale cleanup, outbox y schedulers durante
la captura; además, la apertura auxiliar del harness parte de datos limpios. El resultado refleja
solo el recorrido crítico hasta Vender visible. El merge revisable conserva en Baseline los CUJ
manuales de parser y lista, que el recorrido de arranque no puede observar. Se genera siempre fuera
de `app/src` a partir del par atómico producido por el wrapper:

```bash
ruby scripts/prepare-profile-candidates.rb \
  app/build/reports/profile-runs/<run-id>/startup-prof.txt \
  app/build/reports/profile-runs/<run-id>/baseline-prof.txt \
  app/src/main/baselineProfiles/startup-prof.txt app/src/main/baseline-prof.txt \
  app/build/reports/profile-candidates
```

La tarea exige manifiesto, hashes y JUnit 2/2 del mismo `run-id`; falla ante owners externos,
símbolos residuales de R8 o métodos ligados a una variante. El directorio candidato contiene ambos
HRF y `summary.txt`; copiar a fuente sigue siendo una decisión humana posterior al diff.

AndroidX puede guardar el archivo como `benchmarkData.json` o anteponer el applicationId; por
eso el descubrimiento usa el sufijo estable `*benchmarkData.json` y selecciona la ruta de la
corrida actual. `scripts/macrobenchmark-budgets.json` es el contrato versionado de los quince
límites de la tabla: el resumidor falla si falta una métrica presupuestada o si su p95 nearest-rank
supera la cota. Las métricas sin presupuesto permanecen visibles como diagnóstico, pero no deciden
el gate.

Se conservan, sin editar:

- `benchmark/build/outputs/connected_android_test_additional_output/` (JSON y trazas Perfetto);
- `benchmark/build/outputs/androidTest-results/connected/` (resultado JUnit);
- `benchmark/build/reports/androidTests/connected/` (reporte navegable).

La lista usa la ruta real híbrida: para un salto largo ejecuta `scrollToItem` hasta cuatro filas
antes del destino y anima únicamente la cola visible con `animateScrollToItem`; para un salto corto
solo anima. El benchmark espera cada recorrido, llega explícitamente a la tarjeta 100 y vuelve a la
primera. La cámara del CI usa
`virtualscene`; su tiempo se etiqueta como diagnóstico, aunque rebasar la cota conservadora sí
bloquea el candidato. La aceptación de cámara y p95 se repite en el Pixel 6a físico con las 30
iteraciones predeterminadas, sin otra instalación o instrumentación concurrente.

La comparación diagnóstica posterior a la optimización del 2026-08-24, con resultados antes/después
y límites explícitos, está en
[`test-evidence/2026-08-24-optimizacion-profunda.md`](test-evidence/2026-08-24-optimizacion-profunda.md).
La auditoría integral de Fase G del mismo día permanece en
[`test-evidence/2026-08-24-fase-g-auditoria.md`](test-evidence/2026-08-24-fase-g-auditoria.md), y la
corrida histórica del 2026-08-20 en
[`test-evidence/2026-08-20-prompt-47.md`](test-evidence/2026-08-20-prompt-47.md). Un resultado rojo
en AVD se conserva como señal de investigación; ni un resultado verde ni uno rojo del emulador
sustituyen la serie física predeclarada.

## Reportes del compilador Compose

Las métricas de estabilidad y los reportes de composables son deliberadamente opt-in; una build
normal y CI no cambian sus argumentos ni escriben estos artefactos. La tarea fuerza una compilación
completa de una sola variante para que el resultado no quede vacío o parcial por incrementalidad:

```bash
./gradlew :app:generateComposeCompilerReports \
  -Pfacturastock.composeCompilerReports=true \
  -Pfacturastock.composeCompilerReportVariant=localRelease
```

Las variantes aceptadas son `localDebug`, `localRelease`, `localBenchmark` y `localProfile`. La
salida queda separada en
`app/build/reports/compose-compiler/<variante>/metrics` y `reports`; se revisan especialmente
`*-classes.txt` y `*-composables.csv` antes de modificar la estructura de una tarjeta.

## Memoria, resolución y cancelación

- La versión OCR limita cada lado decodificado a 2048 px y comprime directamente a un archivo
  temporal. Las páginas son secuenciales y los bitmaps/temporales se liberan en `finally`.
- La captura rechaza un JPEG mayor de 15 MiB antes de reservar su `ByteArray`; la copia del
  `ImageProxy` no ocurre en Main y el proxy siempre se cierra.
- El recognizer, parser, hashing, preprocesamiento y sync comprueban cancelación entre fronteras de
  trabajo. Cancelar no publica un lote parcial, no avanza un cursor y no confirma una outbox a medias.
- El respaldo WorkManager exige red, batería no baja y almacenamiento no bajo; el canal mínimo de
  purga exige solo red para poder liberar datos incluso con poco espacio. Las pruebas inyectan ENOSPC antes
  y durante una pasada y exigen `retry` sin perder la fila durable. Sin transporte, en la variante
  `local`, no se programa ninguno de los dos canales.

## Checklist manual con TalkBack y fuente 200 %

Este recorrido requiere el Pixel 6a físico. Se usa una impresión de la factura demo; las capturas de
evidencia no deben contener RUC, nombres, correo, notificaciones ni inventario reales.

1. Activar fuente al 200 % y TalkBack; reiniciar FacturaStock y confirmar que el título obtiene foco
   antes que las acciones.
2. Completar onboarding con datos `[DEMO]`; comprobar etiquetas, instrucciones y errores sin depender
   del color.
3. Crear compra, abrir cámara real, capturar/importar y ajustar recorte con sus acciones semánticas.
   TalkBack debe anunciar posición y acción, no exigir arrastrar con precisión.
4. Recorrer procesamiento, cabecera, 100 líneas, matching, resumen y confirmación. Cada progreso,
   éxito o error debe anunciarse una vez; el foco no debe saltar detrás de un modal.
5. Confirmar la factura demo y comprobar Historial e Inventario. Los importes se leen como PEN/S/ y
   Room conserva unidades menores `Long` y código ISO `PEN`, nunca el texto localizado.
6. Repetir la revisión en horizontal; ningún CTA ni error obligatorio debe quedar inaccesible.
7. Activar ahorro de batería y validar que la compra local funciona y el respaldo espera. Repetir con
   el umbral de almacenamiento bajo del sistema; no llenar ni borrar datos personales del teléfono.
8. Desactivar ambas restricciones y verificar que la outbox se reanuda una sola vez.

Registrar `PASS`/`FAIL`, alias del probador, fecha ISO 8601, versión de app y modelo sanitizado. No se
declara cumplido “flujo principal con TalkBack y fuente 200 %” sin esta evidencia firmada.

Las parejas de texto/fondo de los esquemas claro, oscuro y de estados semánticos tienen además un
gate JVM de contraste WCAG AA de 4,5:1. El resultado numérico complementa, pero no sustituye, la
revisión visual de foco, iconos, bordes y estados deshabilitados en el teléfono.

## Localización es-PE / PEN

La interfaz usa recursos en español y `Locale.forLanguageTag("es-PE")` para fechas e importes. El
formato es exclusivamente de presentación: los repositorios continúan guardando código ISO 4217,
unidades menores enteras y decimales exactos. Cambiar separadores, símbolo o escala visual nunca
reescribe compras ni movimientos existentes.
