package com.facturastock.app.performance

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.os.StrictMode
import android.os.Trace
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button as AndroidButton
import android.widget.LinearLayout
import android.widget.TextView as AndroidTextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.R
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.demo.DemoInvoiceImageGenerator
import com.facturastock.app.data.demo.DemoInvoiceFixture
import com.facturastock.app.data.files.LocalInvoiceImagePreprocessor
import com.facturastock.app.data.ocr.MlKitInvoiceTextRecognizer
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.normalization.InvoiceParseContext
import com.facturastock.app.domain.normalization.InvoiceParser
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.PendingBackupOperation
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCase
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract
import com.facturastock.app.feature.linereview.InvoiceLineReviewScreen
import com.facturastock.app.feature.capture.camera.buildInvoiceImageCapture
import com.facturastock.app.feature.capture.readJpegBytes
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * Superficie exclusiva del build `benchmark`; no forma parte de debug/release distribuibles.
 * Ejecuta los mismos componentes de bitmap, ML Kit, parser, lista y sync que se perfilan desde
 * Macrobenchmark, siempre con la factura sintética y sin identificadores fiscales reales.
 */
class PerformanceBenchmarkActivity : ComponentActivity() {
    private val strictModeViolations = mutableIntStateOf(0)
    private val strictModeViolationNames = mutableStateListOf<String>()
    private lateinit var previousThreadPolicy: StrictMode.ThreadPolicy
    private lateinit var previousVmPolicy: StrictMode.VmPolicy
    private var strictModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se instala antes de la primera composición: también cubre creación de lista y enlace de
        // cámara, no solo los efectos lanzados después del primer frame.
        enableStrictMode()
        setContent {
            FacturaStockTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    when (intent.getStringExtra(EXTRA_SCENARIO)) {
                        SCENARIO_PIPELINE -> PipelineBenchmarkScreen(
                            strictModeViolations = strictModeViolations.intValue,
                            strictModeViolationNames = strictModeViolationNames,
                        )
                        SCENARIO_CAMERA -> CameraBenchmarkScreen(
                            strictModeViolations = strictModeViolations.intValue,
                            strictModeViolationNames = strictModeViolationNames,
                        )
                        else -> LineListBenchmarkScreen(
                            strictModeViolations = strictModeViolations.intValue,
                            strictModeViolationNames = strictModeViolationNames,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        disableStrictMode()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        enableStrictMode()
    }

    override fun onStop() {
        // No dejes la política de esta actividad instalada cuando otro componente tome el proceso.
        disableStrictMode()
        super.onStop()
    }

    private fun enableStrictMode() {
        if (strictModeEnabled) return
        previousThreadPolicy = StrictMode.getThreadPolicy()
        previousVmPolicy = StrictMode.getVmPolicy()
        val listener = StrictMode.OnThreadViolationListener { violation ->
            recordStrictModeViolation(violation)
        }
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyListener(Runnable::run, listener)
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyListener(Runnable::run) { violation ->
                    recordStrictModeViolation(violation)
                }
                .build(),
        )
        strictModeEnabled = true
    }

    private fun disableStrictMode() {
        if (!strictModeEnabled) return
        StrictMode.setThreadPolicy(previousThreadPolicy)
        StrictMode.setVmPolicy(previousVmPolicy)
        strictModeEnabled = false
    }

    private fun recordStrictModeViolation(violation: Throwable) {
        // Macrobenchmark ejecuta DROP_SHADER_CACHE entre muestras mediante este receiver. AndroidX
        // puede enviar el broadcast antes de onStop, aunque ya terminó la sección medida. La
        // exclusión solo acepta ese frame exacto; cualquier otra lectura/escritura/red sigue
        // incrementando el contador, publicando el tag rojo y haciendo fallar la prueba.
        if (violation.isProfileInstallerBenchmarkSetupViolation()) return
        val name = violation.javaClass.simpleName
        Log.e(STRICT_MODE_LOG_TAG, "StrictMode $name", violation)
        val update = {
            strictModeViolations.intValue++
            if (name !in strictModeViolationNames && strictModeViolationNames.size < 5) {
                strictModeViolationNames += name
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            runOnUiThread { update() }
        }
    }

    private fun Throwable.isProfileInstallerBenchmarkSetupViolation(): Boolean =
        stackTrace.any { frame ->
            frame.className == PROFILE_INSTALL_RECEIVER_CLASS && frame.methodName == "onReceive"
        }

    companion object {
        const val EXTRA_SCENARIO = "performance.scenario"
        const val SCENARIO_LINES = "lines"
        const val SCENARIO_PIPELINE = "pipeline"
        const val SCENARIO_CAMERA = "camera"
        const val TAG_PIPELINE_READY = "performance_pipeline_ready"
        const val TAG_LIST_PRESENTATIONS_READY = "performance_list_presentations_ready"
        const val TAG_STRICT_MODE_CLEAN = "performance_strict_mode_clean"
        const val TAG_STRICT_MODE_VIOLATION = "performance_strict_mode_violation"
        private const val PROFILE_INSTALL_RECEIVER_CLASS =
            "androidx.profileinstaller.ProfileInstallReceiver"
        private const val STRICT_MODE_LOG_TAG = "FacturaStockStrictMode"
    }
}

@Composable
private fun LineListBenchmarkScreen(
    strictModeViolations: Int,
    strictModeViolationNames: List<String>,
) {
    val benchmarkState = remember { hundredLineState() }
    var requestedScrollLineId by remember { mutableStateOf<LineId?>(null) }
    var handledScrollLineId by remember { mutableStateOf<LineId?>(null) }
    var cardPresentationsReady by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> listBenchmarkControls(context) },
                update = { status ->
                    status.findViewById<AndroidTextView>(
                        R.id.performance_list_strict_mode_clean,
                    ).visibility = if (strictModeViolations == 0) View.VISIBLE else View.GONE
                    status.findViewById<AndroidTextView>(
                        R.id.performance_list_strict_mode_violation,
                    ).apply {
                        visibility = if (strictModeViolations > 0) View.VISIBLE else View.GONE
                        text = "StrictMode $strictModeViolations: ${strictModeViolationNames.joinToString()}"
                    }
                    status.findViewById<AndroidButton>(R.id.performance_list_to_first)
                        .setOnClickListener {
                            Log.d(BENCHMARK_LOG_TAG, "list request line 1")
                            handledScrollLineId = null
                            requestedScrollLineId = benchmarkState.lines.first().lineId
                        }
                    status.findViewById<AndroidButton>(R.id.performance_list_to_last)
                        .setOnClickListener {
                            Log.d(BENCHMARK_LOG_TAG, "list request line 100")
                            handledScrollLineId = null
                            requestedScrollLineId = benchmarkState.lines.last().lineId
                        }
                    status.findViewById<AndroidTextView>(
                        R.id.performance_list_first_settled,
                    ).visibility = if (handledScrollLineId == benchmarkState.lines.first().lineId) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    status.findViewById<AndroidTextView>(
                        R.id.performance_list_last_settled,
                    ).visibility = if (handledScrollLineId == benchmarkState.lines.last().lineId) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            InvoiceLineReviewScreen(
                state = benchmarkState,
                onAction = {},
                modifier = Modifier.weight(1f),
                requestedScrollLineId = requestedScrollLineId,
                onRequestedScrollHandled = {
                    handledScrollLineId = requestedScrollLineId
                    Log.d(BENCHMARK_LOG_TAG, "list settled ${requestedScrollLineId?.value}")
                    requestedScrollLineId = null
                },
                onCardPresentationsReady = { cardPresentationsReady = true },
            )
        }
        if (cardPresentationsReady) {
            Text(
                text = "Presentations ready",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .testTag(PerformanceBenchmarkActivity.TAG_LIST_PRESENTATIONS_READY),
            )
        }
    }
}

private const val BENCHMARK_LOG_TAG = "FacturaStockBenchmark"

private fun listBenchmarkControls(context: Context): LinearLayout = LinearLayout(context).apply {
    val safeTopPadding = (48 * context.resources.displayMetrics.density).toInt()
    orientation = LinearLayout.VERTICAL
    // La actividad benchmark es edge-to-edge; evita que la barra de estado intercepte los CTAs.
    setPadding(0, safeTopPadding, 0, 0)
    addView(AndroidTextView(context).apply {
        id = R.id.performance_list_strict_mode_clean
        text = "StrictMode clean"
    })
    addView(AndroidTextView(context).apply {
        id = R.id.performance_list_strict_mode_violation
        visibility = View.GONE
    })
    addView(AndroidButton(context).apply {
        id = R.id.performance_list_to_first
        text = "Animate to line 1"
    })
    addView(AndroidButton(context).apply {
        id = R.id.performance_list_to_last
        text = "Animate to line 100"
    })
    addView(AndroidTextView(context).apply {
        id = R.id.performance_list_first_settled
        text = "Line 1 settled"
        visibility = View.GONE
    })
    addView(AndroidTextView(context).apply {
        id = R.id.performance_list_last_settled
        text = "Line 100 settled"
        visibility = View.GONE
    })
}

@Composable
private fun PipelineBenchmarkScreen(
    strictModeViolations: Int,
    strictModeViolationNames: List<String>,
) {
    val context = LocalContext.current.applicationContext
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            result = withContext(dispatcher) { runSyntheticPipeline(context) }
        } finally {
            dispatcher.close()
            executor.shutdown()
        }
        // Da tiempo a que un listener de StrictMode ya encolado publique la infracción.
        delay(250)
    }
    Column(Modifier.padding(16.dp)) {
        Text(result ?: "Procesando fixture sintético")
        if (result != null) {
            Text("Pipeline ready", Modifier.testTag(PerformanceBenchmarkActivity.TAG_PIPELINE_READY))
        }
        if (result != null && strictModeViolations == 0) {
            Text("StrictMode clean", Modifier.testTag(PerformanceBenchmarkActivity.TAG_STRICT_MODE_CLEAN))
        } else if (result != null) {
            Text(
                "StrictMode $strictModeViolations: ${strictModeViolationNames.joinToString()}",
                Modifier.testTag(PerformanceBenchmarkActivity.TAG_STRICT_MODE_VIOLATION),
            )
        }
    }
}

@Composable
private fun CameraBenchmarkScreen(
    strictModeViolations: Int,
    strictModeViolationNames: List<String>,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val jpegCopyExecutor = remember(lifecycleOwner) { Executors.newSingleThreadExecutor() }
    val captureTraceOpen = remember(lifecycleOwner) { AtomicBoolean(false) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var streaming by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedBytes by remember { mutableStateOf<Int?>(null) }
    var cameraFailure by remember { mutableStateOf<String?>(null) }

    fun finishCaptureTrace() {
        if (captureTraceOpen.compareAndSet(true, false)) {
            Trace.endAsyncSection(TRACE_CAMERA_CAPTURE, CAMERA_CAPTURE_TRACE_COOKIE)
        }
    }

    val captureFrame = {
        val capture = imageCapture
        if (capture != null && captureTraceOpen.compareAndSet(false, true)) {
            capturedBytes = null
            cameraFailure = null
            capture.targetRotation = Surface.ROTATION_0
            Trace.beginAsyncSection(
                TRACE_CAMERA_CAPTURE,
                CAMERA_CAPTURE_TRACE_COOKIE,
            )
            try {
                capture.takePicture(
                    jpegCopyExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: androidx.camera.core.ImageProxy) {
                            val byteCount = try {
                                measured(TRACE_CAMERA_JPEG_COPY) {
                                    imageProxy.readJpegBytes().size
                                }
                            } catch (failure: Throwable) {
                                mainExecutor.execute {
                                    cameraFailure = failure.javaClass.simpleName
                                }
                                null
                            } finally {
                                imageProxy.close()
                                finishCaptureTrace()
                            }
                            if (byteCount != null) {
                                mainExecutor.execute { capturedBytes = byteCount }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            finishCaptureTrace()
                            mainExecutor.execute {
                                cameraFailure = exception.javaClass.simpleName
                            }
                        }
                    },
                )
            } catch (failure: RuntimeException) {
                finishCaptureTrace()
                cameraFailure = failure.javaClass.simpleName
            }
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val disposed = AtomicBoolean(false)
        val firstFrameTraceOpen = AtomicBoolean(true)
        Trace.beginAsyncSection(TRACE_CAMERA_FIRST_FRAME, CAMERA_TRACE_COOKIE)
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            if (
                state == PreviewView.StreamState.STREAMING &&
                firstFrameTraceOpen.compareAndSet(true, false)
            ) {
                Trace.endAsyncSection(TRACE_CAMERA_FIRST_FRAME, CAMERA_TRACE_COOKIE)
                streaming = true
            }
        }
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (!disposed.get()) {
                    try {
                        imageCapture = measured(TRACE_CAMERA_BIND) {
                            val provider = providerFuture.get()
                            provider.unbindAll()
                            val capture = buildInvoiceImageCapture()
                            val preview = Preview.Builder().build().also { useCase ->
                                useCase.surfaceProvider = previewView.surfaceProvider
                            }
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                            capture
                        }
                    } catch (failure: Exception) {
                        cameraFailure = failure.javaClass.simpleName
                    }
                }
            },
            mainExecutor,
        )
        onDispose {
            disposed.set(true)
            previewView.previewStreamState.removeObserver(streamObserver)
            if (firstFrameTraceOpen.compareAndSet(true, false)) {
                Trace.endAsyncSection(TRACE_CAMERA_FIRST_FRAME, CAMERA_TRACE_COOKIE)
            }
            finishCaptureTrace()
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            jpegCopyExecutor.shutdownNow()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // UIAutomator puede mantener una caché obsoleta de nodos Compose cuando una TextureView
        // cambia por debajo. Estos controles son View nativos y tienen IDs de recurso estables;
        // solo pertenecen al build benchmark y no cambian el pipeline que se está midiendo.
        AndroidView(
            factory = { viewContext -> cameraBenchmarkControls(viewContext) },
            update = { controls ->
                controls.findViewById<AndroidTextView>(R.id.performance_camera_ready).visibility =
                    if (streaming) View.VISIBLE else View.GONE
                controls.findViewById<AndroidButton>(R.id.performance_camera_capture).apply {
                    visibility = if (streaming && imageCapture != null) View.VISIBLE else View.GONE
                    setOnClickListener { captureFrame() }
                }
                controls.findViewById<AndroidTextView>(R.id.performance_camera_capture_ready).apply {
                    val bytes = capturedBytes
                    visibility = if (bytes != null) View.VISIBLE else View.GONE
                    text = bytes?.let { "Camera capture ready: $it bytes" }.orEmpty()
                }
                controls.findViewById<AndroidTextView>(R.id.performance_camera_failure).apply {
                    visibility = if (cameraFailure != null) View.VISIBLE else View.GONE
                    text = cameraFailure?.let { "Camera failed: $it" }.orEmpty()
                }
                controls.findViewById<AndroidTextView>(
                    R.id.performance_camera_strict_mode_clean,
                ).visibility = if (capturedBytes != null && strictModeViolations == 0) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                controls.findViewById<AndroidTextView>(
                    R.id.performance_camera_strict_mode_violation,
                ).apply {
                    visibility = if (capturedBytes != null && strictModeViolations > 0) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    text = "StrictMode $strictModeViolations: ${strictModeViolationNames.joinToString()}"
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun cameraBenchmarkControls(context: Context): LinearLayout {
    val padding = (16 * context.resources.displayMetrics.density).toInt()
    fun statusText(id: Int, text: String) = AndroidTextView(context).apply {
        this.id = id
        this.text = text
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
        visibility = View.GONE
    }
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(Color.TRANSPARENT)
        addView(statusText(R.id.performance_camera_ready, "Camera ready"))
        addView(
            AndroidButton(context).apply {
                id = R.id.performance_camera_capture
                text = "Capture synthetic frame"
                visibility = View.GONE
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(statusText(R.id.performance_camera_capture_ready, ""))
        addView(statusText(R.id.performance_camera_failure, ""))
        addView(statusText(R.id.performance_camera_strict_mode_clean, "StrictMode clean"))
        addView(statusText(R.id.performance_camera_strict_mode_violation, ""))
    }
}

private suspend fun runSyntheticPipeline(context: Context): String {
    val filesDir = context.filesDir
    val jpeg = measured(TRACE_BITMAP_RENDER) { DemoInvoiceImageGenerator.jpegBytes(90) }
    val decodedSize = measured(TRACE_BITMAP_DECODE) {
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            bitmap.width to bitmap.height
        } finally {
            bitmap.recycle()
        }
    }
    val relativePath = "benchmark/demo-invoice.jpg"
    val imageFile = File(filesDir, relativePath)
    measured(TRACE_BITMAP_WRITE) {
        check(imageFile.parentFile?.mkdirs() == true || imageFile.parentFile?.isDirectory == true)
        imageFile.outputStream().use { output -> output.write(jpeg) }
    }
    val imageId = ImageId.from(UUID.fromString("71000000-0000-4000-8000-000000000001"))
    val preparedPages = measuredSuspend(TRACE_IMAGE_PREPROCESS) {
        LocalInvoiceImagePreprocessor(
            context = context,
            uuidGenerator = UuidGenerator { BENCHMARK_PREPROCESS_RUN_UUID },
            dispatcherProvider = DefaultDispatcherProvider(),
        ).preprocess(
            draftId = BENCHMARK_DRAFT_ID,
            images = listOf(
                InvoiceImage(
                    imageId = imageId,
                    draftId = BENCHMARK_DRAFT_ID,
                    businessId = BENCHMARK_BUSINESS_ID,
                    pageIndex = 0,
                    filePath = relativePath,
                    sha256 = "a".repeat(64),
                    mimeType = "image/jpeg",
                    widthPx = decodedSize.first,
                    heightPx = decodedSize.second,
                    fileSizeBytes = imageFile.length(),
                    createdAt = BENCHMARK_NOW,
                ),
            ),
        )
    }
    val ocrPage = preparedPages.single()
    check(maxOf(ocrPage.widthPx, ocrPage.heightPx) <= LocalInvoiceImagePreprocessor.OCR_MAX_SIDE_PX)
    val document = measuredSuspend(TRACE_OCR) {
        MlKitInvoiceTextRecognizer(
            context = context,
            dispatcherProvider = DefaultDispatcherProvider(),
        ).recognize(listOf(ocrPage))
    }
    val parsedLines = measured(TRACE_PARSER) {
        InvoiceParser().parse(
            snapshot = InvoiceOcrSnapshot(
                draftId = BENCHMARK_DRAFT_ID,
                runId = BENCHMARK_RUN_ID,
                completedAt = BENCHMARK_NOW,
                document = document,
            ),
            context = InvoiceParseContext(
                parserVersion = 1,
                contextFingerprint = "b".repeat(64),
                buyerRuc = null,
                fallbackCurrency = CurrencyCode.of("PEN"),
                referenceIgvRate = AppConfiguration.DEFAULT_TAX_RATE,
            ),
        ).lineItems.items.size
    }
    check(parsedLines == DemoInvoiceFixture.EXPECTED_LINE_COUNT) {
        "El pipeline OCR/parser produjo $parsedLines/${DemoInvoiceFixture.EXPECTED_LINE_COUNT} líneas"
    }
    val synced = measuredSuspend(TRACE_SYNC_100) { runHundredOperationSync() }
    return "${jpeg.size} bytes; OCR ${ocrPage.widthPx}x${ocrPage.heightPx}; " +
        "parser $parsedLines líneas; sync $synced/100"
}

private suspend fun runHundredOperationSync(): Int {
    val repository = BenchmarkOutboxRepository(hundredPendingOperations())
    var claimSequence = 0L
    val processor = ProcessPurchaseBackupOutboxUseCase(
        outbox = repository,
        transport = object : PurchaseBackupTransport {
            override val configured: Boolean = true
            override suspend fun send(envelope: BackupEnvelope): BackupTransportResult =
                BackupTransportResult.Acknowledged("benchmark-receipt", envelope.idempotencyKey)
        },
        clock = AppClock { BENCHMARK_NOW },
        uuidGenerator = UuidGenerator {
            claimSequence++
            UUID(0x7200000000004000L, Long.MIN_VALUE + claimSequence)
        },
    )
    val result = processor(batchLimit = 100, targetCloudBusinessId = BENCHMARK_CLOUD_BUSINESS_ID)
    check(result is com.facturastock.app.domain.usecase.OutboxPassResult.Drained)
    return result.completed
}

private class BenchmarkOutboxRepository(
    operations: List<PendingBackupOperation>,
) : PurchaseBackupOutboxRepository {
    private val pending = operations.toMutableList()
    private val claims = mutableMapOf<String, String>()

    override suspend fun recoverExpiredClaims(now: Instant): Int = 0
    override suspend fun listReady(
        now: Instant,
        limit: Int,
        onlyDocumentPurges: Boolean,
        targetCloudBusinessId: BusinessId?,
    ): List<PendingBackupOperation> =
        pending.take(limit)

    override suspend fun isPurchasePostCompleted(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        targetCloudBusinessId: BusinessId,
    ): Boolean = false

    override suspend fun claim(
        operationId: String,
        claimToken: String,
        claimedAt: Instant,
        leaseUntil: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Boolean = if (pending.any { it.envelope.operationId == operationId }) {
        claims[operationId] = claimToken
        true
    } else {
        false
    }

    override suspend fun complete(
        operationId: String,
        claimToken: String,
        completedAt: Instant,
    ): Boolean = if (claims[operationId] == claimToken) {
        claims.remove(operationId)
        pending.removeAll { it.envelope.operationId == operationId }
        true
    } else {
        false
    }

    override suspend fun fail(
        operationId: String,
        claimToken: String,
        targetStatus: OutboxOperationStatus,
        error: String,
        nextAttemptAt: Instant?,
        failedAt: Instant,
        conflictRemotePurchaseId: String?,
        conflictReceiptId: String?,
    ): Boolean = false

    override fun observeOutboxOperations(businessId: BusinessId): Flow<List<OutboxOperationView>> =
        emptyFlow()

    override suspend fun resolveConflictKeepRemote(
        activeBusinessId: BusinessId,
        operationId: String,
        purchaseId: PurchaseId,
        remotePurchaseId: String?,
        remoteReceiptId: String?,
        actorId: String,
        resolvedAt: Instant,
    ): Boolean = false

    override suspend fun release(
        operationId: String,
        claimToken: String,
        releasedAt: Instant,
    ): Boolean = false

    override suspend fun findNextAttemptAt(
        now: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Instant? = null
    override suspend fun findNextClaimLeaseExpiry(
        now: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Instant? = null
    override suspend fun findOldestOutstandingCreatedAt(
        targetCloudBusinessId: BusinessId,
    ): Instant? = pending
        .filter { it.envelope.targetCloudBusinessId == targetCloudBusinessId }
        .minOfOrNull { it.createdAt ?: Instant.EPOCH }
}

private fun hundredPendingOperations(): List<PendingBackupOperation> = List(100) { index ->
    val suffix = (index + 1).toString().padStart(12, '0')
    PendingBackupOperation(
        envelope = BackupEnvelope(
            operationId = "73000000-0000-4000-8000-$suffix",
            businessId = BENCHMARK_BUSINESS_ID,
            targetCloudBusinessId = BENCHMARK_CLOUD_BUSINESS_ID,
            purchaseId = PurchaseId.from(UUID.fromString("74000000-0000-4000-8000-$suffix")),
            idempotencyKey = "benchmark-sync-$suffix",
            operationType = "SYNC_PURCHASE",
            payloadVersion = 2,
            payload = "{\"synthetic\":true,\"line\":${index + 1}}",
        ),
        attemptCount = 0,
    )
}

private fun hundredLineState(): InvoiceLineReviewContract.State = InvoiceLineReviewContract.State(
    draftId = BENCHMARK_DRAFT_ID,
    isLoading = false,
    lines = List(InvoiceLineReviewContract.MAX_LINES) { index -> benchmarkLine(index) },
    currencyLabel = "S/",
    summary = InvoiceLineReviewContract.Summary(
        lineSum = "S/ 118.00",
        invoiceTotal = "S/ 118.00",
        exactDifference = "S/ 0.00",
    ),
)

private fun benchmarkLine(index: Int): InvoiceLineReviewContract.Line {
    val suffix = (index + 1).toString().padStart(12, '0')
    val values = mapOf(
        InvoiceLineReviewContract.FieldId.DESCRIPTION to "[DEMO] Producto ${index + 1}",
        InvoiceLineReviewContract.FieldId.CODE to "SKU-$suffix",
        InvoiceLineReviewContract.FieldId.QUANTITY to "1",
        InvoiceLineReviewContract.FieldId.UNIT to "NIU",
        InvoiceLineReviewContract.FieldId.UNIT_COST to "1.00",
        InvoiceLineReviewContract.FieldId.DISCOUNT to "0.00",
        InvoiceLineReviewContract.FieldId.IGV to "0.18",
        InvoiceLineReviewContract.FieldId.TOTAL to "1.18",
    )
    return InvoiceLineReviewContract.Line(
        lineId = LineId.from(UUID.fromString("75000000-0000-4000-8000-$suffix")),
        position = index,
        fields = InvoiceLineReviewContract.FieldId.entries.map { fieldId ->
            InvoiceLineReviewContract.Field(
                id = fieldId,
                value = checkNotNull(values[fieldId]),
                origin = InvoiceLineReviewContract.ValueOrigin.OCR,
                confidence = InvoiceLineReviewContract.Confidence.HIGH,
            )
        },
        confidence = InvoiceLineReviewContract.Confidence.HIGH,
        confidencePercent = 98,
        requiresReview = false,
        confirmedByUser = true,
    )
}

private inline fun <T> measured(section: String, block: () -> T): T {
    Trace.beginSection(section)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private suspend inline fun <T> measuredSuspend(
    section: String,
    crossinline block: suspend () -> T,
): T {
    Trace.beginSection(section)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private const val TRACE_BITMAP_RENDER = "fs_bitmap_render_encode"
private const val TRACE_BITMAP_DECODE = "fs_bitmap_decode"
private const val TRACE_BITMAP_WRITE = "fs_bitmap_write"
private const val TRACE_IMAGE_PREPROCESS = "fs_image_preprocess"
private const val TRACE_OCR = "fs_ocr"
private const val TRACE_PARSER = "fs_parser"
private const val TRACE_SYNC_100 = "fs_sync_100"
private const val TRACE_CAMERA_BIND = "fs_camera_bind"
private const val TRACE_CAMERA_FIRST_FRAME = "fs_camera_first_frame"
private const val TRACE_CAMERA_CAPTURE = "fs_camera_capture"
private const val TRACE_CAMERA_JPEG_COPY = "fs_camera_jpeg_copy"
private const val CAMERA_TRACE_COOKIE = 47
private const val CAMERA_CAPTURE_TRACE_COOKIE = 48

private val BENCHMARK_NOW: Instant = Instant.parse("2026-08-20T12:00:00Z")
private val BENCHMARK_DRAFT_ID =
    DraftId.from(UUID.fromString("76000000-0000-4000-8000-000000000001"))
private val BENCHMARK_RUN_ID =
    OcrRunId.from(UUID.fromString("77000000-0000-4000-8000-000000000001"))
private val BENCHMARK_BUSINESS_ID =
    BusinessId.from(UUID.fromString("78000000-0000-4000-8000-000000000001"))
private val BENCHMARK_CLOUD_BUSINESS_ID =
    BusinessId.from(UUID.fromString("78000000-0000-4000-8000-000000000099"))
private val BENCHMARK_PREPROCESS_RUN_UUID =
    UUID.fromString("79000000-0000-4000-8000-000000000001")
