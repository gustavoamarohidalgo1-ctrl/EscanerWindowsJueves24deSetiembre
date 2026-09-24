package com.facturastock.app.feature.home

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.facturastock.app.domain.model.HomeDashboardSnapshot
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

private val DashboardWideBreakpoint = 720.dp
private const val DashboardLargeFontScale = 1.5f

@Composable
internal fun homeDashboardUsesSingleColumn(maxWidth: Dp): Boolean =
    maxWidth < DashboardWideBreakpoint ||
        LocalDensity.current.fontScale >= DashboardLargeFontScale

/**
 * Inicio deliberadamente breve: primero muestra el estado real del negocio y después las dos
 * operaciones diarias. El resto son accesos secundarios sin mensajes introductorios.
 */
internal fun LazyListScope.homeDashboardItems(
    dashboard: HomeDashboardSnapshot?,
    singleColumn: Boolean,
    isCreatingDraft: Boolean,
    onScanInvoice: () -> Unit,
    onNewSale: () -> Unit,
    onOpenProducts: () -> Unit,
    onOpenPurchases: () -> Unit,
    onOpenDebtors: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    item(key = "dashboard_header", contentType = "dashboard_header") {
        BusinessHeader(dashboard)
    }

    dashboard?.let { snapshot ->
        item(key = "dashboard_summary", contentType = "dashboard_summary") {
            BusinessSummary(
                dashboard = snapshot,
                singleColumn = singleColumn,
                onOpenInventory = onOpenInventory,
            )
        }
    }

    item(key = "dashboard_actions", contentType = "dashboard_actions") {
        MainActions(
            singleColumn = singleColumn,
            canSell = dashboard?.overview?.inventory?.availableProductCount?.let { it > 0 }
                ?: true,
            isCreatingDraft = isCreatingDraft,
            isNewBusiness = dashboard?.isNewBusiness == true,
            onScanInvoice = onScanInvoice,
            onNewSale = onNewSale,
        )
    }

    item(key = "dashboard_shortcuts", contentType = "dashboard_shortcuts") {
        SecondaryLinks(
            singleColumn = singleColumn,
            onOpenProducts = onOpenProducts,
            onOpenPurchases = onOpenPurchases,
            onOpenDebtors = onOpenDebtors,
        )
    }

    if (dashboard != null && dashboard.overview.purchases.syncProblemCount > 0) {
        item(key = "dashboard_sync_issue", contentType = "dashboard_issue") {
            SyncIssue(
                count = dashboard.overview.purchases.syncProblemCount,
                onClick = onOpenPurchases,
            )
        }
    }

}

private val HomeDashboardSnapshot.isNewBusiness: Boolean
    get() = overview.inventory.productCount == 0 &&
        overview.purchases.postedCount == 0 &&
        overview.drafts.openCount == 0

@Composable
private fun BusinessHeader(dashboard: HomeDashboardSnapshot?) {
    val spacing = FacturaStockDesign.spacing
    val businessName = dashboard?.business?.tradeName?.takeIf { it.isNotBlank() }
        ?: dashboard?.business?.legalName?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.home_dashboard_business_fallback)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HomeTestTags.DASHBOARD_HEADER),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                text = businessName,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineMedium,
            )
            dashboard?.let { snapshot ->
                Text(
                    text = snapshot.observedAt.atZone(snapshot.zoneId).toLocalDate()
                        .formatForDisplay(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (dashboard?.isDemoMode == true) {
            Text(
                text = stringResource(Res.string.home_dashboard_demo_mode),
                modifier = Modifier.padding(start = spacing.sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private data class SummaryValue(
    val value: String,
    val labelRes: StringResource,
    val warning: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun BusinessSummary(
    dashboard: HomeDashboardSnapshot,
    singleColumn: Boolean,
    onOpenInventory: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val inventory = dashboard.overview.inventory
    val summaries = listOf(
        SummaryValue(
            value = stringResource(
                Res.string.home_summary_stock_value,
                inventory.availableProductCount,
                inventory.productCount,
            ),
            labelRes = Res.string.home_summary_stock,
            onClick = onOpenInventory,
        ),
        SummaryValue(
            value = inventory.attentionProductCount.toString(),
            labelRes = Res.string.home_summary_attention,
            warning = inventory.attentionProductCount > 0,
            onClick = onOpenInventory,
        ),
        SummaryValue(
            value = dashboard.overview.drafts.openCount.toString(),
            labelRes = Res.string.home_dashboard_metric_drafts,
        ),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HomeTestTags.PULSE_GRID),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (singleColumn) {
            Column(modifier = Modifier.padding(horizontal = spacing.md)) {
                summaries.forEachIndexed { index, summary ->
                    SummaryRow(summary)
                    if (index != summaries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.Top,
            ) {
                summaries.forEach { summary ->
                    SummaryCell(summary, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(summary: SummaryValue) {
    val spacing = FacturaStockDesign.spacing
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(summary.labelRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = summary.value,
                color = if (summary.warning) {
                    FacturaStockDesign.semanticColors.warning
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    if (summary.onClick == null) {
        content()
    } else {
        Surface(
            onClick = summary.onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.minimumTouchTarget)
                .semantics { role = Role.Button },
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    }
}

@Composable
private fun SummaryCell(summary: SummaryValue, modifier: Modifier) {
    val spacing = FacturaStockDesign.spacing
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                text = summary.value,
                color = if (summary.warning) {
                    FacturaStockDesign.semanticColors.warning
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(summary.labelRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    if (summary.onClick == null) {
        Box(modifier = modifier) { content() }
    } else {
        Surface(
            onClick = summary.onClick,
            modifier = modifier
                .heightIn(min = spacing.minimumTouchTarget)
                .semantics { role = Role.Button },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    }
}

@Composable
private fun MainActions(
    singleColumn: Boolean,
    canSell: Boolean,
    isCreatingDraft: Boolean,
    isNewBusiness: Boolean,
    onScanInvoice: () -> Unit,
    onNewSale: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val modifier = if (isNewBusiness) {
        Modifier.testTag(HomeTestTags.GETTING_STARTED)
    } else {
        Modifier
    }
    val sale: @Composable (Modifier) -> Unit = { itemModifier ->
        if (canSell) {
            Button(
                onClick = onNewSale,
                modifier = itemModifier
                    .heightIn(min = spacing.comfortableTouchTarget)
                    .semantics { role = Role.Button }
                    .testTag(HomeTestTags.SALES_CTA),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
            ) {
                ActionContent(Res.drawable.ic_sale, Res.string.action_new_sale)
            }
        } else {
            OutlinedButton(
                onClick = onNewSale,
                modifier = itemModifier
                    .heightIn(min = spacing.comfortableTouchTarget)
                    .semantics { role = Role.Button }
                    .testTag(HomeTestTags.SALES_CTA),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
            ) {
                ActionContent(Res.drawable.ic_sale, Res.string.action_new_sale)
            }
        }
    }
    val invoice: @Composable (Modifier) -> Unit = { itemModifier ->
        if (canSell) {
            OutlinedButton(
                onClick = onScanInvoice,
                enabled = !isCreatingDraft,
                modifier = itemModifier
                    .heightIn(min = spacing.comfortableTouchTarget)
                    .semantics { role = Role.Button }
                    .testTag(HomeTestTags.SCAN_CTA),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
            ) {
                ActionContent(Res.drawable.ic_add_document, Res.string.action_scan_invoice)
            }
        } else {
            Button(
                onClick = onScanInvoice,
                enabled = !isCreatingDraft,
                modifier = itemModifier
                    .heightIn(min = spacing.comfortableTouchTarget)
                    .semantics { role = Role.Button }
                    .testTag(HomeTestTags.SCAN_CTA),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
            ) {
                ActionContent(Res.drawable.ic_add_document, Res.string.action_scan_invoice)
            }
        }
    }

    if (singleColumn) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            sale(Modifier.fillMaxWidth())
            invoice(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            sale(Modifier.weight(1f))
            invoice(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionContent(iconRes: DrawableResource, labelRes: StringResource) {
    val spacing = FacturaStockDesign.spacing
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(spacing.icon),
    )
    Text(
        text = stringResource(labelRes),
        modifier = Modifier.padding(start = spacing.xs),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun SecondaryLinks(
    singleColumn: Boolean,
    onOpenProducts: () -> Unit,
    onOpenPurchases: () -> Unit,
    onOpenDebtors: () -> Unit,
) {
    val links: @Composable (Modifier) -> Unit = { modifier ->
        SecondaryLink(
            iconRes = Res.drawable.ic_products,
            labelRes = Res.string.home_shortcut_products,
            tag = HomeTestTags.SHORTCUT_PRODUCTS,
            onClick = onOpenProducts,
            modifier = modifier,
        )
        SecondaryLink(
            iconRes = Res.drawable.ic_receipt,
            labelRes = Res.string.action_view_purchases,
            tag = HomeTestTags.SHORTCUT_PURCHASES,
            onClick = onOpenPurchases,
            modifier = modifier,
        )
        SecondaryLink(
            iconRes = Res.drawable.ic_debtors,
            labelRes = Res.string.home_shortcut_debtors,
            tag = HomeTestTags.SHORTCUT_DEBTORS,
            onClick = onOpenDebtors,
            modifier = modifier,
        )
    }
    if (singleColumn) {
        Column(modifier = Modifier.fillMaxWidth()) {
            links(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            links(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SecondaryLink(
    iconRes: DrawableResource,
    labelRes: StringResource,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = spacing.minimumTouchTarget)
            .semantics { role = Role.Button }
            .testTag(tag),
        shape = MaterialTheme.shapes.small,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(spacing.iconSmall),
        )
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.padding(start = spacing.xs),
        )
    }
}

@Composable
private fun SyncIssue(count: Int, onClick: () -> Unit) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .semantics { role = Role.Button }
            .testTag(HomeTestTags.ATTENTION_PANEL),
        shape = MaterialTheme.shapes.small,
        color = FacturaStockDesign.semanticColors.warningContainer,
        contentColor = FacturaStockDesign.semanticColors.onWarningContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier.size(spacing.iconSmall),
            )
            Text(
                text = pluralStringResource(Res.plurals.home_sync_issues, count, count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
