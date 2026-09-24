package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.findAutomaticBarcodeRecovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.UUID

/**
 * Mide la recuperación de lecturas incompletas sobre catálogos grandes: sólo llama a la función
 * de recuperación que ya existía. No usa los predicados nuevos ni asserts sobre tiempos; el
 * informe JSON se escribe en la salida estándar del test (antes iba al estado de instrumentación).
 */
class BarcodeRecoveryPerformanceTest {
    @Test
    fun incompleteScanRecoveryReportsCpuAndWallTime() =
        runBlocking {
            val threads = ManagementFactory.getThreadMXBean()
            val reports = mutableListOf<String>()
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
                    val wallStart = System.nanoTime()
                    val cpuStart = threads.currentThreadCpuTime
                    repeat(ITERATIONS_PER_SAMPLE) {
                        if (findAutomaticBarcodeRecovery(SCANNED, BUSINESS_ID, catalog)?.productId == TARGET_ID) {
                            successfulCalls += 1
                        }
                    }
                    cpuPerCall[sample] = (threads.currentThreadCpuTime - cpuStart) / ITERATIONS_PER_SAMPLE
                    wallPerCall[sample] = (System.nanoTime() - wallStart) / ITERATIONS_PER_SAMPLE
                }
                assertEquals(SAMPLE_COUNT * ITERATIONS_PER_SAMPLE, successfulCalls)
                reports +=
                    jsonObject(
                        "catalog_size" to size,
                        "warmup_iterations" to WARMUP_ITERATIONS,
                        "samples" to SAMPLE_COUNT,
                        "iterations_per_sample" to ITERATIONS_PER_SAMPLE,
                        "successful_calls" to successfulCalls,
                        "cpu_ns_per_call_median" to percentile(cpuPerCall, 50),
                        "cpu_ns_per_call_p95" to percentile(cpuPerCall, 95),
                        "wall_ns_per_call_median" to percentile(wallPerCall, 50),
                        "wall_ns_per_call_p95" to percentile(wallPerCall, 95),
                        "cpu_ns_per_call_samples" to cpuPerCall.joinToString(",", "[", "]"),
                        "wall_ns_per_call_samples" to wallPerCall.joinToString(",", "[", "]"),
                    )
            }
            val result =
                jsonObject(
                    "benchmark" to "\"barcode_recovery_incomplete_scan\"",
                    "scan_length" to SCANNED.length,
                    "reports" to reports.joinToString(",", "[", "]"),
                )
            println("barcode_recovery_performance=$result")
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

    /** Los valores ya vienen serializados (números, arreglos o cadenas entre comillas). */
    private fun jsonObject(vararg fields: Pair<String, Any>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> "\"$key\":$value" }

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
