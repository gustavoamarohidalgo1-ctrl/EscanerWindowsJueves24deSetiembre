# Auditoría Fase B — Cámara y OCR local (prompts 11–17)

Fecha: 21 de agosto de 2026 (`America/Lima`). Revisión **de código, no de compilación**: `java -version`
responde «Unable to locate a Java Runtime», así que ningún criterio que exija Gradle, emulador o
dispositivo se pudo ejecutar aquí. Continúa la [auditoría de Fase A](2026-08-21-fase-a-auditoria.md).

## Resultado por prompt

| # | Prompt | Estado | Base de la conclusión |
| --- | --- | ---: | --- |
| 11 | Elegir cámara o imagen sin permisos excesivos | CUMPLE | `SourceRoute.kt` usa `ActivityResultContracts.PickVisualMedia` con `ImageOnly` (sin permiso de almacenamiento en ninguna API) y pide `CAMERA` con `RequestPermission()` **solo** al pulsar cámara, saltando el diálogo si `ContextCompat.checkSelfPermission` ya la concede. El manifiesto principal declara únicamente `CAMERA`: no hay ubicación, contactos, audio ni administración de archivos. `Action.PickCancelled` conserva el borrador; `InvalidImageReason` cubre formato, tamaño, corrupción, ausencia y disco lleno. Pruebas: `SourceViewModelTest` (13) con concedido, denegado, inválido y cancelado |
| 12 | CameraX para facturas | CUMPLE tras la corrección | `CameraPreviewSection.kt` liga `Preview` + `ImageCapture` a la cámara trasera con `unbindAll()` antes de ligar y en `DisposableEffect.onDispose`; resolución acotada a 1440×2560 con `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` (no la máxima del sensor); `targetRotation` desde `view.display.rotation` y `rotationDegrees` del `ImageProxy`; enfoque táctil con `FLAG_AF or FLAG_AE`; flash AUTO/ON/OFF; obturador deshabilitado durante la captura; `CameraErrorKind` con reintento e importación. La fuga de cámara **no tenía prueba**: se añadió una (ver abajo) |
| 13 | Imágenes privadas y limpieza de metadatos | CUMPLE | `LocalDraftImageImporter.kt`: temporal `import-*.tmp` en el directorio privado → validación → limpieza de metadatos → `Files.move(…, ATOMIC_MOVE)` con respaldo `renameTo` y copia; SHA-256 y tamaño se calculan **sobre el archivo ya limpio** en una sola pasada. Nombre UUID, nunca `MediaStore`. `ImportDraftImageUseCase` borra el archivo de la página reemplazada solo después de validar y persistir la nueva, «nunca otro, en mejor esfuerzo». `StaleImportCleanup.kt` solo toca la raíz de `filesDir` con el prefijo temporal y una antigüedad mínima de 1 hora: «jamás se entra a subdirectorios ni se toca el directorio `draft_images`» |
| 14 | Vista previa, recorte, giro y varias páginas | CUMPLE | `PreviewContract.kt` tiene las siete acciones exigidas más arrastre de esquinas de recorte; el recorte se guarda en fracciones normalizadas (permyriad 0..10000), así que no puede salirse del archivo por construcción; `PreviewScreen.kt:249` da zoom `coerceIn(1f, 5f)` con desplazamiento acotado. El original nunca se sobrescribe (`clearOcrVersionsNeverRemovesTheOriginal`). Giros: `ImageCropTest` «rotating 90 clockwise four times restores the original crop» y `DraftImageTransformUseCasesTest` «rotating four times restores the original rotation and crop». El OCR no arranca antes de `ProcessClicked`: `PreviewViewModelTest` «two process clicks start only one analysis and emit one navigation» y «back cancels active quality analysis before OCR starts». Persistencia ante muerte del proceso: `NavigationRecreationTest` |
| 15 | Calidad y preprocesado sin agotar memoria | CUMPLE | `ImageQualityAnalyzer` e `InvoiceImagePreprocessor` son interfaces de dominio con implementación en `data/files`. `ImageQuality.kt` reporta solo enteros (permille, décimas de grado, luminancia 0..255) y nombra las advertencias `Possible*`, con KDoc explícito: «no certifica que la captura sea defectuosa». Decodificación con `inJustDecodeBounds` + `inSampleSize`, `recycleSafely()`, `withContext(default/io)` y `ensureActive()` repetido. Compresión documentada en el KDoc: grises, contraste 112 % y JPEG 88, con `OCR_MAX_SIDE_PX = 2048`. Pruebas instrumentadas: `eightThousandPixelImageIsSampledAndDoesNotEscapeAsOutOfMemory`, `preprocessRotatesAndWritesGrayscaleCopyWithoutTouchingOriginal`, `clearWaitingForAnActiveBatchRemainsCancelable`, y seis casos de luz, giro e inclinación en `LocalImageQualityAnalyzerTest` |
| 16 | ML Kit OCR completamente local | CUMPLE | `MlKitInvoiceTextRecognizer` obtiene **un** cliente por lote, lo reutiliza para todas las páginas en orden (`mapIndexed`) y lo cierra en `finally`; `TaskAwait.kt` convierte el Task API en `suspend` cancelable de modo que un resultado tardío no puede reanudar una corrutina cancelada. El modelo propio (`InvoiceTextDocument` → página → bloque → línea → elemento) valida la geometría dentro de la página en `init` vía `requireInside`, así que **no se puede construir** una coordenada fuera de la página. `verifyLocalOcrConfiguration` obliga al artefacto latino integrado, prohíbe la variante descargable de Play Services, prohíbe cualquier `AIza…` y falla si un `import com.google.mlkit.` aparece fuera de `data/ocr/`. Prueba instrumentada `bundledLatinModelRecognizesSyntheticInvoiceWithoutNetworkPermission` (la variante `local` elimina `INTERNET` del manifiesto, que es un modo avión más estricto) |
| 17 | Orquestar OCR, progreso y recuperación | CUMPLE | `RunInvoiceOcrUseCase` valida sesión y páginas, reclama el trabajo por CAS (`beginOcrRun(draftId, runId)` bajo `NonCancellable`), verifica las imágenes de origen antes y después del reconocimiento, publica el snapshot de forma atómica y cierra en `OCR_READY`; un snapshot ya publicado se reproduce sin volver a invocar el motor. `InvoiceOcrStage` expone etapas reales (`PREPARING`, `READING`, `MERGING_PAGES`) sin porcentajes inventados. `RetryDraftOcrUseCase` usa `resetInterruptedOcr(draftId, expectedRunId)` para que «una acción atrasada no pueda cancelar otro intento ni degradar un borrador avanzado o confirmado». Inicio ofrece reanudar (`HomeContract`). `RunInvoiceOcrUseCaseTest` (14) incluye «two concurrent invocations let only the CAS winner execute OCR», «retry replaces the prior snapshot instead of appending another one» y «process restart recovers an interrupted token and starts a fresh idempotent run» |

## Lo que faltaba y se aplicó

El único hueco real de la fase era de cobertura, no de implementación. El primer criterio del
Prompt 12 —«Entrar y salir repetidamente de la cámara no filtra el dispositivo»— quedaba respaldado
solo por lectura del código: `CaptureScreenTest` usa a propósito un visor falso para no depender de
una cámara real, y ninguna prueba instrumentada ejercitaba CameraX de verdad. La única referencia no
productiva a `buildInvoiceImageCapture` estaba en el Macrobenchmark.

Se añadió
[`app/src/androidTest/java/com/facturastock/app/feature/capture/camera/CameraPreviewSectionLifecycleTest.kt`](../../app/src/androidTest/java/com/facturastock/app/feature/capture/camera/CameraPreviewSectionLifecycleTest.kt):
entra y sale del visor tres veces y, en cada salida, afirma sobre el hilo principal que
`ProcessCameraProvider.isBound` es falso para **todas** las capturas entregadas hasta entonces —la
señal directa de fuga, sin depender de tiempos—. Además comprueba que cada entrada rehace el enlace
con un `ImageCapture` nuevo en vez de reutilizar el anterior. En un dispositivo sin cámara la prueba
se **omite** (`Assume`), porque ahí el camino correcto es `onCameraError`, ya cubierto por
`cameraErrorStateOffersRetryAndPickingAnImage`.

La prueba está escrita, **no ejecutada**: requiere emulador o dispositivo y Gradle, y ninguno de los
dos existe en este entorno.

## Decisiones sostenidas sin cambio

1. **La resolución no es la máxima del sensor y así debe quedar.** El Prompt 12 pide «resolución
   suficiente sin usar siempre la máxima»: 1440×2560 con `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` es
   exactamente eso, y subirla dispararía el tamaño del JPEG (el tope de la política de captura es
   15 MiB) y el coste de decodificación del OCR.
2. **Las advertencias de calidad siguen llamándose `Possible*`.** El Prompt 15 prohíbe prometer
   certeza; renombrarlas a algo afirmativo sería incumplir el criterio, no mejorarlo.

## No verificable en este entorno

Todo lo que exige Gradle o dispositivo: `assembleDebug`, `testDebugUnitTest`, `ciStaticAnalysis`, los
diez verificadores (incluido `verifyLocalOcrConfiguration`, citado arriba por lectura), Lint, Kover y
la instrumentación completa —incluida la prueba nueva—. La evidencia previa en disco que respalda
estos puntos está citada en [`../ACEPTACION_V1.md`](../ACEPTACION_V1.md); no la produjo esta sesión.

