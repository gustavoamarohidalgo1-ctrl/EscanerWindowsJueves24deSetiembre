package com.facturastock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.facturastock.app.ui.theme.FacturaStockDesign
import com.facturastock.app.core.input.suppressScannerTrailingKeys

@Composable
fun FacturaStockDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestDismiss: () -> Unit = onDismiss,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = {
            if (dismissEnabled) onRequestDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .suppressScannerTrailingKeys()
                .fillMaxSize()
                .padding(FacturaStockDesign.spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            FacturaStockDialogContent(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                dismissLabel = dismissLabel,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
                confirmEnabled = confirmEnabled,
                dismissEnabled = dismissEnabled,
                content = content,
                modifier = modifier,
            )
        }
    }
}

@Composable
internal fun FacturaStockDialogContent(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val spacing = FacturaStockDesign.spacing

    Surface(
        modifier = modifier
            .widthIn(max = spacing.dialogMaxWidth)
            .fillMaxWidth()
            .semantics {
                paneTitle = title
                isTraversalGroup = true
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = spacing.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(spacing.md))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (content != null) {
                    Spacer(modifier = Modifier.height(spacing.md))
                    content()
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            FacturaStockPrimaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            FacturaStockSecondaryButton(
                text = dismissLabel,
                onClick = onDismiss,
                enabled = dismissEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
