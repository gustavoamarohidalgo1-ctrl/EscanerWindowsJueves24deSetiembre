package com.facturastock.app.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genera los perfiles base y de arranque desde el inicio del proceso hasta Vender visible.
 *
 * La salida se copia a `benchmark/build/outputs/connected_android_test_additional_output` y queda
 * limitada al código de FacturaStock: las dependencias conservan sus perfiles publicados en cada
 * AAR y no se inmovilizan desde este proyecto.
 */
@RunWith(AndroidJUnit4::class)
class FacturaStockBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun ensureSalesReadyBeforeProfiling() {
        // Cada prueba parte de datos limpios; evita heredar jobs o perfiles parciales de otra
        // captura antes de completar onboarding fuera de la ventana observada.
        StartupJourney.prepare(resetPersistentState = true)
    }

    @Test
    fun coldStartup() = collectColdStartup(includeInStartupProfile = true)

    @Test
    fun baselineColdStartup() = collectColdStartup(includeInStartupProfile = false)

    private fun collectColdStartup(includeInStartupProfile: Boolean) =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = includeInStartupProfile,
            filterPredicate = applicationProfileRule::matches,
        ) {
            pressHome()
            startActivityAndWait()
            StartupJourney.waitForSalesReady(
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
            )
        }

    private companion object {
        const val TARGET_PACKAGE = StartupJourney.TARGET_PACKAGE
        val applicationProfileRule = Regex("^[HSP]*Lcom/facturastock/app/.*")
    }
}
