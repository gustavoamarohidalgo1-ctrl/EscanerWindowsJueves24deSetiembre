package com.facturastock.app.ui.components

import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun FacturaStockPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIconRes: DrawableResource? = null,
) {
    val spacing = FacturaStockDesign.spacing

    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = spacing.comfortableTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(
            horizontal = spacing.lg,
            vertical = spacing.md,
        ),
    ) {
        if (leadingIconRes != null) {
            Icon(
                painter = painterResource(leadingIconRes),
                contentDescription = null,
                modifier = Modifier.size(spacing.icon),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun FacturaStockSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIconRes: DrawableResource? = null,
    leadingIconPainter: Painter? = null,
) {
    val spacing = FacturaStockDesign.spacing
    val resourcePainter = if (leadingIconPainter == null && leadingIconRes != null) {
        painterResource(leadingIconRes)
    } else {
        null
    }
    val resolvedLeadingIcon = leadingIconPainter ?: resourcePainter

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = spacing.comfortableTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(
            horizontal = spacing.lg,
            vertical = spacing.md,
        ),
    ) {
        if (resolvedLeadingIcon != null) {
            Icon(
                painter = resolvedLeadingIcon,
                contentDescription = null,
                modifier = Modifier.size(spacing.icon),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}
