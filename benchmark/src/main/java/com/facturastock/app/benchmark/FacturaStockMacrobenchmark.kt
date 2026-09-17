package com.facturastock.app.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mediciones de aceptación reproducibles. Los resultados crudos de cada iteración se conservan
 * como JSON/Perfetto; el p95 se calcula desde esos valores, nunca desde tiempos escritos a mano.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class FacturaStockMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun isolateTargetStateWithoutLaunchingApp() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val clearOutput = device.executeShellCommand("pm clear $TARGET_PACKAGE")
        check(clearOutput.contains("Success", ignoreCase = true)) {
            "No se pudo limpiar el estado durable del escenario anterior: $clearOutput"
        }
        check(device.executeShellCommand("pidof $TARGET_PACKAGE").trim().isEmpty()) {
            "El proceso target anterior sigue activo antes del escenario"
        }
    }

    @Test
    fun coldStartup() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
            ),
            startupMode = StartupMode.COLD,
            iterations = iterations,
            setupBlock = {
                // Cada muestra recrea el mismo onboarding ya completado sin jobs heredados. La
                // apertura auxiliar usa el extra permitido solo en benchmark/profile y termina
                // su proceso antes de que empiece el intervalo medido.
                StartupJourney.prepare(resetPersistentState = true)
            },
        ) {
            startActivityAndWait()
            StartupJourney.waitForSalesReady(device)
        }
    }

    @Test
    fun cameraFirstFrame() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric(
                sectionName = TRACE_CAMERA_BIND,
                mode = TraceSectionMetric.Mode.First,
                label = "cameraBind",
            ),
            TraceSectionMetric(
                sectionName = TRACE_CAMERA_FIRST_FRAME,
                mode = TraceSectionMetric.Mode.First,
                label = "cameraFirstFrame",
            ),
            TraceSectionMetric(
                sectionName = TRACE_CAMERA_CAPTURE,
                mode = TraceSectionMetric.Mode.First,
                label = "cameraCapture",
            ),
            TraceSectionMetric(
                sectionName = TRACE_CAMERA_JPEG_COPY,
                mode = TraceSectionMetric.Mode.First,
                label = "cameraJpegCopy",
            ),
            FrameTimingMetric(),
        ),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
        // El estado frío aísla cada muestra: en recreaciones WARM la cámara virtual puede cerrar
        // el graph anterior después de que CameraX ya abrió el siguiente, contaminando la muestra.
        startupMode = StartupMode.COLD,
        iterations = iterations,
        setupBlock = {
            device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.CAMERA")
        },
    ) {
        startBenchmarkActivity(SCENARIO_CAMERA)
        // El CTA solo se compone después de que PreviewView publicó STREAMING y CameraX devolvió
        // el ImageCapture. Esperarlo evita depender de un nodo Text no accionable que UIAutomator
        // puede dejar fuera de su caché en una jerarquía híbrida Compose + TextureView.
        assertTrue(
            "La cámara no publicó un primer frame ni quedó lista para capturar",
            device.wait(
                Until.hasObject(By.res(TARGET_PACKAGE, TAG_CAMERA_CAPTURE)),
                PIPELINE_TIMEOUT_MS,
            ),
        )
        checkNotNull(device.findObject(By.res(TARGET_PACKAGE, TAG_CAMERA_CAPTURE))).click()
        assertTrue(
            device.wait(
                Until.hasObject(By.res(TARGET_PACKAGE, TAG_CAMERA_CAPTURE_READY)),
                PIPELINE_TIMEOUT_MS,
            ),
        )
        val violation = device.findObject(
            By.res(TARGET_PACKAGE, TAG_CAMERA_STRICT_MODE_VIOLATION),
        )?.text
        assertTrue(
            violation ?: "StrictMode camera clean tag missing",
            device.hasObject(By.res(TARGET_PACKAGE, TAG_CAMERA_STRICT_MODE_CLEAN)),
        )
        assertNoStrictModeLogViolations()
    }

    @Test
    fun bitmapOcrParserAndSyncPipeline() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            traceMetric(TRACE_BITMAP_RENDER, "bitmapRenderEncode"),
            traceMetric(TRACE_BITMAP_DECODE, "bitmapDecode"),
            traceMetric(TRACE_BITMAP_WRITE, "bitmapWrite"),
            traceMetric(TRACE_IMAGE_PREPROCESS, "imagePreprocess"),
            traceMetric(TRACE_OCR, "ocr"),
            traceMetric(TRACE_PARSER, "parser"),
            traceMetric(TRACE_SYNC_100, "sync100"),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
        startupMode = StartupMode.WARM,
        iterations = iterations,
    ) {
        startBenchmarkActivity(SCENARIO_PIPELINE)
        assertTrue(device.wait(Until.hasObject(By.res(TAG_PIPELINE_READY)), PIPELINE_TIMEOUT_MS))
        assertStrictModeClean()
        assertNoStrictModeLogViolations()
    }

    @Test
    fun hundredLineListScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
        startupMode = StartupMode.WARM,
        iterations = iterations,
        setupBlock = {
            device.executeShellCommand("cmd statusbar collapse")
            startBenchmarkActivity(SCENARIO_LINES)
            assertTrue(device.wait(Until.hasObject(By.res(TAG_LINE_LIST)), UI_TIMEOUT_MS))
            assertTrue(
                "Las 100 presentaciones deben estar precalculadas antes de medir el scroll",
                device.wait(
                    Until.hasObject(By.res(TAG_LIST_PRESENTATIONS_READY)),
                    UI_TIMEOUT_MS,
                ),
            )
        },
    ) {
        checkNotNull(device.findObject(By.res(TARGET_PACKAGE, TAG_LIST_TO_LAST))).click()
        assertTrue(
            "La lista no terminó la animación hacia la línea 100",
            device.wait(
                Until.hasObject(By.res(TARGET_PACKAGE, TAG_LIST_LAST_SETTLED)),
                LIST_TIMEOUT_MS,
            ),
        )
        assertTrue("La línea 100 no quedó visible", device.hasObject(By.res(TAG_LAST_LINE)))
        device.waitForIdle()
        checkNotNull(device.findObject(By.res(TARGET_PACKAGE, TAG_LIST_TO_FIRST))).click()
        assertTrue(
            "La lista no terminó la animación hacia la línea 1",
            device.wait(
                Until.hasObject(By.res(TARGET_PACKAGE, TAG_LIST_FIRST_SETTLED)),
                LIST_TIMEOUT_MS,
            ),
        )
        assertTrue("La línea 1 no quedó visible", device.hasObject(By.res(TAG_FIRST_LINE)))
        assertNoStrictModeLogViolations()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.startBenchmarkActivity(
        scenario: String,
    ) {
        startActivityAndWait(
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(TARGET_PACKAGE, BENCHMARK_ACTIVITY)
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra(EXTRA_SCENARIO, scenario)
            },
        )
    }

    private fun traceMetric(section: String, label: String): TraceSectionMetric =
        TraceSectionMetric(
            sectionName = section,
            mode = TraceSectionMetric.Mode.First,
            label = label,
        )

    private fun androidx.benchmark.macro.MacrobenchmarkScope.assertStrictModeClean() {
        device.waitForIdle()
        val violation = device.findObject(By.res(TAG_STRICT_MODE_VIOLATION))?.text
        assertTrue(
            violation ?: "StrictMode clean tag missing",
            device.wait(Until.hasObject(By.res(TAG_STRICT_MODE_CLEAN)), UI_TIMEOUT_MS),
        )
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.assertNoStrictModeLogViolations() {
        val targetPid = device.executeShellCommand("pidof $TARGET_PACKAGE").trim()
        assertTrue("No se encontró el PID de $TARGET_PACKAGE", targetPid.matches(Regex("\\d+")))
        val strictModeLog = device.executeShellCommand(
            "logcat -d --pid=$targetPid -s FacturaStockStrictMode:E '*:S'",
        )
        val violations = strictModeLog.lineSequence()
            .filter { line -> line.contains("FacturaStockStrictMode") }
            .joinToString(separator = "\n")
        assertTrue(
            violations.ifBlank { "StrictMode clean" },
            violations.isBlank(),
        )
    }

    private val iterations: Int
        get() = InstrumentationRegistry.getArguments()
            .getString(ARG_ITERATIONS)
            ?.toIntOrNull()
            ?.takeIf { it in MIN_ITERATIONS..MAX_ITERATIONS }
            ?: PHYSICAL_ITERATIONS

    private companion object {
        const val TARGET_PACKAGE = StartupJourney.TARGET_PACKAGE
        const val BENCHMARK_ACTIVITY =
            "com.facturastock.app.performance.PerformanceBenchmarkActivity"
        const val EXTRA_SCENARIO = "performance.scenario"
        const val SCENARIO_LINES = "lines"
        const val SCENARIO_PIPELINE = "pipeline"
        const val SCENARIO_CAMERA = "camera"
        const val TAG_LINE_LIST = "line_review_list"
        const val TAG_LIST_PRESENTATIONS_READY = "performance_list_presentations_ready"
        const val TAG_PIPELINE_READY = "performance_pipeline_ready"
        const val TAG_CAMERA_CAPTURE = "performance_camera_capture"
        const val TAG_CAMERA_CAPTURE_READY = "performance_camera_capture_ready"
        const val TAG_CAMERA_STRICT_MODE_CLEAN = "performance_camera_strict_mode_clean"
        const val TAG_CAMERA_STRICT_MODE_VIOLATION =
            "performance_camera_strict_mode_violation"
        const val TAG_STRICT_MODE_CLEAN = "performance_strict_mode_clean"
        const val TAG_STRICT_MODE_VIOLATION = "performance_strict_mode_violation"
        const val TAG_LIST_TO_FIRST = "performance_list_to_first"
        const val TAG_LIST_TO_LAST = "performance_list_to_last"
        const val TAG_LIST_FIRST_SETTLED = "performance_list_first_settled"
        const val TAG_LIST_LAST_SETTLED = "performance_list_last_settled"
        const val TAG_FIRST_LINE =
            "line_review_card_75000000-0000-4000-8000-000000000001"
        const val TAG_LAST_LINE =
            "line_review_card_75000000-0000-4000-8000-000000000100"
        const val TRACE_BITMAP_RENDER = "fs_bitmap_render_encode"
        const val TRACE_BITMAP_DECODE = "fs_bitmap_decode"
        const val TRACE_BITMAP_WRITE = "fs_bitmap_write"
        const val TRACE_IMAGE_PREPROCESS = "fs_image_preprocess"
        const val TRACE_OCR = "fs_ocr"
        const val TRACE_PARSER = "fs_parser"
        const val TRACE_SYNC_100 = "fs_sync_100"
        const val TRACE_CAMERA_BIND = "fs_camera_bind"
        const val TRACE_CAMERA_FIRST_FRAME = "fs_camera_first_frame"
        const val TRACE_CAMERA_CAPTURE = "fs_camera_capture"
        const val TRACE_CAMERA_JPEG_COPY = "fs_camera_jpeg_copy"
        const val ARG_ITERATIONS = "iterations"
        const val PHYSICAL_ITERATIONS = 30
        const val MIN_ITERATIONS = 1
        const val MAX_ITERATIONS = 100
        const val UI_TIMEOUT_MS = 10_000L
        const val LIST_TIMEOUT_MS = 30_000L
        const val PIPELINE_TIMEOUT_MS = 60_000L
    }
}
