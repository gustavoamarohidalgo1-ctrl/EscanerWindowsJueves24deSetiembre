package com.facturastock.app.feature.sales

import com.facturastock.app.resources.*
import com.facturastock.app.ui.navigation.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.facturastock.app.di.appViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.core.input.KeyboardWedgeReadError
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.PhysicalScannerRegistration
import com.facturastock.app.feature.common.ReadPhysicalInput
import com.facturastock.app.feature.common.ScannerCodeInput
import com.facturastock.app.ui.theme.FacturaStockDesign
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
fun SalesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    entryKind: SalesContract.EntryKind = SalesContract.EntryKind.CASH,
    allowEntryKindSelection: Boolean = true,
    showStepBack: Boolean = true,
    onCreditSalePosted: () -> Unit = {},
    onOpenDebtors: () -> Unit = {},
    onExitCancelled: () -> Unit = {},
    onExitRequestAvailable: ((() -> Unit)?) -> Unit = {},
    onRegisterProduct: (SalesContract.ProductRegistrationRequest) -> Unit = {},
    registrationResult: SalesContract.ProductRegistrationResult? = null,
    onRegistrationResultConsumed: () -> Unit = {},
    viewModel: SalesViewModel = appViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val associatedMessage = stringResource(Res.string.sales_message_barcode_associated)
    val postedMessage = stringResource(Res.string.sales_message_posted)
    val currentExitRegistration by rememberUpdatedState(onExitRequestAvailable)
    val lifecycleOwner = LocalLifecycleOwner.current
    val consumeRegistrationResult by rememberUpdatedState(onRegistrationResultConsumed)
    // Un callback estable evita recomponer cada tarjeta de producto/carrito en cada emisión del
    // estado (cada lectura emite varias). Lee los valores vigentes al ejecutarse la acción.
    val latestState by rememberUpdatedState(state)
    val latestAllowEntryKindSelection by rememberUpdatedState(allowEntryKindSelection)
    val latestOnExitCancelled by rememberUpdatedState(onExitCancelled)
    val screenAction: (SalesContract.Action) -> Unit =
        remember(viewModel) {
            { action ->
                if (action == SalesContract.Action.DiscardEditsDismissed) {
                    latestOnExitCancelled()
                }
                if (action == SalesContract.Action.StepBackSelected &&
                    !latestAllowEntryKindSelection &&
                    latestState.entryStep == SalesContract.EntryStep.SELECT_MODE
                ) {
                    viewModel.onAction(SalesContract.Action.BackSelected)
                } else {
                    viewModel.onAction(action)
                }
            }
        }
    // Cada tecla del lector escribe aquí. Sólo el campo lo lee (ver ReadPhysicalInput), así una
    // lectura de 13 dígitos no recompone 13 veces toda la ruta de Ventas.
    val physicalInput = remember(viewModel) { mutableStateOf("") }
    val clearScannerInput = {
        KeyboardWedgeRouter.reset()
        physicalInput.value = ""
        viewModel.onAction(SalesContract.Action.ScannerReadReset)
    }

    LaunchedEffect(viewModel, entryKind, allowEntryKindSelection) {
        viewModel.onAction(SalesContract.Action.InitializeEntry(entryKind, allowEntryKindSelection, unifiedInput = true))
    }

    LaunchedEffect(viewModel, lifecycleOwner, registrationResult) {
        val result = registrationResult ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(result))
        consumeRegistrationResult()
    }

    DisposableEffect(viewModel) {
        currentExitRegistration {
            viewModel.onAction(SalesContract.Action.BackSelected)
        }
        onDispose { currentExitRegistration(null) }
    }

    DisposableEffect(viewModel, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    viewModel.onAction(SalesContract.Action.ScannerSessionStopped)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onAction(SalesContract.Action.ScannerSessionStopped)
        }
    }

    BackHandler {
        when {
            state.isProcessingBarcode && state.entryStep == SalesContract.EntryStep.SELL -> {
                viewModel.onAction(SalesContract.Action.StepBackSelected)
            }

            state.isMutating -> {
                viewModel.onAction(SalesContract.Action.BackSelected)
            }

            state.discardEditsReview -> {
                onExitCancelled()
                viewModel.onAction(SalesContract.Action.DiscardEditsDismissed)
            }

            state.weightSaleEditor != null -> {
                viewModel.onAction(SalesContract.Action.WeightSaleDismissed)
            }

            state.pendingReplacement != null -> {
                viewModel.onAction(SalesContract.Action.BarcodeReplacementDismissed)
            }

            state.pendingLocations.isNotEmpty() -> {
                viewModel.onAction(SalesContract.Action.LocationSelectionDismissed)
            }

            state.isAssociating -> {
                viewModel.onAction(SalesContract.Action.AssociationDismissed)
            }

            state.entryStep == SalesContract.EntryStep.SELL ||
                (allowEntryKindSelection && state.entryStep == SalesContract.EntryStep.SELECT_MODE) -> {
                viewModel.onAction(SalesContract.Action.StepBackSelected)
            }

            else -> {
                viewModel.onAction(SalesContract.Action.BackSelected)
            }
        }
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is SalesContract.Effect.RegisterProduct -> {
                if (viewModel.uiState.value.productRegistration == effect.request) {
                    onRegisterProduct(effect.request)
                }
            }
            SalesContract.Effect.OpenDebtors -> {
                onOpenDebtors()
            }

            SalesContract.Effect.Back -> {
                onBack()
            }

            SalesContract.Effect.CreditSalePosted -> {
                onCreditSalePosted()
            }

            is SalesContract.Effect.ShowMessage -> {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        when (effect.message) {
                            SalesContract.Message.BARCODE_ASSOCIATED -> associatedMessage
                            SalesContract.Message.SALE_POSTED -> postedMessage
                        },
                    )
                }
            }
        }
    }

    PhysicalScannerRegistration(
        enabled = state.canRouteScannerInput,
        onAvailabilityChanged = { active ->
            viewModel.onAction(SalesContract.Action.ScannerAvailabilityChanged(active))
        },
        onScan = { value ->
            viewModel.onAction(SalesContract.Action.BarcodeScanned(value))
        },
        onInputChanged = { physicalInput.value = it },
        onReadError = { error ->
            viewModel.onAction(
                SalesContract.Action.ScannerReadFailed(
                    when (error) {
                        KeyboardWedgeReadError.INCOMPLETE -> {
                            SalesContract.ScannerFailure.INCOMPLETE
                        }

                        KeyboardWedgeReadError.TOO_LONG -> {
                            SalesContract.ScannerFailure.TOO_LONG
                        }

                        KeyboardWedgeReadError.INVALID_CHARACTER -> {
                            SalesContract.ScannerFailure.INVALID_CHARACTER
                        }
                    },
                ),
            )
        },
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // El receptor IME permanece compuesto aunque se desplace el carrito.
            if (state.entryStep == SalesContract.EntryStep.SELL &&
                state.mode == SalesContract.EntryMode.SCANNER
            ) {
                ReadPhysicalInput(physicalInput) { currentPhysicalInput ->
                    ScannerCodeInput(
                        // Permanece tocable al editar el deudor; el registro HID conserva arriba
                        // todos sus guards. Al recuperar foco, el otro campo publica su pérdida.
                        enabled = if (state.unifiedInput) state.copy(isTextInputFocused = false).canRouteScannerInput
                            else state.canRouteScannerInput,
                        physicalInput = currentPhysicalInput,
                        onClearPhysicalInput = clearScannerInput,
                        onCode = { value ->
                            viewModel.onAction(SalesContract.Action.BarcodeScanned(value))
                        },
                        submitLabelRes = if (state.unifiedInput) Res.string.sales_unified_add_code else Res.string.sales_scanner_add_product,
                        searchQuery = state.query.takeIf { state.unifiedInput },
                        onSearchQueryChange = if (state.unifiedInput) {
                            { value -> viewModel.onAction(SalesContract.Action.SearchChanged(value)) }
                        } else null,
                        isOtherTextInputFocused = state.unifiedInput && state.isTextInputFocused,
                        labelRes = if (state.unifiedInput) Res.string.sales_unified_input_label else Res.string.scanner_code_label,
                        hintRes = if (state.unifiedInput) Res.string.sales_unified_input_hint else Res.string.scanner_code_hint,
                        modifier = Modifier.padding(FacturaStockDesign.spacing.md),
                    )
                }
                SalesScannerFeedback(
                    state = state,
                    modifier = Modifier.padding(horizontal = FacturaStockDesign.spacing.md),
                )
            }
            SalesScreen(
                state = state,
                onAction = screenAction,
                modifier = Modifier.weight(1f),
                allowEntryKindSelection = allowEntryKindSelection,
                showScannerStatus = false,
                showStepBack = showStepBack,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
