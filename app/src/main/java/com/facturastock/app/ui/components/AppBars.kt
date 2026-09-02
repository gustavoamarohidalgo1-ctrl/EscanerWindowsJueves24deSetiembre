package com.facturastock.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun FacturaStockTopBar(
    title: String,
    modifier: Modifier = Modifier,
    contentMaxWidth: Dp? = null,
    compact: Boolean = false,
    @DrawableRes navigationIconRes: Int? = null,
    navigationContentDescription: String? = null,
    onNavigationClick: (() -> Unit)? = null,
    @DrawableRes actionIconRes: Int? = null,
    actionContentDescription: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val spacing = FacturaStockDesign.spacing
    val resolvedContentMaxWidth = contentMaxWidth ?: spacing.contentMaxWidth
    val minimumHeight = if (compact) {
        spacing.minimumTouchTarget
    } else {
        spacing.topBarMinHeight
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(FacturaStockNavigationTestTags.TOP)
            .semantics { isTraversalGroup = true },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = spacing.borderThin,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = resolvedContentMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = minimumHeight)
                    .padding(
                        horizontal = spacing.md,
                        vertical = if (compact) spacing.xxs else spacing.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (
                    navigationIconRes != null &&
                    navigationContentDescription != null &&
                    onNavigationClick != null
                ) {
                    IconButton(
                        onClick = onNavigationClick,
                        modifier = Modifier.sizeIn(
                            minWidth = spacing.minimumTouchTarget,
                            minHeight = spacing.minimumTouchTarget,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(navigationIconRes),
                            contentDescription = navigationContentDescription,
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.xs))
                }

                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = if (compact) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (
                    actionIconRes != null &&
                    actionContentDescription != null &&
                    onActionClick != null
                ) {
                    Spacer(modifier = Modifier.width(spacing.xs))
                    IconButton(
                        onClick = onActionClick,
                        modifier = Modifier.sizeIn(
                            minWidth = spacing.minimumTouchTarget,
                            minHeight = spacing.minimumTouchTarget,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(actionIconRes),
                            contentDescription = actionContentDescription,
                        )
                    }
                }
            }
        }
    }
}

@Immutable
data class FacturaStockBottomItem(
    val label: String,
    @param:DrawableRes val iconRes: Int,
)

object FacturaStockNavigationTestTags {
    const val TOP = "facturastock_top_bar"
    const val BOTTOM = "facturastock_bottom_navigation"
    const val RAIL = "facturastock_navigation_rail"
}

@Composable
fun FacturaStockBottomNavigation(
    items: List<FacturaStockBottomItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    require(items.size in 2..5)
    require(selectedIndex in items.indices)

    val spacing = FacturaStockDesign.spacing

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(FacturaStockNavigationTestTags.BOTTOM)
            .semantics { isTraversalGroup = true },
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = spacing.xxs,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            items.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                val contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val containerColor = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = spacing.xxs / 2, vertical = spacing.xxs),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = spacing.minimumTouchTarget)
                            .selectable(
                                selected = selected,
                                onClick = { onItemSelected(index) },
                                role = Role.Tab,
                            ),
                        shape = MaterialTheme.shapes.medium,
                        color = containerColor,
                        contentColor = contentColor,
                        border = if (selected) {
                            BorderStroke(
                                spacing.borderThin * 2,
                                MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            null
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = spacing.xxs,
                                vertical = if (compact) spacing.xxs else spacing.xs,
                            ),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (!compact) {
                                Icon(
                                    painter = painterResource(item.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(spacing.icon),
                                )
                                Spacer(modifier = Modifier.height(spacing.xxs))
                            }
                            Text(
                                text = item.label,
                                style = if (compact) {
                                    MaterialTheme.typography.labelSmall
                                } else {
                                    MaterialTheme.typography.labelMedium
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Navegación persistente para tablet y ventanas amplias. Mantiene los mismos nombres, orden y
 * roles que la barra inferior para que cambiar el tamaño de la ventana no cambie el modelo mental.
 */
@Composable
fun FacturaStockNavigationRail(
    items: List<FacturaStockBottomItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    brandLabel: String,
    @DrawableRes brandIconRes: Int,
    modifier: Modifier = Modifier,
) {
    require(items.size in 2..5)
    require(selectedIndex in items.indices)

    val spacing = FacturaStockDesign.spacing

    Surface(
        modifier = modifier
            .width(spacing.navigationRailWidth)
            .fillMaxHeight()
            .testTag(FacturaStockNavigationTestTags.RAIL)
            .semantics { isTraversalGroup = true },
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = spacing.borderThin,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top +
                            WindowInsetsSides.Bottom +
                            WindowInsetsSides.Start,
                    ),
                )
                .padding(horizontal = spacing.xs, vertical = spacing.xs),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(brandIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(spacing.iconEmphasis),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(spacing.xxs))
                Text(
                    text = brandLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                items.forEachIndexed { index, item ->
                    val selected = selectedIndex == index
                    val contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = spacing.minimumTouchTarget)
                            .selectable(
                                selected = selected,
                                onClick = { onItemSelected(index) },
                                role = Role.Tab,
                            ),
                        shape = MaterialTheme.shapes.large,
                        color = containerColor,
                        contentColor = contentColor,
                        border = if (selected) {
                            BorderStroke(
                                spacing.borderThin * 2,
                                MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            null
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = spacing.xxs,
                                vertical = spacing.xxs,
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                painter = painterResource(item.iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(spacing.icon),
                            )
                            Spacer(modifier = Modifier.height(spacing.xxs))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
