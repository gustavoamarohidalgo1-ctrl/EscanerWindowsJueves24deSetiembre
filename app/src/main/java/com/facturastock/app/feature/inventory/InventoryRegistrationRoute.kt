package com.facturastock.app.feature.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.ReadPhysicalInput
import com.facturastock.app.feature.common.ScannerCodeInput
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun InventoryRegistrationRoute(
    onRegisterProduct: (String) -> Unit,
    onOpenProduct: (ProductId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InventoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanner = rememberInventoryScannerInput(state, viewModel)

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is InventoryContract.Effect.OpenProductCreation -> {
                if (viewModel.uiState.value.registrationNavigationPending) {
                    effect.barcode?.let(onRegisterProduct)
                }
            }

            is InventoryContract.Effect.OpenProduct -> {
                if (viewModel.uiState.value.registrationNavigationPending) {
                    onOpenProduct(effect.productId)
                }
            }

            InventoryContract.Effect.Back,
            InventoryContract.Effect.CloseInvalidRoute,
            -> {
                onBack()
            }

            is InventoryContract.Effect.OpenPurchase,
            is InventoryContract.Effect.EditProduct,
            -> {
                Unit
            }
        }
    }
    InventoryRegistrationScreen(
        state = state,
        onRetry = { viewModel.onAction(InventoryContract.Action.Retry) },
        scannerContent = {
            ReadPhysicalInput(scanner.physicalInput) { currentPhysicalInput ->
                ScannerCodeInput(
                    enabled = state.isRegisteringProducts && state.canRouteScannerInput,
                    physicalInput = currentPhysicalInput,
                    onClearPhysicalInput = scanner.clear,
                    onCode = scanner.submit,
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
fun InventoryRegistrationScreen(
    state: InventoryContract.State,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
    scannerContent: (@Composable () -> Unit)? = null,
) {
    val spacing = FacturaStockDesign.spacing
    val message = inventoryScannerMessage(state)
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.lg)
                .testTag(InventoryTestTags.REGISTRATION_SCREEN),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_barcode_scanner),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.inventory_mode_scanner), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.inventory_registration_help), style = MaterialTheme.typography.bodyLarge)
                scannerContent?.invoke()
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(InventoryTestTags.REGISTRATION_STATUS)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    shape = MaterialTheme.shapes.medium,
                    color =
                        if (state.scannerFailure != null || state.failure != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                ) {
                    Text(stringResource(message), modifier = Modifier.padding(spacing.md))
                }
                if (state.isLoading || state.isBarcodeLookupRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.inventory_registration_enter), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.inventory_registration_result_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.failure != null || state.scannerFailure == InventoryContract.ScannerFailure.LOOKUP_FAILED) {
                    FacturaStockSecondaryButton(text = stringResource(R.string.action_retry), onClick = onRetry)
                }
            }
        }
        // Sin «Volver al inventario»: la flecha de la barra superior ya regresa.
    }
}

internal fun inventoryScannerMessage(state: InventoryContract.State): Int =
    when {
        state.isLoading -> R.string.feature_loading_message
        state.isBarcodeLookupRunning || state.registrationNavigationPending -> R.string.inventory_scanner_searching
        state.failure != null -> R.string.inventory_scanner_lookup_failed
        state.scannerFailure == InventoryContract.ScannerFailure.INVALID_BARCODE -> R.string.inventory_scanner_invalid
        state.scannerFailure == InventoryContract.ScannerFailure.INCOMPLETE_BARCODE -> R.string.inventory_scanner_incomplete
        state.scannerFailure == InventoryContract.ScannerFailure.BARCODE_TOO_LONG -> R.string.inventory_scanner_too_long
        state.scannerFailure == InventoryContract.ScannerFailure.NO_ACTIVE_BUSINESS -> R.string.inventory_scanner_no_business
        state.scannerFailure != null -> R.string.inventory_scanner_lookup_failed
        state.scannerActive -> R.string.inventory_registration_ready
        else -> R.string.inventory_scanner_inactive
    }
