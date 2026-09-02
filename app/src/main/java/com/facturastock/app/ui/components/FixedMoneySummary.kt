package com.facturastock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun FixedMoneySummary(
    label: String,
    amount: String,
    spokenAmount: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    announceChanges: Boolean = false,
) {
    require(spokenAmount.isNotBlank())

    val spacing = FacturaStockDesign.spacing
    val useVerticalLayout = LocalDensity.current.fontScale >= 1.5f

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = spacing.xs,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            if (useVerticalLayout) {
                Column(
                    modifier = Modifier
                        .widthIn(max = spacing.contentMaxWidth)
                        .fillMaxWidth(),
                ) {
                    MoneySummaryText(
                        label = label,
                        amount = amount,
                        spokenAmount = spokenAmount,
                        announceChanges = announceChanges,
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    FacturaStockPrimaryButton(
                        text = actionLabel,
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = actionEnabled,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .widthIn(max = spacing.contentMaxWidth)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MoneySummaryText(
                        label = label,
                        amount = amount,
                        spokenAmount = spokenAmount,
                        announceChanges = announceChanges,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(spacing.md))
                    FacturaStockPrimaryButton(
                        text = actionLabel,
                        onClick = onAction,
                        enabled = actionEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoneySummaryText(
    label: String,
    amount: String,
    spokenAmount: String,
    announceChanges: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spokenAmount
            if (announceChanges) {
                liveRegion = LiveRegionMode.Polite
            }
        },
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(spacing.xxs))
        Text(
            text = amount,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
