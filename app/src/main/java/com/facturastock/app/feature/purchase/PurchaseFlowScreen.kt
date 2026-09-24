package com.facturastock.app.feature.purchase

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun PurchaseFlowScreen(
    titleRes: StringResource,
    messageRes: StringResource,
    primaryActionRes: StringResource,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    step: Int? = null,
    totalSteps: Int = PURCHASE_EDITABLE_STEP_COUNT,
    iconRes: DrawableResource = Res.drawable.ic_receipt,
    tone: StatusTone = StatusTone.INFO,
    primaryActionEnabled: Boolean = true,
    showPreviousAction: Boolean = false,
    onPreviousAction: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    val title = stringResource(titleRes)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (step != null) {
            item {
                Text(
                    text = stringResource(Res.string.purchase_flow_step, step, totalSteps),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        item {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        item {
            StatusCard(
                statusLabel = step?.let {
                    stringResource(Res.string.purchase_flow_step, it, totalSteps)
                } ?: title,
                title = title,
                message = stringResource(messageRes),
                tone = tone,
                iconRes = iconRes,
            )
        }
        item {
            FacturaStockPrimaryButton(
                text = stringResource(primaryActionRes),
                onClick = onPrimaryAction,
                modifier = Modifier.fillMaxWidth(),
                enabled = primaryActionEnabled,
            )
        }
        if (showPreviousAction) {
            item {
                FacturaStockSecondaryButton(
                    text = stringResource(Res.string.action_previous_step),
                    onClick = onPreviousAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

const val PURCHASE_EDITABLE_STEP_COUNT = 9
