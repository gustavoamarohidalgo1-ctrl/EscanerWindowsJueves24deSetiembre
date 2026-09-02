package com.facturastock.app.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Estado reproducible para perfilar el arranque real hasta que Home tenga datos utilizables.
 *
 * El onboarding se completa desde [prepare] antes de que BaselineProfileRule o MacrobenchmarkRule
 * empiecen a capturar. Así el perfil de arranque no queda contaminado por un formulario de primer
 * uso y el bloque medido puede exigir el primer contenido operativo del dashboard.
 */
internal object StartupJourney {
    const val TARGET_PACKAGE = "com.facturastock.app"

    private const val MAIN_ACTIVITY = "com.facturastock.app.MainActivity"
    private const val ONBOARDING_SCREEN_TAG = "onboarding_screen"
    private const val BUSINESS_NAME_TAG = "onboarding_business_name"
    private const val WAREHOUSE_TAG = "onboarding_warehouse"
    private const val SUBMIT_TAG = "onboarding_submit"
    private const val HOME_READY_TAG = "home_scan_cta"
    private const val BENCHMARK_BUSINESS_NAME = "BenchmarkBusiness"
    private const val BENCHMARK_WAREHOUSE_NAME = "BenchmarkWarehouse"
    private const val SUPPRESS_DEFERRED_STARTUP_EXTRA =
        "com.facturastock.app.performance.SUPPRESS_DEFERRED_STARTUP"
    private const val START_TIMEOUT_MS = 20_000L
    private const val HOME_TIMEOUT_MS = 30_000L
    private const val UI_STEP_TIMEOUT_MS = 10_000L
    private const val SUBMIT_TRANSITION_TIMEOUT_MS = 5_000L
    private const val SUBMIT_ATTEMPTS = 3

    /** Deja el paquete fuera de primer uso y vuelve a Home antes de iniciar una captura. */
    fun prepare(resetPersistentState: Boolean = false) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (resetPersistentState) {
            val clearOutput = device.executeShellCommand("pm clear $TARGET_PACKAGE")
            check(clearOutput.contains("Success", ignoreCase = true)) {
                "No se pudo limpiar el estado durable antes de perfilar: $clearOutput"
            }
            check(device.executeShellCommand("pidof $TARGET_PACKAGE").trim().isEmpty()) {
                "El proceso target siguió activo después de limpiar sus datos"
            }
        }
        device.pressHome()
        val launchOutput = device.executeShellCommand(
            "am start -W -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -n $TARGET_PACKAGE/$MAIN_ACTIVITY " +
                "--ez $SUPPRESS_DEFERRED_STARTUP_EXTRA true",
        )
        check(!launchOutput.contains("Error:", ignoreCase = true)) {
            "No se pudo abrir MainActivity para preparar el recorrido: $launchOutput"
        }

        when (waitForEntryPoint(device)) {
            EntryPoint.HOME -> Unit
            EntryPoint.ONBOARDING -> completeOnboarding(device)
        }
        waitForHomeReady(device)
        device.pressHome()
        // La apertura auxiliar suprime el trabajo post-frame únicamente en benchmark/profile.
        // Detener el paquete garantiza que la captura comience desde un proceso nuevo.
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        check(device.executeShellCommand("pidof $TARGET_PACKAGE").trim().isEmpty()) {
            "La corrida de preparación siguió activa antes de medir el arranque"
        }
        device.waitForIdle()
    }

    /** Home está listo solo cuando el estado cargado compone su acción principal. */
    fun waitForHomeReady(device: UiDevice) {
        check(
            device.wait(
                Until.hasObject(By.res(HOME_READY_TAG)),
                HOME_TIMEOUT_MS,
            ),
        ) {
            "Home no publicó su contenido operativo ($HOME_READY_TAG)"
        }
        device.waitForIdle()
    }

    private fun waitForEntryPoint(device: UiDevice): EntryPoint {
        val deadlineNanos = System.nanoTime() + START_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (device.hasObject(By.res(HOME_READY_TAG))) return EntryPoint.HOME
            if (device.hasObject(By.res(ONBOARDING_SCREEN_TAG))) {
                return EntryPoint.ONBOARDING
            }
            device.wait(
                Until.hasObject(By.res(HOME_READY_TAG)),
                250L,
            )
        }
        error("MainActivity no llegó a onboarding ni a Home dentro del plazo")
    }

    private fun completeOnboarding(device: UiDevice) {
        enterText(device, BUSINESS_NAME_TAG, BENCHMARK_BUSINESS_NAME)
        enterText(device, WAREHOUSE_TAG, BENCHMARK_WAREHOUSE_NAME)

        repeat(SUBMIT_ATTEMPTS) {
            // El primer frame con ambos textos puede conservar fugazmente el CTA deshabilitado.
            // Reubicarlo y exigir enabled evita que UiAutomator envíe un click que Compose descarte.
            scrollUntilFound(device, By.res(SUBMIT_TAG))
            val submit = checkNotNull(
                device.wait(
                    Until.findObject(By.res(SUBMIT_TAG).enabled(true)),
                    UI_STEP_TIMEOUT_MS,
                ),
            ) { "El CTA de onboarding no se habilitó tras completar los campos obligatorios" }
            submit.click()

            when (waitForSubmitOutcome(device)) {
                SubmitOutcome.HOME -> return
                SubmitOutcome.TRANSITION -> {
                    waitForHomeReady(device)
                    return
                }
                SubmitOutcome.ONBOARDING -> Unit
            }
        }
        error("Onboarding conservó el formulario tras $SUBMIT_ATTEMPTS intentos de envío")
    }

    private fun waitForSubmitOutcome(device: UiDevice): SubmitOutcome {
        val deadlineNanos = System.nanoTime() + SUBMIT_TRANSITION_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadlineNanos) {
            if (device.hasObject(By.res(HOME_READY_TAG))) return SubmitOutcome.HOME
            if (!device.hasObject(By.res(ONBOARDING_SCREEN_TAG))) {
                return SubmitOutcome.TRANSITION
            }
            device.wait(Until.hasObject(By.res(HOME_READY_TAG)), 250L)
        }
        return SubmitOutcome.ONBOARDING
    }

    private fun enterText(
        device: UiDevice,
        tag: String,
        value: String,
    ) {
        val editable = scrollUntilFound(
            device,
            By.res(tag),
        )
        editable.setText(value)
        // ESC cierra el IME si apareció, pero no navega fuera de MainActivity cuando no hay IME.
        device.executeShellCommand("input keyevent KEYCODE_ESCAPE")
        device.waitForIdle()
        check(device.currentPackageName == TARGET_PACKAGE) {
            "El formulario de onboarding perdió el foco de la app al completar $tag"
        }
    }

    private fun scrollUntilFound(
        device: UiDevice,
        selector: androidx.test.uiautomator.BySelector,
    ): UiObject2 {
        repeat(6) {
            device.findObject(selector)?.let { return it }
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                20,
            )
            device.waitForIdle()
        }
        return checkNotNull(device.wait(Until.findObject(selector), UI_STEP_TIMEOUT_MS)) {
            "No se encontró el control requerido por el recorrido de arranque: $selector"
        }
    }

    private enum class EntryPoint {
        HOME,
        ONBOARDING,
    }

    private enum class SubmitOutcome {
        HOME,
        TRANSITION,
        ONBOARDING,
    }
}
