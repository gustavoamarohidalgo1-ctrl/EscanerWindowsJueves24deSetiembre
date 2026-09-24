package com.facturastock.app.feature.settings

import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        viewModel.onAction(
            uri?.let { SettingsContract.Action.ExportDestinationSelected(it.toString()) }
                ?: SettingsContract.Action.ExportCanceled,
        )
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            SettingsContract.Effect.CreateExportDocument ->
                exportLauncher.launch("facturastock-libro-contable.json")
        }
    }

    if (
        state.config == null &&
        state.failure == SettingsContract.Failure.LOAD_FAILED
    ) {
        RecoverableError(
            title = stringResource(R.string.feature_load_error_title),
            message = stringResource(R.string.feature_load_error_message),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { viewModel.onAction(SettingsContract.Action.Retry) },
            modifier = modifier.fillMaxSize(),
        )
    } else if (state.config == null) {
        LoadingState(
            message = stringResource(R.string.feature_loading_message),
            modifier = modifier.fillMaxSize(),
        )
    } else {
        SettingsScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}

@Composable
fun SettingsScreen(
    state: SettingsContract.State,
    onAction: (SettingsContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md)
            .testTag(SettingsTestTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.savedFeedback) {
            Text(
                text = stringResource(R.string.saved_feedback_title),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = FacturaStockDesign.semanticColors.success,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (state.failure == SettingsContract.Failure.SAVE_FAILED) {
            StatusCard(
                statusLabel = stringResource(R.string.navigation_settings),
                title = stringResource(R.string.save_error_title),
                message = stringResource(R.string.save_error_message),
                tone = StatusTone.ERROR,
                iconRes = R.drawable.ic_warning,
            )
        }
        if (state.failure == SettingsContract.Failure.LOAD_FAILED && state.business == null) {
            RecoverableError(
                title = stringResource(R.string.feature_load_error_title),
                message = stringResource(R.string.feature_load_error_message),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(SettingsContract.Action.Retry) },
            )
        }

        SectionHeader(titleRes = R.string.settings_section_business)
        OutlinedTextField(
            value = state.legalName,
            onValueChange = { onAction(SettingsContract.Action.LegalNameChanged(it)) },
            label = { Text(stringResource(R.string.settings_legal_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.tradeName,
            onValueChange = { onAction(SettingsContract.Action.TradeNameChanged(it)) },
            label = { Text(stringResource(R.string.settings_trade_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.ruc,
            onValueChange = { newValue ->
                onAction(
                    SettingsContract.Action.RucChanged(
                        newValue.filter(Char::isDigit).take(11),
                    ),
                )
            },
            label = { Text(stringResource(R.string.settings_ruc_label)) },
            singleLine = true,
            isError = !state.isRucWellFormed,
            supportingText = if (state.isRucWellFormed) {
                null
            } else {
                { Text(stringResource(R.string.ruc_error_invalid)) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.rucChecksumWarning) {
            StatusCard(
                statusLabel = stringResource(R.string.ruc_checksum_warning_label),
                title = stringResource(R.string.ruc_checksum_warning_title),
                message = stringResource(R.string.ruc_checksum_warning),
                tone = StatusTone.WARNING,
                iconRes = R.drawable.ic_warning,
                announcementMode = LiveRegionMode.Polite,
            )
        }
        FacturaStockPrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = { onAction(SettingsContract.Action.SaveBusinessProfile) },
            enabled = state.canSaveBusiness,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(titleRes = R.string.settings_section_tax)
        OutlinedTextField(
            value = state.taxRatePercent,
            onValueChange = { newValue ->
                onAction(
                    SettingsContract.Action.TaxRateChanged(
                        newValue.filter { it.isDigit() || it == '.' }.take(6),
                    ),
                )
            },
            label = { Text(stringResource(R.string.tax_rate_label)) },
            singleLine = true,
            isError = !state.isTaxRateValid,
            supportingText = if (state.isTaxRateValid) {
                null
            } else {
                { Text(stringResource(R.string.tax_rate_error_invalid)) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.cost_policy_label),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
        CostPolicyOption(
            selected = state.costPolicy == CostPolicy.NET,
            labelRes = R.string.cost_policy_net,
            onSelect = { onAction(SettingsContract.Action.CostPolicySelected(CostPolicy.NET)) },
        )
        CostPolicyOption(
            selected = state.costPolicy == CostPolicy.GROSS,
            labelRes = R.string.cost_policy_gross,
            onSelect = { onAction(SettingsContract.Action.CostPolicySelected(CostPolicy.GROSS)) },
        )
        FacturaStockPrimaryButton(
            text = stringResource(R.string.action_save),
            onClick = { onAction(SettingsContract.Action.SaveTaxConfiguration) },
            enabled = state.canSaveTax,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(titleRes = R.string.settings_section_data)
        state.exportResult?.let { result -> ExportResultCard(result) }
        Text(
            text = stringResource(R.string.settings_export_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        FacturaStockSecondaryButton(
            text = stringResource(
                if (state.isExporting) {
                    R.string.settings_privacy_working
                } else {
                    R.string.settings_export_action
                },
            ),
            onClick = { onAction(SettingsContract.Action.ExportData) },
            enabled = state.canExport,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.EXPORT_DATA),
        )
        Spacer(modifier = Modifier.height(spacing.md))
    }

    if (state.showTaxWarningDialog) {
        FacturaStockDialog(
            title = stringResource(R.string.settings_tax_warning_title),
            message = stringResource(R.string.settings_tax_warning_message),
            confirmLabel = stringResource(R.string.action_save),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(SettingsContract.Action.ConfirmTaxConfiguration) },
            onDismiss = { onAction(SettingsContract.Action.DismissDialogs) },
        )
    }
}

@Composable
private fun SectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun CostPolicyOption(
    selected: Boolean,
    @StringRes labelRes: Int,
    onSelect: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ExportResultCard(result: SettingsContract.ExportResult) {
    when (result) {
        is SettingsContract.ExportResult.Exported -> StatusCard(
            statusLabel = stringResource(R.string.settings_section_data),
            title = stringResource(R.string.settings_export_success_title),
            message = stringResource(
                R.string.settings_export_success_message,
                result.products,
                result.suppliers,
                result.units,
                result.inventoryLocations,
                result.supplierProductAliases,
                result.purchases,
                result.purchaseLines,
                result.inventoryBalances,
                result.stockMovements,
                result.auditEvents,
                result.retainedImageMetadata,
            ),
            tone = StatusTone.SUCCESS,
            iconRes = R.drawable.ic_check_circle,
            modifier = Modifier.testTag(SettingsTestTags.EXPORT_RESULT),
            announcementMode = LiveRegionMode.Polite,
        )

        SettingsContract.ExportResult.DestinationCleanupUnconfirmed -> StatusCard(
            statusLabel = stringResource(R.string.settings_section_data),
            title = stringResource(R.string.settings_export_partial_title),
            message = stringResource(R.string.settings_export_partial_message),
            tone = StatusTone.ERROR,
            iconRes = R.drawable.ic_warning,
            modifier = Modifier.testTag(SettingsTestTags.EXPORT_RESULT),
            announcementMode = LiveRegionMode.Assertive,
        )

        SettingsContract.ExportResult.Failed -> StatusCard(
            statusLabel = stringResource(R.string.settings_section_data),
            title = stringResource(R.string.settings_privacy_failure_title),
            message = stringResource(R.string.settings_privacy_failure_message),
            tone = StatusTone.ERROR,
            iconRes = R.drawable.ic_warning,
            modifier = Modifier.testTag(SettingsTestTags.EXPORT_RESULT),
            announcementMode = LiveRegionMode.Assertive,
        )
    }
}
