package com.facturastock.app.feature.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.facturastock.app.R
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.theme.FacturaStockDesign

/** Inicio directo del flujo para registrar una factura de compra. */
@Composable
fun InvoiceHubScreen(
    isCreating: Boolean,
    creationFailed: Boolean,
    onRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(InvoiceHubTestTags.SCREEN),
        contentPadding = PaddingValues(
            horizontal = spacing.lg,
            vertical = spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "invoice_hub_primary", contentType = "primary") {
            ConstrainedSection {
                InvoicePrimarySection(
                    isCreating = isCreating,
                    creationFailed = creationFailed,
                    onRegister = onRegister,
                )
            }
        }
        item(key = "invoice_hub_two_steps", contentType = "guide") {
            ConstrainedSection {
                TwoStepGuide()
            }
        }
        item(key = "invoice_hub_inventory_notice", contentType = "notice") {
            ConstrainedSection {
                CatalogOnlyNotice()
            }
        }
    }
}

@Composable
private fun ConstrainedSection(content: @Composable () -> Unit) {
    val spacing = FacturaStockDesign.spacing

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = spacing.contentMaxWidth),
        ) {
            content()
        }
    }
}

@Composable
private fun InvoicePrimarySection(
    isCreating: Boolean,
    creationFailed: Boolean,
    onRegister: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceHubTestTags.HERO),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RegisterButton(
            isCreating = isCreating,
            creationFailed = creationFailed,
            onRegister = onRegister,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isCreating) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvoiceHubTestTags.CREATION_PROGRESS),
            )
        } else if (creationFailed) {
            CreationError()
        }
    }
}

@Composable
private fun RegisterButton(
    isCreating: Boolean,
    creationFailed: Boolean,
    onRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FacturaStockPrimaryButton(
        text = stringResource(
            when {
                isCreating -> R.string.invoice_hub_register_working
                creationFailed -> R.string.invoice_hub_retry_action
                else -> R.string.invoice_hub_register_action
            },
        ),
        onClick = onRegister,
        modifier = modifier.testTag(InvoiceHubTestTags.REGISTER_ACTION),
        enabled = !isCreating,
        leadingIconRes = if (isCreating) null else R.drawable.ic_camera,
    )
}

@Composable
private fun CreationError() {
    val spacing = FacturaStockDesign.spacing

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(InvoiceHubTestTags.CREATION_ERROR),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier.size(spacing.icon),
            )
            Text(
                text = stringResource(R.string.invoice_hub_error_message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TwoStepGuide() {
    val spacing = FacturaStockDesign.spacing

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceHubTestTags.TWO_STEP_GUIDE),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.invoice_hub_guide_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.invoice_hub_guide_open_camera),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.invoice_hub_guide_take_photo),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CatalogOnlyNotice() {
    val spacing = FacturaStockDesign.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(InvoiceHubTestTags.CATALOG_ONLY_NOTICE),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            modifier = Modifier.size(spacing.icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.invoice_hub_inventory_notice),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
