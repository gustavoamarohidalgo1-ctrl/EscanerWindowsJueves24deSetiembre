# Evidencia de auditoría — Fase G (prompts 44–50)

Fecha: **24 de agosto de 2026** (`America/Lima`). Ejecutado desde la raíz local del proyecto. Los
datos de prueba fueron sintéticos; no se usaron facturas ni identificadores fiscales reales.

## Entorno ejecutado

- JDK 17 y Gradle Wrapper del repositorio.
- AVD `Pixel_10a`, Android 17/API 37, `sdk_gphone16k_arm64`.
- Firebase Emulator Suite local para Auth, Firestore, Functions y Storage.
- La máquina anfitriona ejecutó Node 24 y mostró la advertencia de que Functions declara Node 22;
  las 106 pruebas pasaron. CI está fijada en Node 22.
- No hubo teléfono físico, secretos productivos ni acceso a Google Play Console.

## Resultados reproducibles

| Control | Comando/artefacto | Resultado |
| --- | --- | --- |
| JVM hermético | `./gradlew --offline --no-daemon test` | `BUILD SUCCESSFUL`; 5.998 ejecuciones entre seis variantes, 0 fallos/errores/omitidas |
| Corpus dorado | `app/build/reports/golden-corpus/golden-corpus-report.md` | 7/7 fixtures, 144/144 verificaciones; siete PNG con hashes distintos |
| Cobertura crítica | `:app:koverVerifyLocalDebug :app:koverXmlReportLocalDebug` | 10.450/11.742 líneas = **89,00 %**; 5.438/8.116 ramas = 67,00 % informativo |
| Análisis y Lint | `ciStaticAnalysis`, `lintLocalDebug`, `lintCloudDebug` | Verde; 0 errores y 61 advertencias por flavor |
| Android local | `:app:connectedLocalDebugAndroidTest` | 434/434, 0 fallos/errores/omitidas; incluye 100 líneas con fuente 200 % y reciclado hasta la fila final |
| Runtime cloud | `CloudFirebaseRuntimeSmokeTest` | 1/1 |
| Migración/repositorios | `RoomCloudBusinessBindingMigrationIntegrationTest` | 2/2; v19→v20 conserva y completa la operación pendiente; doble complete rechazado |
| Firebase | `firebase emulators:exec ... 'npm test'` | 106/106, 0 fallos/omitidas |
| Macrobenchmark | `:benchmark:connectedLocalBenchmarkAndroidTest` | 4/4 pruebas; 10 muestras escalares y frames aplanados por serie; sin ANR/OOM; StrictMode se controla dentro de cámara, pipeline y lista |
| Empaquetado | APK/AAB local y cloud release | `BUILD SUCCESSFUL`; cloud 41.516.430 bytes y local 38.269.832 bytes, ambos AAB sin firma productiva y solo para validación |
| Play assets | `ruby scripts/verify-play-assets.rb` + inspección visual de las 8 imágenes | Ficha 12/67/1988/367; formato/dimensiones válidos y contenido `[DEMO]` sintético. El script no inspecciona píxeles |
| Secretos/publicación | `ruby scripts/scan-repository-secrets.rb` y staging cerrado | Sin credenciales de alta confianza ni archivos de clave; UTP/logcat crudos no se publican porque pueden contener rutas del host |
| Release | contratos y manifiesto APK | Bucket obligatorio; cinco metadatos Analytics/Crashlytics presentes una vez y en `false` |
| Workflow | `actionlint` | Verde; todas las Actions externas están fijadas a un SHA completo con comentario de versión |
| Dependencias | `npm audit --audit-level=high` y Dependency Review | Functions: 0 altos/críticos y 10 moderados transitivos; deltas de PR Android/Gradle se bloquean desde severidad alta. La submission del grafo habilita alertas Dependabot, no un escaneo histórico local |

Los AAB producidos en esta corrida no son candidatos de publicación: no se firmaron con la clave de
carga registrada ni contienen la configuración Firebase productiva. Los contratos de producción sí
se probaron aparte con un keystore y valores completamente sintéticos fuera del repositorio.

## Corpus dorado

Cada caso usa tres recursos separados:

1. especificación y ruta de imagen sintética en `golden-corpus/cases`;
2. bloques/posiciones OCR esperados en `golden-corpus/expected-ocr`;
3. resultado cerrado en `golden-corpus/expected-results`.

La matriz cubre factura limpia, inclinada, borrosa, descripción multilínea, sin IGV y formatos
decimales regionales. El generador aplica skew y blur reales a los píxeles y las pruebas verifican
que las matrices difieren. El reporte mide coincidencia de cabecera, líneas, totales, confianza y
warnings contra esos contratos. No afirma que el JVM haya alimentado los PNG a ML Kit: la prueba
pura comienza en el OCR esperado, mientras el smoke Android cubre la integración OCR por separado.

## Medición p95 diagnóstica en AVD

Origen crudo:
`benchmark/build/outputs/connected_android_test_additional_output/localBenchmark/connected/Pixel_10a(AVD) - 17/com.facturastock.app.benchmark-benchmarkData.json`.
El cálculo usa nearest-rank y 10 muestras. El AVD sirve para diagnóstico, no para aprobar el Pixel 6a
físico.

| Métrica | p95 | Presupuesto | Estado AVD |
| --- | ---: | ---: | --- |
| Decode bitmap | 87,932 ms | 250 ms | CUMPLE |
| Render/JPEG | 195,882 ms | 900 ms | CUMPLE |
| Escritura privada | 1,362 ms | 150 ms | CUMPLE |
| Preprocesamiento | 139,197 ms | 1.500 ms | CUMPLE |
| OCR ML Kit | 366,115 ms | 3.000 ms | CUMPLE |
| Parser | 210,898 ms | 250 ms | CUMPLE |
| Sync 100 operaciones | 1,551 ms | 500 ms | CUMPLE |
| Enlace de cámara | 26,651 ms | 500 ms | CUMPLE |
| Captura cámara virtual | 3.066,873 ms | 2.000 ms | **NO CUMPLE** |
| Primer frame cámara | 659,588 ms | 1.500 ms | CUMPLE |
| Copia JPEG | 6,601 ms | 150 ms | CUMPLE |
| Inicio frío | 368,730 ms | 1.200 ms | CUMPLE |
| Lista 100, duración de frame | 61,378 ms | 16,67 ms | **NO CUMPLE** |
| Lista 100, frame overrun | 67,366 ms | 0 ms | **NO CUMPLE** |
| Memoria pipeline, RSS anon + file emparejado | 311.008 KiB ≈ 303,7 MiB | 384 MiB | CUMPLE |

La cámara `virtualscene` puede distorsionar la captura y queda marcada como diagnóstica. El rojo de
la lista no se descarta por ese motivo. La serie se repitió después de optimizar: el parser bajó de
409,251 ms en la primera medición de esta auditoría a 210,898 ms y ya cumple el límite de 250 ms.
Antes de aceptar Prompt 47 se debe continuar la investigación de la lista y ejecutar 30 muestras en
el Pixel 6a físico.

El campo global `context.compilationMode=run-from-apk` del JSON corresponde al APK runner
self-instrumented (`com.facturastock.app.benchmark`), no al paquete medido. No se reescribió ese
valor. El target `com.facturastock.app` exige ahora explícitamente
`CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)`: el APK contiene
`assets/dexopt/baseline.prof` y `.profm`, cada uno de los cuatro logs registra
`RESULT_INSTALL_SUCCESS`, y ART informa `actualCompilerFilter=speed-profile, status=PERFORMED` para
la app objetivo. Esas tres pruebas acreditan el perfil aplicado; el campo del runner no lo hace.

## Cambios relevantes aplicados

- Corpus dorado separado, transformaciones de imagen y reporte por fixture.
- Pruebas sin reloj real para configuración DataStore.
- Migración v19→v20 enlazada a repositorios reales y continuidad de outbox.
- Recuperación directa `UNAUTHENTICATED` → recuperar sesión → un reintento, con siete pruebas.
- Cámara bloqueada hasta `CameraX` listo; scroll/reflujo, live regions, roles y etiquetas accesibles.
- Parser sin segunda extracción de cabecera, átomos geométricos cacheados y validación lineal de
  auditoría; `VisualRow` mantiene su unión geométrica incremental y evita listas/cajas temporales.
  Las regresiones comparan el resultado completo, bounding boxes, evidencia y las 38 líneas.
- Formato monetario cacheado por hilo; métricas de tarjeta en un solo `StaticLayout`, copias estáticas
  por pantalla e iconos compartidos; prueba de 100 líneas con fuente 200 % y reciclado profundo.
- Baseline Profile ampliado y requerido explícitamente para las cuatro series de Macrobenchmark.
- StrictMode se restaura en `onStop`; si AndroidX envía antes su `DROP_SHADER_CACHE` entre muestras,
  solo se excluye el frame exacto `ProfileInstallReceiver.onReceive`. Cámara/pipeline/lista
  verifican UI y log por PID, y cualquier otro origen hace fallar el recorrido. Los tres logs
  finales contienen cero entradas `E FacturaStockStrictMode`; inicio frío no instala esa política.
- Contratos de Storage bucket y metadatos de telemetría release.
- Todas las Actions externas del workflow quedaron fijadas a commits SHA-1 completos; el contrato
  release rechaza volver a una etiqueta mutable o quitar el comentario de versión.
- Descubrimiento correcto del nombre AndroidX `*benchmarkData.json`, mínimo de 10 muestras y RSS
  anon + file emparejado por iteración en CI.
- Documentación de release actualizada para Room 20 y migraciones 15–20.

## Trazabilidad de la corrida

El snapshot entregado no contiene directorio `.git`; por eso no existe un commit SHA verificable y
no se inventa uno. Los hashes reproducibles de los inputs y resultados principales son:

| Recurso | SHA-256 |
| --- | --- |
| JSON Macrobenchmark final | `fb55144b56c08eeadd23bdd65883de4d6fef636cc462315f804dd0a25df7a97e` |
| JUnit Macrobenchmark final | `5f338a6b9f47fc40457316b1b281fd0d1e8801a872c95bea40ed6f3bc0d21ba8` |
| Reporte del corpus dorado | `46c7a40121f23671b2629d4d3bac6750b510eedf4d290643b5f4a5d55ba34587` |
| `functions/package-lock.json` | `ea9fef115d85b3c4e8f2d0460b0f729d6425b417e17b5fb46c482c64a7b8e586` |
| `gradle/libs.versions.toml` | `c55c4e12773890a3ee917a055a84a9b444f4f6e7d0940aa8b8bd1a8dba36d84e` |
| AAB cloud de validación, sin firma | `7bc6ade9a1f3569b94d5d53fc4d9682239de08021a640f9624caea0f18c915f8` |
| AAB local de validación, sin firma | `7904b449bd7f6a348fbd806b9e74d597dbb0580d61ea67cdb20d612951164606` |

## Pendientes no simulados

1. Rendimiento y TalkBack/fuente 200 % en Pixel 6a físico.
2. Piloto humano de 21 pasos, incluida cámara real y factura de 38 líneas.
3. Clave/certificado de carga, proyecto/bucket Firebase y URLs/identidad legales reales.
4. AAB productivo instalado desde la pista interna de Play.
5. Verificación independiente: una persona usuaria con el manual y un desarrollador nuevo con el
   README.
6. Diez advisories moderados de npm. La corrección forzada propuesta degrada `firebase-tools` y no
   se aplicó; se deben actualizar cuando el árbol oficial publique una resolución compatible.

Decisión vinculada: [`../ACEPTACION_V1.md`](../ACEPTACION_V1.md) mantiene la versión 1.0 en
**NO CUMPLE** hasta cerrar esos gates.
