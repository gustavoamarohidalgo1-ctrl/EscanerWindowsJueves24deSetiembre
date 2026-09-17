package com.facturastock.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.core.input.KeyboardWedgeRegistration
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.core.input.KeyboardWedgeReadError

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
    onReadError: (KeyboardWedgeReadError) -> Unit = {},
    onInputChanged: (String) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = LocalScannerInputPermission.current
    val currentPermission by rememberUpdatedState(permission)
    val allowed = enabled && permission()
    val currentOnScan by rememberUpdatedState(onScan)
    val currentOnAvailabilityChanged by rememberUpdatedState(onAvailabilityChanged)
    val currentOnReadError by rememberUpdatedState(onReadError)
    val currentOnInputChanged by rememberUpdatedState(onInputChanged)
    DisposableEffect(lifecycleOwner, allowed) {
        var registration: KeyboardWedgeRegistration? = null

        fun deactivate() {
            registration?.close()
            registration = null
            currentOnAvailabilityChanged(false)
        }

        fun activate() {
            if (!allowed || !currentPermission() || registration != null) return
            registration = KeyboardWedgeRouter.activate(
                onScan = { if (currentPermission()) currentOnScan(it) },
                onReadError = { if (currentPermission()) currentOnReadError(it) },
                onInputChanged = { currentOnInputChanged(it) },
            )
            currentOnAvailabilityChanged(true)
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
            currentOnAvailabilityChanged(false)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            deactivate()
        }
    }
}
