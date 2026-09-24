package com.facturastock.app.feature.inventory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.feature.common.PhysicalScannerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal data class InventoryScannerInput(
    /** Sólo el campo debe leerlo (ReadPhysicalInput): cada tecla no recompone la ruta. */
    val physicalInput: State<String>,
    val submit: (String) -> Unit,
    val clear: () -> Unit,
)

/** El listado y la pantalla de registro reciben el mismo flujo de códigos. */
@Composable
internal fun rememberInventoryScannerInput(
    state: InventoryContract.State,
    viewModel: InventoryViewModel,
    autoResetIdleRead: Boolean = false,
): InventoryScannerInput {
    // Ninguno de los dos se lee durante la composición: un carácter del lector sólo recompone el
    // campo que muestra el texto parcial, no el listado completo.
    val physicalInput = remember { mutableStateOf("") }
    val readActivity = remember { mutableIntStateOf(0) }
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
        onReadError = {
            readActivity.intValue += 1
            viewModel.onAction(InventoryContract.Action.ScannerReadFailed(it))
        },
        onInputChanged = {
            // KeyboardWedgeRouter.reset() publica "" aunque ya estuviera vacío; sólo un cambio real
            // cuenta como actividad, o el reinicio automático se reprogramaría a sí mismo sin fin.
            if (it != physicalInput.value) {
                readActivity.intValue += 1
                physicalInput.value = it
            }
        },
    )
    // Sin «Reiniciar lector», una trama sin sufijo o rechazada no puede bloquear el lector: tras
    // una pausa mayor que el timeout entre teclas se descarta y la lectura siguiente parte limpia.
    // El aviso de error permanece hasta la próxima lectura; nunca se confirma una trama sin sufijo.
    val hasReadError = state.scannerFailure in INVENTORY_READ_ERRORS
    LaunchedEffect(autoResetIdleRead, hasReadError) {
        if (!autoResetIdleRead) return@LaunchedEffect
        // Cada tecla o error cancela la espera anterior; tras la pausa se reinicia una sola vez.
        snapshotFlow { physicalInput.value.isNotEmpty() to readActivity.intValue }
            .collectLatest { (hasPartialRead, _) ->
                if (!hasPartialRead && !hasReadError) return@collectLatest
                delay(INVENTORY_IDLE_READ_RESET_MILLIS)
                KeyboardWedgeRouter.reset()
                physicalInput.value = ""
            }
    }
    return InventoryScannerInput(
        physicalInput = physicalInput,
        submit = {
            KeyboardWedgeRouter.reset()
            physicalInput.value = ""
            viewModel.onAction(InventoryContract.Action.ScannerReadReset)
            viewModel.onAction(InventoryContract.Action.BarcodeScanned(it))
        },
        clear = {
            KeyboardWedgeRouter.reset()
            physicalInput.value = ""
            viewModel.onAction(InventoryContract.Action.ScannerReadReset)
        },
    )
}

/** Doble del timeout entre teclas del ensamblador: nunca corta una ráfaga en curso. */
internal const val INVENTORY_IDLE_READ_RESET_MILLIS = 2_000L

private val INVENTORY_READ_ERRORS =
    setOf(
        InventoryContract.ScannerFailure.INVALID_BARCODE,
        InventoryContract.ScannerFailure.INCOMPLETE_BARCODE,
        InventoryContract.ScannerFailure.BARCODE_TOO_LONG,
    )
