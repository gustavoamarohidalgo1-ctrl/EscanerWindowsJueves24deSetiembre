package com.facturastock.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.core.input.KeyboardWedgeRegistration
import com.facturastock.app.core.input.KeyboardWedgeRouter

/**
 * Registers the single physical HID/keyboard-wedge destination while the owning route is resumed.
 *
 * The registration is deliberately route-scoped: pausing, leaving the screen, opening a blocking
 * editor or otherwise disabling [enabled] clears any partial scan and releases the global router.
 */
@Composable
fun PhysicalScannerRegistration(
    enabled: Boolean,
    onAvailabilityChanged: (Boolean) -> Unit,
    onScan: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, enabled) {
        var registration: KeyboardWedgeRegistration? = null

        fun deactivate() {
            registration?.close()
            registration = null
            onAvailabilityChanged(false)
        }

        fun activate() {
            if (!enabled || registration != null) return
            registration = KeyboardWedgeRouter.activate(onScan)
            onAvailabilityChanged(true)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activate()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> deactivate()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            activate()
        } else {
            onAvailabilityChanged(false)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            deactivate()
        }
    }
}
