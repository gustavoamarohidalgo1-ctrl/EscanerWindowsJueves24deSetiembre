package com.facturastock.app.feature.inventory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.feature.common.PhysicalScannerRegistration

internal data class InventoryScannerInput(
    val physicalInput: String,
    val submit: (String) -> Unit,
    val clear: () -> Unit,
)

/** El listado y la pantalla de registro reciben el mismo flujo de códigos. */
@Composable
internal fun rememberInventoryScannerInput(
    state: InventoryContract.State,
    viewModel: InventoryViewModel,
): InventoryScannerInput {
    var physicalInput by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
                    }

                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                        viewModel.onAction(InventoryContract.Action.EndProductRegistration)
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onAction(InventoryContract.Action.EndProductRegistration)
        }
    }
    PhysicalScannerRegistration(
        enabled = state.isRegisteringProducts && state.canRouteScannerInput,
        onAvailabilityChanged = {
            viewModel.onAction(InventoryContract.Action.ScannerAvailabilityChanged(it))
        },
        onScan = { viewModel.onAction(InventoryContract.Action.BarcodeScanned(it)) },
        onReadError = { viewModel.onAction(InventoryContract.Action.ScannerReadFailed(it)) },
        onInputChanged = { physicalInput = it },
    )
    return InventoryScannerInput(
        physicalInput = physicalInput,
        submit = {
            KeyboardWedgeRouter.reset()
            physicalInput = ""
            viewModel.onAction(InventoryContract.Action.ScannerReadReset)
            viewModel.onAction(InventoryContract.Action.BarcodeScanned(it))
        },
        clear = {
            KeyboardWedgeRouter.reset()
            physicalInput = ""
            viewModel.onAction(InventoryContract.Action.ScannerReadReset)
        },
    )
}
