package com.facturastock.app.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            OnboardingContract.Effect.OnboardingCompleted -> onFinished()
        }
    }

    OnboardingScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}

/**
 * Formulario inicial directo: conserva toda la configuración y sus validaciones, pero elimina
 * contenido introductorio, progreso, resúmenes duplicados y contenedores decorativos.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingContract.State,
    onAction: (OnboardingContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ONBOARDING_CONTENT_MAX_WIDTH)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.lg)
                .testTag(OnboardingTestTags.SCREEN),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OnboardingSectionTitle(R.string.onboarding_section_business)

            OutlinedTextField(
                value = state.businessName,
                onValueChange = { value ->
                    onAction(
                        OnboardingContract.Action.BusinessNameChanged(
                            value.take(OnboardingContract.BUSINESS_NAME_MAX_LENGTH),
                        ),
                    )
                },
                label = { Text(stringResource(R.string.onboarding_business_name_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.onboarding_business_name_help_counter,
                            state.businessName.length,
                            OnboardingContract.BUSINESS_NAME_MAX_LENGTH,
                        ),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.BUSINESS_NAME),
            )

            OutlinedTextField(
                value = state.ruc,
                onValueChange = { newValue ->
                    onAction(
                        OnboardingContract.Action.RucChanged(
                            newValue.filter(Char::isDigit).take(11),
                        ),
                    )
                },
                label = { Text(stringResource(R.string.onboarding_ruc_label)) },
                singleLine = true,
                isError = !state.isRucWellFormed,
                supportingText = if (!state.isRucWellFormed) {
                    { Text(stringResource(R.string.ruc_error_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.RUC),
            )

            OnboardingDivider()
            OnboardingSectionTitle(R.string.onboarding_section_tax)

            OutlinedTextField(
                value = state.taxRatePercent,
                onValueChange = { newValue ->
                    onAction(
                        OnboardingContract.Action.TaxRateChanged(
                            newValue.filter { it.isDigit() || it == '.' }.take(6),
                        ),
                    )
                },
                label = { Text(stringResource(R.string.tax_rate_label)) },
                singleLine = true,
                isError = !state.isTaxRateValid,
                supportingText = if (!state.isTaxRateValid) {
                    { Text(stringResource(R.string.tax_rate_error_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.TAX_RATE),
            )

            Text(
                text = stringResource(R.string.cost_policy_label),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                CostPolicyOption(
                    selected = state.costPolicy == CostPolicy.NET,
                    labelRes = R.string.cost_policy_net,
                    onSelect = {
                        onAction(OnboardingContract.Action.CostPolicySelected(CostPolicy.NET))
                    },
                    modifier = Modifier.testTag(OnboardingTestTags.COST_NET),
                )
                CostPolicyOption(
                    selected = state.costPolicy == CostPolicy.GROSS,
                    labelRes = R.string.cost_policy_gross,
                    onSelect = {
                        onAction(OnboardingContract.Action.CostPolicySelected(CostPolicy.GROSS))
                    },
                    modifier = Modifier.testTag(OnboardingTestTags.COST_GROSS),
                )
            }

            OnboardingDivider()
            OnboardingSectionTitle(R.string.onboarding_section_inventory)

            OutlinedTextField(
                value = state.warehouseName,
                onValueChange = { value ->
                    onAction(
                        OnboardingContract.Action.WarehouseNameChanged(
                            value.take(OnboardingContract.WAREHOUSE_NAME_MAX_LENGTH),
                        ),
                    )
                },
                label = { Text(stringResource(R.string.onboarding_warehouse_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.onboarding_warehouse_help_counter,
                            state.warehouseName.length,
                            OnboardingContract.WAREHOUSE_NAME_MAX_LENGTH,
                        ),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.WAREHOUSE),
            )

            if (state.rucChecksumWarning) {
                StatusCard(
                    statusLabel = stringResource(R.string.ruc_checksum_warning_label),
                    title = stringResource(R.string.ruc_checksum_warning_title),
                    message = stringResource(R.string.ruc_checksum_warning),
                    tone = StatusTone.WARNING,
                    iconRes = R.drawable.ic_warning,
                )
            }

            if (state.failure != null) {
                RecoverableError(
                    title = stringResource(R.string.save_error_title),
                    message = stringResource(R.string.save_error_message),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(OnboardingContract.Action.Save) },
                )
            } else {
                FacturaStockPrimaryButton(
                    text = stringResource(
                        if (state.isSaving) {
                            R.string.action_saving
                        } else {
                            R.string.action_start_onboarding
                        },
                    ),
                    onClick = { onAction(OnboardingContract.Action.Save) },
                    enabled = state.canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OnboardingTestTags.SUBMIT),
                )
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
    }
}

@Composable
private fun OnboardingSectionTitle(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun OnboardingDivider() {
    val spacing = FacturaStockDesign.spacing
    HorizontalDivider(
        modifier = Modifier.padding(vertical = spacing.xs),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun CostPolicyOption(
    selected: Boolean,
    @StringRes labelRes: Int,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = spacing.xs),
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

private val ONBOARDING_CONTENT_MAX_WIDTH = 640.dp
