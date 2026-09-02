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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.BuildConfig
import com.facturastock.app.R
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
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
    onOpenDemoCapture: (DraftId, CaptureId) -> Unit,
    onOpenDemoPurchase: (PurchaseId) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenPrivacyPolicy: (String) -> Unit,
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
            is SettingsContract.Effect.OpenDemoCapture ->
                onOpenDemoCapture(effect.draftId, effect.captureId)

            is SettingsContract.Effect.OpenDemoPurchase ->
                onOpenDemoPurchase(effect.purchaseId)

            SettingsContract.Effect.OpenAccount -> onOpenAccount()
            SettingsContract.Effect.OpenSync -> onOpenSync()
            SettingsContract.Effect.OpenPrivacyPolicy -> {
                BuildConfig.PRIVACY_POLICY_URL
                    .takeIf(String::isNotBlank)
                    ?.let(onOpenPrivacyPolicy)
            }

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
            privacyPolicyAvailable = BuildConfig.PRIVACY_POLICY_URL.isNotBlank(),
            modifier = modifier,
        )
    }
}

@Composable
fun SettingsScreen(
    state: SettingsContract.State,
    onAction: (SettingsContract.Action) -> Unit,
    modifier: Modifier = Modifier,
    privacyPolicyAvailable: Boolean = false,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
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

        SectionHeader(titleRes = R.string.settings_section_regional)
        ReadOnlyRow(
            labelRes = R.string.regional_currency_label,
            valueRes = R.string.regional_currency_value,
        )
        ReadOnlyRow(
            labelRes = R.string.regional_timezone_label,
            valueRes = R.string.regional_timezone_value,
        )

        SectionHeader(titleRes = R.string.settings_section_account)
        Text(
            text = stringResource(R.string.settings_account_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.settings_account_open),
            onClick = { onAction(SettingsContract.Action.OpenAccount) },
            enabled = !state.isSaving && !state.isStartingDemoScenario,
            modifier = Modifier.fillMaxWidth(),
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.settings_sync_open),
            onClick = { onAction(SettingsContract.Action.OpenSync) },
            enabled = !state.isSaving && !state.isStartingDemoScenario,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(titleRes = R.string.settings_section_privacy)
        state.privacyResult?.let { result ->
            PrivacyResultCard(result)
        }
        Text(
            text = stringResource(R.string.settings_retention_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_retention_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        RetentionPolicyOption(
            selected = state.imageRetentionPolicy == ImageRetentionPolicy.AFTER_OCR,
            labelRes = R.string.settings_retention_after_ocr,
            onSelect = {
                onAction(
                    SettingsContract.Action.ImageRetentionPolicySelected(
                        ImageRetentionPolicy.AFTER_OCR,
                    ),
                )
            },
            enabled = !state.isSaving && !state.isPrivacyBusy,
        )
        RetentionPolicyOption(
            selected = state.imageRetentionPolicy == ImageRetentionPolicy.AFTER_CONFIRM,
            labelRes = R.string.settings_retention_after_confirm,
            onSelect = {
                onAction(
                    SettingsContract.Action.ImageRetentionPolicySelected(
                        ImageRetentionPolicy.AFTER_CONFIRM,
                    ),
                )
            },
            enabled = !state.isSaving && !state.isPrivacyBusy,
        )
        RetentionPolicyOption(
            selected = state.imageRetentionPolicy == ImageRetentionPolicy.DAYS_30,
            labelRes = R.string.settings_retention_30_days,
            onSelect = {
                onAction(
                    SettingsContract.Action.ImageRetentionPolicySelected(
                        ImageRetentionPolicy.DAYS_30,
                    ),
                )
            },
            enabled = !state.isSaving && !state.isPrivacyBusy,
        )
        RetentionPolicyOption(
            selected = state.imageRetentionPolicy == ImageRetentionPolicy.DAYS_90,
            labelRes = R.string.settings_retention_90_days,
            onSelect = {
                onAction(
                    SettingsContract.Action.ImageRetentionPolicySelected(
                        ImageRetentionPolicy.DAYS_90,
                    ),
                )
            },
            enabled = !state.isSaving && !state.isPrivacyBusy,
        )
        RetentionPolicyOption(
            selected = state.imageRetentionPolicy == ImageRetentionPolicy.KEEP,
            labelRes = R.string.settings_retention_keep,
            onSelect = {
                onAction(
                    SettingsContract.Action.ImageRetentionPolicySelected(
                        ImageRetentionPolicy.KEEP,
                    ),
                )
            },
            enabled = !state.isSaving && !state.isPrivacyBusy,
        )
        SettingsSwitch(
            checked = state.backupEnabled,
            titleRes = R.string.settings_backup_title,
            summaryRes = R.string.settings_backup_summary,
            tag = SettingsTestTags.BACKUP_CONSENT,
            enabled = !state.isSaving && !state.isPrivacyBusy,
            onCheckedChange = {
                onAction(SettingsContract.Action.BackupEnabledChanged(it))
            },
        )
        SettingsSwitch(
            checked = state.documentBackupEnabled,
            titleRes = R.string.settings_document_backup_title,
            summaryRes = R.string.settings_document_backup_summary,
            tag = SettingsTestTags.DOCUMENT_BACKUP_CONSENT,
            enabled = state.backupEnabled && !state.isSaving && !state.isPrivacyBusy,
            onCheckedChange = {
                onAction(SettingsContract.Action.DocumentBackupEnabledChanged(it))
            },
        )
        SettingsSwitch(
            checked = state.biometricLockEnabled,
            titleRes = R.string.settings_biometric_lock_title,
            summaryRes = R.string.settings_biometric_lock_summary,
            tag = SettingsTestTags.BIOMETRIC_LOCK,
            enabled = !state.isSaving && !state.isPrivacyBusy,
            onCheckedChange = {
                onAction(SettingsContract.Action.BiometricLockEnabledChanged(it))
            },
        )
        Text(
            text = stringResource(R.string.settings_export_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        FacturaStockSecondaryButton(
            text = stringResource(
                if (state.privacyOperation == SettingsContract.PrivacyOperation.EXPORT) {
                    R.string.settings_privacy_working
                } else {
                    R.string.settings_export_action
                },
            ),
            onClick = { onAction(SettingsContract.Action.ExportData) },
            enabled = !state.isSaving && !state.isPrivacyBusy &&
                !state.awaitingExportDestination,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.EXPORT_DATA),
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.settings_delete_images_action),
            onClick = { onAction(SettingsContract.Action.DeleteImages) },
            enabled = !state.isSaving && !state.isPrivacyBusy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.DELETE_IMAGES),
        )
        FacturaStockSecondaryButton(
            text = stringResource(
                if (state.privacyOperation == SettingsContract.PrivacyOperation.CLEAN_FILES) {
                    R.string.settings_privacy_working
                } else {
                    R.string.settings_clean_files_action
                },
            ),
            onClick = { onAction(SettingsContract.Action.CleanPrivateFiles) },
            enabled = !state.isSaving && !state.isPrivacyBusy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.CLEAN_PRIVATE_FILES),
        )
        Text(
            text = stringResource(R.string.settings_privacy_policy_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.settings_privacy_policy_open),
            onClick = { onAction(SettingsContract.Action.OpenPrivacyPolicy) },
            enabled =
                privacyPolicyAvailable && !state.isSaving && !state.isStartingDemoScenario,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.PRIVACY_POLICY_ACTION),
        )
        SettingsSwitch(
            checked = state.diagnosticsEnabled,
            titleRes = R.string.settings_diagnostics_title,
            summaryRes = R.string.settings_diagnostics_summary,
            tag = SettingsTestTags.DIAGNOSTICS_CONSENT,
            enabled = !state.isSaving && !state.isPrivacyBusy,
            onCheckedChange = {
                onAction(SettingsContract.Action.DiagnosticsConsentChanged(it))
            },
        )

        SectionHeader(titleRes = R.string.settings_section_demo)
        state.demoScenarioFailure?.let { failure ->
            StatusCard(
                statusLabel = stringResource(R.string.demo_scenario_status_label),
                title = stringResource(R.string.demo_scenario_error_title),
                message = stringResource(
                    when (failure) {
                        SettingsContract.DemoScenarioFailure.NOT_IN_DEMO_MODE ->
                            R.string.demo_scenario_not_active_error

                        SettingsContract.DemoScenarioFailure.CONFLICT ->
                            R.string.demo_scenario_conflict_error

                        SettingsContract.DemoScenarioFailure.START_FAILED ->
                            R.string.demo_scenario_start_error
                    },
                ),
                tone = StatusTone.ERROR,
                iconRes = R.drawable.ic_warning,
            )
        }
        if (state.isDemoMode) {
            StatusCard(
                statusLabel = stringResource(R.string.settings_section_demo),
                title = stringResource(R.string.demo_active_title),
                message = stringResource(R.string.demo_active_message),
                tone = StatusTone.INFO,
                iconRes = R.drawable.ic_info,
            )
            FacturaStockPrimaryButton(
                text = stringResource(
                    if (state.isStartingDemoScenario) {
                        R.string.action_demo_scenario_starting
                    } else {
                        R.string.action_demo_scenario_open
                    },
                ),
                onClick = { onAction(SettingsContract.Action.StartDemoScenario) },
                enabled = state.canStartDemoScenario,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.DEMO_SCENARIO_ACTION),
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.action_demo_exit),
                onClick = { onAction(SettingsContract.Action.ExitDemoMode) },
                enabled = !state.isSaving && !state.isStartingDemoScenario,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = stringResource(R.string.settings_demo_inactive_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.action_demo_enter),
                onClick = { onAction(SettingsContract.Action.EnterDemoMode) },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    if (state.showDemoEnterDialog) {
        FacturaStockDialog(
            title = stringResource(R.string.demo_enter_title),
            message = stringResource(R.string.demo_enter_message),
            confirmLabel = stringResource(R.string.action_demo_enter),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(SettingsContract.Action.ConfirmEnterDemoMode) },
            onDismiss = { onAction(SettingsContract.Action.DismissDialogs) },
        )
    }
    if (state.showDemoExitDialog) {
        FacturaStockDialog(
            title = stringResource(R.string.demo_exit_title),
            message = stringResource(R.string.demo_exit_message),
            confirmLabel = stringResource(R.string.action_demo_exit),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(SettingsContract.Action.ConfirmExitDemoMode) },
            onDismiss = { onAction(SettingsContract.Action.DismissDialogs) },
        )
    }
    if (state.showDeleteImagesDialog) {
        FacturaStockDialog(
            title = stringResource(R.string.settings_delete_images_dialog_title),
            message = stringResource(R.string.settings_delete_images_dialog_message),
            confirmLabel = stringResource(R.string.settings_delete_images_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(SettingsContract.Action.ConfirmDeleteImages) },
            onDismiss = { onAction(SettingsContract.Action.DismissDialogs) },
        )
    }
    state.pendingImageRetentionPolicy?.let { policy ->
        FacturaStockDialog(
            title = stringResource(R.string.settings_retention_dialog_title),
            message = stringResource(
                R.string.settings_retention_dialog_message,
                stringResource(policy.labelResource()),
            ),
            confirmLabel = stringResource(R.string.settings_retention_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                onAction(SettingsContract.Action.ConfirmImageRetentionPolicy)
            },
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
private fun RetentionPolicyOption(
    selected: Boolean,
    @StringRes labelRes: Int,
    onSelect: () -> Unit,
    enabled: Boolean,
) {
    val spacing = FacturaStockDesign.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SettingsSwitch(
    checked: Boolean,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int,
    tag: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(summaryRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun PrivacyResultCard(result: SettingsContract.PrivacyResult) {
    when (result) {
        is SettingsContract.PrivacyResult.Exported -> StatusCard(
            statusLabel = stringResource(R.string.settings_section_privacy),
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
            modifier = Modifier.testTag(SettingsTestTags.PRIVACY_RESULT),
            announcementMode = LiveRegionMode.Polite,
        )

        is SettingsContract.PrivacyResult.ImagesDeleted -> {
            val report = result.report
            val confirmedAbsent = report.draftDeleted + report.draftAlreadyAbsent +
                report.retentionDeleted + report.retentionAlreadyAbsent +
                report.forcedOcrDeleted + report.forcedOcrAlreadyAbsent
            val tone = when {
                !report.hasUnconfirmedLocalWork -> StatusTone.SUCCESS
                confirmedAbsent > 0 -> StatusTone.WARNING
                else -> StatusTone.ERROR
            }
            val deletionMessage = stringResource(
                R.string.settings_delete_images_result_message,
                report.draftDeleteAttempted,
                report.draftDeleted,
                report.draftAlreadyAbsent,
                report.draftDeleteFailed,
                report.retentionDeleteAttempted,
                report.retentionDeleted,
                report.retentionAlreadyAbsent,
                report.retentionDeleteFailed,
                report.purgeIntentDurable,
                report.purgeIntentNotRequired,
                report.purgeIntentFailed,
                report.orphanDocumentArtifactsDeleted,
                report.orphanDocumentArtifactsFailed,
                report.purgeIntentLegacyDestinationUnknown,
                report.forcedOcrDeleteAttempted,
                report.forcedOcrDeleted,
                report.forcedOcrAlreadyAbsent,
                report.forcedOcrDeleteFailed,
                report.failedSteps.size,
                report.unreferencedDraftImagesDeleted,
            )
            StatusCard(
                statusLabel = stringResource(R.string.settings_section_privacy),
                title = stringResource(
                    when (tone) {
                        StatusTone.SUCCESS -> R.string.settings_delete_images_success_title
                        StatusTone.WARNING -> R.string.settings_delete_images_partial_title
                        StatusTone.ERROR -> R.string.settings_delete_images_failure_title
                        StatusTone.NEUTRAL,
                        StatusTone.INFO,
                        -> R.string.settings_delete_images_success_title
                    },
                ),
                message = if (report.forceDeletionStillPending) {
                    deletionMessage + "\n\n" +
                        stringResource(R.string.settings_delete_images_retry_scheduled)
                } else {
                    deletionMessage
                },
                tone = tone,
                modifier = Modifier.testTag(SettingsTestTags.PRIVACY_RESULT),
                announcementMode = privacyResultAnnouncementMode(tone),
                iconRes = if (tone == StatusTone.SUCCESS) {
                    R.drawable.ic_check_circle
                } else {
                    R.drawable.ic_warning
                },
            )
        }

        is SettingsContract.PrivacyResult.FilesCleaned -> {
            val report = result.report
            val confirmedWork = report.staleImports + report.staleCacheEntries +
                report.stalePrivateTemps +
                report.orphanDraftDirs + report.unreferencedDraftImagesDeleted +
                report.committedOcrRuns + report.discardedOcrRuns + report.afterOcrDeleted +
                report.afterOcrAlreadyAbsent + report.retentionDeleted +
                report.retentionAlreadyAbsent + report.encryptedMigrated +
                report.encryptionAlreadySatisfied + report.orphanDocumentArtifactsDeleted
            val failed = report.afterOcrDeleteFailed + report.draftDeleteFailed +
                report.retentionDeleteFailed +
                report.purgeIntentFailed + report.purgeIntentLegacyDestinationUnknown +
                report.encryptionFailed +
                report.orphanDocumentArtifactsFailed + report.failedSteps.size
            val tone = when {
                failed == 0 -> StatusTone.SUCCESS
                confirmedWork > 0 -> StatusTone.WARNING
                else -> StatusTone.ERROR
            }
            StatusCard(
                statusLabel = stringResource(R.string.settings_section_privacy),
                title = stringResource(
                    when (tone) {
                        StatusTone.SUCCESS -> R.string.settings_cleanup_success_title
                        StatusTone.WARNING -> R.string.settings_cleanup_partial_title
                        StatusTone.ERROR -> R.string.settings_cleanup_failure_title
                        StatusTone.NEUTRAL,
                        StatusTone.INFO,
                        -> R.string.settings_cleanup_success_title
                    },
                ),
                message = stringResource(
                    R.string.settings_cleanup_success_message,
                    report.staleImports,
                    report.staleCacheEntries,
                    report.stalePrivateTemps,
                    report.orphanDraftDirs,
                    report.committedOcrRuns,
                    report.afterOcrDeleted,
                    report.afterOcrAlreadyAbsent,
                    report.afterOcrDeleteFailed,
                    report.retentionDeleted,
                    report.retentionAlreadyAbsent,
                    report.retentionDeleteFailed,
                    report.purgeIntentDurable,
                    report.purgeIntentNotRequired,
                    report.purgeIntentFailed,
                    report.encryptedMigrated,
                    report.encryptionAlreadySatisfied,
                    report.encryptionFailed,
                    report.failedSteps.size,
                    report.orphanDocumentArtifactsDeleted,
                    report.orphanDocumentArtifactsFailed,
                    report.purgeIntentLegacyDestinationUnknown,
                    report.unreferencedDraftImagesDeleted,
                    report.discardedOcrRuns,
                ),
                tone = tone,
                modifier = Modifier.testTag(SettingsTestTags.PRIVACY_RESULT),
                announcementMode = privacyResultAnnouncementMode(tone),
                iconRes = if (tone == StatusTone.SUCCESS) {
                    R.drawable.ic_check_circle
                } else {
                    R.drawable.ic_warning
                },
            )
        }

        is SettingsContract.PrivacyResult.RetentionPolicyUpdated -> {
            val report = result.report
            val failed = report?.let {
                it.afterOcrDeleteFailed + it.draftDeleteFailed + it.retentionDeleteFailed +
                    it.purgeIntentFailed + it.purgeIntentLegacyDestinationUnknown +
                    it.encryptionFailed +
                    it.orphanDocumentArtifactsFailed + it.failedSteps.size
            } ?: if (result.maintenanceAttempted) 1 else 0
            val confirmedAbsent = report?.let {
                it.afterOcrDeleted + it.afterOcrAlreadyAbsent + it.retentionDeleted +
                    it.retentionAlreadyAbsent
            } ?: 0
            val tone = when {
                failed == 0 -> StatusTone.SUCCESS
                confirmedAbsent > 0 -> StatusTone.WARNING
                else -> StatusTone.ERROR
            }
            StatusCard(
                statusLabel = stringResource(R.string.settings_section_privacy),
                title = stringResource(
                    when (tone) {
                        StatusTone.SUCCESS -> R.string.settings_retention_updated_title
                        StatusTone.WARNING -> R.string.settings_retention_partial_title
                        StatusTone.ERROR -> R.string.settings_retention_pending_title
                        StatusTone.NEUTRAL,
                        StatusTone.INFO,
                        -> R.string.settings_retention_updated_title
                    },
                ),
                message = if (report == null) {
                    stringResource(
                        if (result.maintenanceAttempted) {
                            R.string.settings_retention_maintenance_failed_message
                        } else {
                            R.string.settings_retention_keep_message
                        },
                        stringResource(result.policy.labelResource()),
                    )
                } else {
                    stringResource(
                        R.string.settings_retention_result_message,
                        stringResource(result.policy.labelResource()),
                        report.afterOcrDeleted,
                        report.afterOcrAlreadyAbsent,
                        report.afterOcrDeleteFailed,
                        report.retentionDeleted,
                        report.retentionAlreadyAbsent,
                        report.retentionDeleteFailed,
                        report.purgeIntentDurable,
                        report.purgeIntentNotRequired,
                        report.purgeIntentFailed,
                        report.purgeIntentLegacyDestinationUnknown,
                    )
                },
                tone = tone,
                modifier = Modifier.testTag(SettingsTestTags.PRIVACY_RESULT),
                announcementMode = privacyResultAnnouncementMode(tone),
                iconRes = if (tone == StatusTone.SUCCESS) {
                    R.drawable.ic_check_circle
                } else {
                    R.drawable.ic_warning
                },
            )
        }

        is SettingsContract.PrivacyResult.BackupUpdated -> StatusCard(
            statusLabel = stringResource(R.string.settings_section_privacy),
            title = stringResource(R.string.settings_backup_updated_title),
            message = stringResource(
                if (!result.schedulerUpdated) {
                    R.string.settings_backup_scheduler_warning
                } else if (result.enabled) {
                    R.string.settings_backup_enabled_message
                } else {
                    R.string.settings_backup_disabled_message
                },
            ),
            tone = if (result.schedulerUpdated) StatusTone.SUCCESS else StatusTone.WARNING,
            modifier = Modifier.testTag(SettingsTestTags.PRIVACY_RESULT),
            announcementMode = LiveRegionMode.Polite,
            iconRes = if (result.schedulerUpdated) {
                R.drawable.ic_check_circle
            } else {
                R.drawable.ic_warning
            },
        )

        SettingsContract.PrivacyResult.ExportDestinationCleanupUnconfirmed -> StatusCard(
            statusLabel = stringResource(R.string.settings_section_privacy),
            title = stringResource(R.string.settings_export_partial_title),
            message = stringResource(R.string.settings_export_partial_message),
            tone = StatusTone.ERROR,
            iconRes = R.drawable.ic_warning,
            modifier = Modifier.testTag(SettingsTestTags.PRIVACY_RESULT),
        )

        SettingsContract.PrivacyResult.Failed -> StatusCard(
            statusLabel = stringResource(R.string.settings_section_privacy),
            title = stringResource(R.string.settings_privacy_failure_title),
            message = stringResource(R.string.settings_privacy_failure_message),
            tone = StatusTone.ERROR,
            iconRes = R.drawable.ic_warning,
            modifier = Modifier.testTag(SettingsTestTags.PRIVACY_RESULT),
        )
    }
}

private fun privacyResultAnnouncementMode(tone: StatusTone): LiveRegionMode =
    if (tone == StatusTone.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite

@StringRes
private fun ImageRetentionPolicy.labelResource(): Int = when (this) {
    ImageRetentionPolicy.AFTER_OCR -> R.string.settings_retention_after_ocr
    ImageRetentionPolicy.AFTER_CONFIRM -> R.string.settings_retention_after_confirm
    ImageRetentionPolicy.DAYS_30 -> R.string.settings_retention_30_days
    ImageRetentionPolicy.DAYS_90 -> R.string.settings_retention_90_days
    ImageRetentionPolicy.KEEP -> R.string.settings_retention_keep
}

@Composable
private fun ReadOnlyRow(
    @StringRes labelRes: Int,
    @StringRes valueRes: Int,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(valueRes),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
