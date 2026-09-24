package com.facturastock.app.feature.sales

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.findAutomaticBarcodeRecovery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * El mismo APK de pruebas puede medir el APK anterior y el optimizado: sólo llama a la función
 * de recuperación que ya existía. No usa los predicados nuevos ni asserts sobre tiempos.
 */
@RunWith(AndroidJUnit4::class)
class BarcodeRecoveryPerformanceTest {
    @Test
    fun incompleteScanRecoveryReportsCpuAndWallTime() =
        runBlocking {
            val reports = JSONArray()
            for (size in listOf(2_000, 5_000)) {
                // Preparación fuera de la medición. Todos los demás códigos son GTIN válidos;
                // su prefijo de seis nueves impide que contengan la lectura con sólo dos omisiones.
                val catalog = List(size) { index -> product(index) }
                repeat(WARMUP_ITERATIONS) {
                    assertEquals(TARGET_ID, findAutomaticBarcodeRecovery(SCANNED, BUSINESS_ID, catalog)?.productId)
                }
                val cpuPerCall = LongArray(SAMPLE_COUNT)
                val wallPerCall = LongArray(SAMPLE_COUNT)
                var successfulCalls = 0
                repeat(SAMPLE_COUNT) { sample ->
                    val wallStart = SystemClock.elapsedRealtimeNanos()
                    val cpuStart = Debug.threadCpuTimeNanos()
                    repeat(ITERATIONS_PER_SAMPLE) {
                        if (findAutomaticBarcodeRecovery(SCANNED, BUSINESS_ID, catalog)?.productId == TARGET_ID) {
                            successfulCalls += 1
                        }
                    }
                    cpuPerCall[sample] = (Debug.threadCpuTimeNanos() - cpuStart) / ITERATIONS_PER_SAMPLE
                    wallPerCall[sample] = (SystemClock.elapsedRealtimeNanos() - wallStart) / ITERATIONS_PER_SAMPLE
                }
                assertEquals(SAMPLE_COUNT * ITERATIONS_PER_SAMPLE, successfulCalls)
                reports.put(
                    JSONObject()
                        .put("catalog_size", size)
                        .put("warmup_iterations", WARMUP_ITERATIONS)
                        .put("samples", SAMPLE_COUNT)
                        .put("iterations_per_sample", ITERATIONS_PER_SAMPLE)
                        .put("successful_calls", successfulCalls)
                        .put("cpu_ns_per_call_median", percentile(cpuPerCall, 50))
                        .put("cpu_ns_per_call_p95", percentile(cpuPerCall, 95))
                        .put("wall_ns_per_call_median", percentile(wallPerCall, 50))
                        .put("wall_ns_per_call_p95", percentile(wallPerCall, 95))
                        .put("cpu_ns_per_call_samples", JSONArray(cpuPerCall.toList()))
                        .put("wall_ns_per_call_samples", JSONArray(wallPerCall.toList())),
                )
            }
            val result =
                JSONObject()
                    .put("benchmark", "barcode_recovery_incomplete_scan")
                    .put("scan_length", SCANNED.length)
                    .put("reports", reports)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString("barcode_recovery_performance", result.toString()) },
            )
        }

    private fun product(index: Int): Product =
        Product(
            productId = if (index == 0) TARGET_ID else ProductId.from(UUID(0L, index.toLong() + 10L)),
            businessId = BUSINESS_ID,
            unitId = UNIT_ID,
            name = "Fixture $index",
            barcode = if (index == 0) COMPLETE else gtin13("999999" + index.toString().padStart(6, '0')),
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
        )

    private fun gtin13(body: String): String {
        var sum = 0
        for (index in body.indices) sum += (body[index] - '0') * if (index % 2 == 0) 1 else 3
        return body + ((10 - sum % 10) % 10)
    }

    private fun percentile(
        samples: LongArray,
        percentile: Int,
    ): Long {
        val sorted = samples.sorted()
        return sorted[((sorted.size - 1) * percentile) / 100]
    }

    private companion object {
        const val SCANNED = "51234500004"
        const val COMPLETE = "7751234500004"
        const val WARMUP_ITERATIONS = 30
        const val SAMPLE_COUNT = 30
        const val ITERATIONS_PER_SAMPLE = 10
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 1L))
        val TARGET_ID: ProductId = ProductId.from(UUID(0L, 2L))
        val UNIT_ID: UnitId = UnitId.from(UUID(0L, 3L))
        val CREATED_AT: Instant = Instant.parse("2026-09-19T00:00:00Z")
    }
}
