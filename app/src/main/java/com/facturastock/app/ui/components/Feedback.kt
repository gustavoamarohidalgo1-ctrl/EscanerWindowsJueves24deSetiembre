package com.facturastock.app.ui.components

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.facturastock.app.ui.theme.FacturaStockDesign

enum class StatusTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

@Immutable
private data class StatusColors(
    val container: Color,
    val content: Color,
    val border: Color,
)

@Composable
private fun statusColors(tone: StatusTone): StatusColors {
    val material = MaterialTheme.colorScheme
    val semantic = FacturaStockDesign.semanticColors
    return when (tone) {
        StatusTone.NEUTRAL -> StatusColors(
            container = material.surfaceVariant,
            content = material.onSurfaceVariant,
            border = material.outlineVariant,
        )
        StatusTone.INFO -> StatusColors(
            container = semantic.infoContainer,
            content = semantic.onInfoContainer,
            border = semantic.info,
        )
        StatusTone.SUCCESS -> StatusColors(
            container = semantic.successContainer,
            content = semantic.onSuccessContainer,
            border = semantic.success,
        )
        StatusTone.WARNING -> StatusColors(
            container = semantic.warningContainer,
            content = semantic.onWarningContainer,
            border = semantic.warning,
        )
        StatusTone.ERROR -> StatusColors(
            container = material.errorContainer,
            content = material.onErrorContainer,
            border = material.error,
        )
    }
}

@Composable
fun StatusCard(
    statusLabel: String,
    title: String,
    message: String,
    tone: StatusTone,
    iconRes: DrawableResource,
    modifier: Modifier = Modifier,
    /** Anuncio para resultados que aparecen tras una operación; null evita anunciar contenido inicial. */
    announcementMode: LiveRegionMode? = if (tone == StatusTone.ERROR) {
        LiveRegionMode.Assertive
    } else {
        null
    },
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val spacing = FacturaStockDesign.spacing
    val colors = statusColors(tone)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                announcementMode?.let { liveRegion = it }
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.container),
        border = BorderStroke(spacing.borderThin, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(spacing.iconContainer),
                shape = MaterialTheme.shapes.medium,
                color = colors.content.copy(alpha = 0.12f),
                contentColor = colors.content,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(spacing.icon),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text(
                    text = statusLabel,
                    color = colors.content,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = title,
                    color = colors.content,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = message,
                    color = colors.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
                supportingContent?.let {
                    Spacer(modifier = Modifier.height(spacing.xs))
                    it()
                }
            }
        }
    }
}

@Composable
fun ConfidenceChip(
    label: String,
    percentage: Int,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    require(percentage in 0..100)

    val spacing = FacturaStockDesign.spacing
    val colors = statusColors(tone)
    val visibleText = stringResource(Res.string.confidence_value, label, percentage)
    val spokenText = stringResource(
        Res.string.confidence_description,
        label,
        percentage.toString(),
    )

    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spokenText
        },
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(spacing.borderThin, colors.border),
    ) {
        Text(
            text = visibleText,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun RecoverableError(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
        border = BorderStroke(spacing.borderThin, colors.error.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(spacing.iconContainer),
                    shape = MaterialTheme.shapes.medium,
                    color = colors.onErrorContainer.copy(alpha = 0.12f),
                    contentColor = colors.onErrorContainer,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_warning),
                            contentDescription = null,
                            modifier = Modifier.size(spacing.icon),
                        )
                    }
                }
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    color = colors.onErrorContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = message,
                color = colors.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            FacturaStockSecondaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                leadingIconRes = Res.drawable.ic_refresh,
            )
        }
    }
}

@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(spacing.iconLarge))
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconRes: DrawableResource = Res.drawable.ic_info,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    val spacing = FacturaStockDesign.spacing

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = spacing.xxs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(spacing.iconContainer),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(spacing.iconEmphasis),
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(spacing.lg))
                FacturaStockPrimaryButton(
                    text = actionLabel,
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier
                        .widthIn(max = spacing.dialogMaxWidth)
                        .fillMaxWidth(),
                )
            }
        }
    }
}
