package com.facturastock.app.feature.reports

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.facturastock.app.R
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.SalesReportTotals
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.format.formatCurrencyAmountForDisplay
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.math.RoundingMode

private val ReportsWideBreakpoint = 760.dp
private const val ReportsLargeFontScale = 1.4f

@Composable
fun ReportsScreen(
    state: ReportsContract.State,
    onAction: (ReportsContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val singleColumn = reportsUseSingleColumn(maxWidth)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ReportsTestTags.SCREEN),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "reports_period", contentType = "period_selector") {
                ReportsConstrainedSection {
                    PeriodSelector(
                        selected = state.selectedPeriod,
                        onSelect = { onAction(ReportsContract.Action.PeriodSelected(it)) },
                    )
                }
            }

            when {
                state.isLoading && state.report == null -> item(
                    key = "reports_loading",
                    contentType = "loading",
                ) {
                    ReportsConstrainedSection {
                        LoadingState(
                            message = stringResource(R.string.reports_loading),
                            modifier = Modifier.testTag(ReportsTestTags.LOADING),
                        )
                    }
                }

                state.failure != null && state.report == null -> item(
                    key = "reports_error",
                    contentType = "error",
                ) {
                    ReportsConstrainedSection {
                        RetryNotice(
                            text = stringResource(R.string.reports_error_message),
                            onRetry = { onAction(ReportsContract.Action.Retry) },
                            isError = true,
                            modifier = Modifier.testTag(ReportsTestTags.ERROR),
                        )
                    }
                }

                state.report != null -> {
                    val report = requireNotNull(state.report)

                    item(key = "reports_range", contentType = "range") {
                        ReportsConstrainedSection { ReportRange(report.range) }
                    }
                    if (state.isRefreshing) {
                        item(key = "reports_refreshing", contentType = "progress") {
                            ReportsConstrainedSection {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (state.failure != null) {
                        item(key = "reports_stale", contentType = "warning") {
                            ReportsConstrainedSection {
                                RetryNotice(
                                    text = stringResource(R.string.reports_stale_message),
                                    onRetry = { onAction(ReportsContract.Action.Retry) },
                                    isError = false,
                                    modifier = Modifier.testTag(ReportsTestTags.STALE_NOTICE),
                                )
                            }
                        }
                    }
                    item(key = "reports_hero", contentType = "profit_hero") {
                        ReportsConstrainedSection {
                            GrossProfitHero(report.primaryTotals)
                        }
                    }
                    item(key = "reports_metrics", contentType = "metrics") {
                        ReportsConstrainedSection {
                            PrimaryMetrics(
                                totals = report.primaryTotals,
                                singleColumn = singleColumn,
                            )
                        }
                    }
                    if (report.primaryTotals.grossProfit == null) {
                        item(key = "reports_profit_issue", contentType = "warning") {
                            ReportsConstrainedSection {
                                ProfitUnavailableNotice(
                                    issues = report.primaryTotals.issues,
                                    modifier = Modifier.testTag(
                                        ReportsTestTags.PROFIT_UNAVAILABLE,
                                    ),
                                )
                            }
                        }
                    }
                    if (report.totalsByCurrency.size > 1) {
                        item(key = "reports_other_currencies", contentType = "currency_totals") {
                            ReportsConstrainedSection {
                                OtherCurrencies(
                                    report = report,
                                    singleColumn = singleColumn,
                                )
                            }
                        }
                    }
                    if (report.sales.isEmpty()) {
                        item(key = "reports_empty", contentType = "empty") {
                            ReportsConstrainedSection { EmptyReport() }
                        }
                    } else {
                        item(key = "reports_sales_header", contentType = "section_header") {
                            ReportsConstrainedSection {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.reports_sale_count,
                                        report.sales.size,
                                        report.sales.size,
                                    ),
                                    modifier = Modifier
                                        .semantics { heading() }
                                        .testTag(ReportsTestTags.SALES_LIST),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                        items(
                            items = report.sales,
                            key = { it.saleId.value },
                            contentType = { "reported_sale" },
                        ) { sale ->
                            ReportsConstrainedSection {
                                SaleProfitRow(
                                    sale = sale,
                                    range = report.range,
                                    singleColumn = singleColumn,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun reportsUseSingleColumn(maxWidth: Dp): Boolean =
    maxWidth < ReportsWideBreakpoint || LocalDensity.current.fontScale >= ReportsLargeFontScale

@Composable
private fun ReportsConstrainedSection(content: @Composable () -> Unit) {
    val spacing = FacturaStockDesign.spacing
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = spacing.contentWideMaxWidth),
        ) {
            content()
        }
    }
}

@Composable
private fun PeriodSelector(
    selected: SalesReportPeriod,
    onSelect: (SalesReportPeriod) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .testTag(ReportsTestTags.PERIOD_SELECTOR),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        SalesReportPeriod.entries.forEach { period ->
            val isSelected = selected == period
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = spacing.minimumTouchTarget)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(period) },
                    )
                    .testTag(period.testTag()),
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.minimumTouchTarget)
                        .padding(horizontal = spacing.xs, vertical = spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = selectedPeriodLabel(period),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun selectedPeriodLabel(period: SalesReportPeriod): String = stringResource(
    when (period) {
        SalesReportPeriod.DAY -> R.string.reports_period_day
        SalesReportPeriod.WEEK -> R.string.reports_period_week
        SalesReportPeriod.MONTH -> R.string.reports_period_month
    },
)

private fun SalesReportPeriod.testTag(): String = when (this) {
    SalesReportPeriod.DAY -> ReportsTestTags.PERIOD_DAY
    SalesReportPeriod.WEEK -> ReportsTestTags.PERIOD_WEEK
    SalesReportPeriod.MONTH -> ReportsTestTags.PERIOD_MONTH
}

@Composable
private fun ReportRange(range: SalesReportRange) {
    val start = range.startInclusive.atZone(range.zoneId).toLocalDate()
    val end = range.endExclusive.atZone(range.zoneId).toLocalDate().minusDays(1)
    val dateLabel = if (start == end) {
        start.formatForDisplay()
    } else {
        stringResource(
            R.string.reports_range_dates,
            start.formatForDisplay(),
            end.formatForDisplay(),
        )
    }

    Text(
        text = dateLabel,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReportsTestTags.RANGE),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun GrossProfitHero(totals: SalesReportTotals) {
    val spacing = FacturaStockDesign.spacing
    val semanticColors = FacturaStockDesign.semanticColors
    val profit = totals.grossProfit
    val profitColor = when {
        profit == null -> MaterialTheme.colorScheme.onSurfaceVariant
        profit.amount.signum() < 0 -> MaterialTheme.colorScheme.error
        else -> semanticColors.success
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReportsTestTags.HERO),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.reports_gross_profit),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = profit?.formatForReport()
                    ?: stringResource(R.string.reports_amount_unavailable),
                color = profitColor,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.reports_profit_formula),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PrimaryMetrics(totals: SalesReportTotals, singleColumn: Boolean) {
    val metrics = listOf(
        ReportMetric(
            label = stringResource(R.string.reports_total_charged),
            value = totals.totalCharged.formatForReport(),
        ),
        ReportMetric(
            label = stringResource(R.string.reports_net_revenue),
            value = totals.netRevenue.formatForReport(),
        ),
        ReportMetric(
            label = stringResource(R.string.reports_historical_cost),
            value = totals.historicalCost?.formatForReport()
                ?: stringResource(R.string.reports_amount_unavailable),
        ),
    )
    val spacing = FacturaStockDesign.spacing

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReportsTestTags.METRICS),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (singleColumn) {
            Column(modifier = Modifier.padding(horizontal = spacing.md)) {
                metrics.forEachIndexed { index, metric ->
                    MetricValue(metric, Modifier.padding(vertical = spacing.sm))
                    if (index != metrics.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                metrics.forEach { metric -> MetricValue(metric, Modifier.weight(1f)) }
            }
        }
    }
}

private data class ReportMetric(val label: String, val value: String)

@Composable
private fun MetricValue(metric: ReportMetric, modifier: Modifier = Modifier) {
    val spacing = FacturaStockDesign.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
        Text(
            text = metric.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = metric.value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfitUnavailableNotice(
    issues: Set<RealizedProfitIssue>,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val semanticColors = FacturaStockDesign.semanticColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.small,
        color = semanticColors.warningContainer,
        contentColor = semanticColors.onWarningContainer,
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier.size(spacing.iconSmall),
            )
            Text(
                text = stringResource(
                    R.string.reports_profit_unavailable_summary,
                    profitIssuesMessage(issues),
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun profitIssuesMessage(issues: Set<RealizedProfitIssue>): String {
    if (issues.isEmpty()) return stringResource(R.string.reports_issue_unknown)
    val messages = mutableListOf<String>()
    for (issue in issues.sortedBy { it.ordinal }) {
        messages += stringResource(issue.messageRes())
    }
    return messages.joinToString(separator = " ")
}

@StringRes
private fun RealizedProfitIssue.messageRes(): Int = when (this) {
    RealizedProfitIssue.MISSING_HISTORICAL_COST -> R.string.reports_issue_missing_cost
    RealizedProfitIssue.COST_CURRENCY_MISMATCH -> R.string.reports_issue_currency_mismatch
    RealizedProfitIssue.INVALID_PERSISTED_DATA -> R.string.reports_issue_invalid_data
    RealizedProfitIssue.DECIMAL_LIMIT_EXCEEDED -> R.string.reports_issue_decimal_limit
}

@Composable
private fun OtherCurrencies(report: SalesReport, singleColumn: Boolean) {
    val spacing = FacturaStockDesign.spacing
    val otherTotals = report.totalsByCurrency.filter { it.currency != report.primaryCurrency }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReportsTestTags.OTHER_CURRENCIES),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.reports_other_currencies_title),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.reports_other_currencies_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        otherTotals.forEach { totals ->
            CurrencyTotals(totals = totals, singleColumn = singleColumn)
        }
    }
}

@Composable
private fun CurrencyTotals(totals: SalesReportTotals, singleColumn: Boolean) {
    val spacing = FacturaStockDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = totals.currency.value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            LabeledAmount(
                label = stringResource(R.string.reports_gross_profit),
                value = totals.grossProfit?.formatForReport()
                    ?: stringResource(R.string.reports_amount_unavailable),
                textAlign = TextAlign.End,
            )
        }
        ThreeAmounts(
            totalCharged = totals.totalCharged.formatForReport(),
            netRevenue = totals.netRevenue.formatForReport(),
            historicalCost = totals.historicalCost?.formatForReport()
                ?: stringResource(R.string.reports_amount_unavailable),
            singleColumn = singleColumn,
        )
        if (totals.issues.isNotEmpty()) {
            ProfitUnavailableNotice(totals.issues)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SaleProfitRow(
    sale: RealizedSaleProfit,
    range: SalesReportRange,
    singleColumn: Boolean,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReportsTestTags.sale(sale.saleId.value))
            .padding(vertical = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (singleColumn) {
            SaleIdentity(sale = sale, range = range)
            LabeledAmount(
                label = stringResource(R.string.reports_gross_profit),
                value = sale.grossProfit?.formatForReport()
                    ?: stringResource(R.string.reports_amount_unavailable),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                SaleIdentity(sale = sale, range = range, modifier = Modifier.weight(1f))
                LabeledAmount(
                    label = stringResource(R.string.reports_gross_profit),
                    value = sale.grossProfit?.formatForReport()
                        ?: stringResource(R.string.reports_amount_unavailable),
                    textAlign = TextAlign.End,
                )
            }
        }
        ThreeAmounts(
            totalCharged = sale.totalCharged.formatForDisplay(),
            netRevenue = sale.netRevenue.formatForDisplay(),
            historicalCost = sale.historicalCost?.formatForReport()
                ?: stringResource(R.string.reports_amount_unavailable),
            singleColumn = singleColumn,
        )
        if (sale.issues.isNotEmpty()) {
            ProfitUnavailableNotice(sale.issues)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SaleIdentity(
    sale: RealizedSaleProfit,
    range: SalesReportRange,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
        Text(
            text = stringResource(
                R.string.reports_sale_identifier,
                sale.saleId.value.reportIdentifier(),
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.reports_sale_metadata,
                sale.postedAt.formatForDisplay(range.zoneId),
                pluralStringResource(
                    R.plurals.reports_line_count,
                    sale.lineCount,
                    sale.lineCount,
                ),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ThreeAmounts(
    totalCharged: String,
    netRevenue: String,
    historicalCost: String,
    singleColumn: Boolean,
) {
    val spacing = FacturaStockDesign.spacing
    val amounts = listOf(
        ReportMetric(stringResource(R.string.reports_total_charged), totalCharged),
        ReportMetric(stringResource(R.string.reports_net_revenue), netRevenue),
        ReportMetric(stringResource(R.string.reports_historical_cost), historicalCost),
    )
    if (singleColumn) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            amounts.forEach { LabeledAmount(it.label, it.value) }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            amounts.forEach { LabeledAmount(it.label, it.value, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun LabeledAmount(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    val spacing = FacturaStockDesign.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = textAlign,
        )
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            textAlign = textAlign,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyReport() {
    val spacing = FacturaStockDesign.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.comfortableTouchTarget)
            .testTag(ReportsTestTags.EMPTY),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_sale),
            contentDescription = null,
            modifier = Modifier.size(spacing.icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.reports_empty_title),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RetryNotice(
    text: String,
    onRetry: () -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val semanticColors = FacturaStockDesign.semanticColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            semanticColors.warningContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            semanticColors.onWarningContainer
        },
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            FacturaStockSecondaryButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                leadingIconRes = R.drawable.ic_refresh,
            )
        }
    }
}

private fun ExactMonetaryAmount.formatForReport(): String {
    val rounded = amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_EVEN)
    return formatCurrencyAmountForDisplay(currency.value, rounded.toPlainString())
        ?: "${currency.value} ${rounded.toPlainString()}"
}

private fun String.reportIdentifier(): String =
    takeLast(REPORT_ID_VISIBLE_CHARACTERS).uppercase()

private const val REPORT_ID_VISIBLE_CHARACTERS = 8
