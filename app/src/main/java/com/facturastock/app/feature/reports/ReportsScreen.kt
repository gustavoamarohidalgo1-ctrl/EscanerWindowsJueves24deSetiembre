package com.facturastock.app.feature.reports

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.facturastock.app.R
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.RealizedSaleLineProfit
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.SalesReportTotals
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.format.formatCurrencyAmountForDisplay
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.math.RoundingMode
import java.math.BigDecimal

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
            state.report?.takeIf { it.debtPayments.isNotEmpty() }?.let { report ->
                item(key = "reports_debt_payment_summary", contentType = "collection_summary") {
                    ReportsConstrainedSection { DebtPaymentSummary(report) }
                }
            }
            item(key = "reports_pdf_actions", contentType = "report_actions") {
                ReportsConstrainedSection { ReportPdfActions(state, onAction) }
            }
            item(key = "reports_period", contentType = "period_selector") {
                ReportsConstrainedSection {
                    PeriodSelector(
                        selected = state.selectedPeriod,
                        enabled = !state.isVoiding,
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
                            enabled = !state.isVoiding,
                            modifier = Modifier.testTag(ReportsTestTags.ERROR),
                        )
                    }
                }

                state.report != null -> {
                    val report = requireNotNull(state.report)

                    item(key = "reports_range", contentType = "range") {
                        ReportsConstrainedSection { ReportRange(report.range) }
                    }
                    if (state.failure != null) {
                        item(key = "reports_stale", contentType = "warning") {
                            ReportsConstrainedSection {
                                RetryNotice(
                                    text = stringResource(R.string.reports_stale_message),
                                    onRetry = { onAction(ReportsContract.Action.Retry) },
                                    isError = false,
                                    enabled = !state.isVoiding,
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
                    items(
                        items = report.debtPayments,
                        key = { "payment_${it.payment.paymentId.value}" },
                        contentType = { "debt_payment" },
                    ) { payment ->
                        ReportsConstrainedSection { DebtPaymentRow(payment, report.range) }
                    }
                    if (report.sales.isEmpty()) {
                        item(key = "reports_empty", contentType = "empty") {
                            ReportsConstrainedSection {
                                if (report.debtPayments.isEmpty()) EmptyReport()
                                else Text(stringResource(R.string.reports_debt_payments_no_new_sales),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
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
                                    voidEnabled = !state.isVoiding && !state.isLoadingVoidPreview &&
                                        !state.isRefreshing && state.failure == null && report.businessId != null,
                                    onVoid = { onAction(ReportsContract.Action.VoidRequested(sale.saleId)) },
                                    detailsEnabled = !state.isVoiding,
                                )
                            }
                        }
                    }
                }
            }
        }
        if ((state.isRefreshing || state.isVoiding) && state.report != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg),
            ) {
                ReportsConstrainedSection {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().testTag(ReportsTestTags.REFRESHING),
                    )
                }
            }
        }
        if (state.voidSucceeded) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(spacing.md)
                    .testTag(ReportsTestTags.VOID_SUCCESS)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                actionOnNewLine = true,
                action = {
                    TextButton(onClick = { onAction(ReportsContract.Action.VoidNoticeDismissed) }) {
                        Text(stringResource(R.string.reports_void_notice_dismiss))
                    }
                },
            ) {
                Text(stringResource(R.string.reports_void_success))
            }
        }
    }
    if (state.voidSaleId != null) {
        VoidSaleDialog(state = state, onAction = onAction)
    }
}

@Composable
private fun DebtPaymentSummary(report: SalesReport) {
    val spacing = FacturaStockDesign.spacing
    val totals = linkedMapOf(report.primaryCurrency to BigDecimal.ZERO)
    report.debtPayments.forEach { item ->
        val amount = item.payment.amount
        totals[amount.currency] = (totals[amount.currency] ?: BigDecimal.ZERO).add(amount.toMajor())
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(ReportsTestTags.DEBT_PAYMENTS),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                stringResource(if (report.range.period == SalesReportPeriod.DAY) R.string.reports_debt_payments_today
                    else R.string.reports_debt_payments_period),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Column(Modifier.testTag(ReportsTestTags.DEBT_PAYMENT_TOTALS)) {
                totals.forEach { (currency, total) ->
                    Text(stringResource(R.string.reports_debt_payments_total, ExactMonetaryAmount(total, currency).formatForReport()),
                        style = MaterialTheme.typography.headlineSmall)
                }
            }
            Text(stringResource(R.string.reports_debt_payments_explanation), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.reports_debt_payments_no_double_count), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DebtPaymentRow(item: DebtPaymentReportItem, range: SalesReportRange) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(ReportsTestTags.debtPayment(item.payment.paymentId.value)),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(item.debtorName, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.reports_debt_payment_amount, item.payment.amount.formatForDisplay()),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.payment.occurredAt.formatForDisplay(range.zoneId), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReportPdfActions(state: ReportsContract.State, onAction: (ReportsContract.Action) -> Unit) {
    val spacing = FacturaStockDesign.spacing
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.md).testTag(ReportsTestTags.PDF_ACTIONS),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(stringResource(R.string.reports_pdf_actions_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            FacturaStockSecondaryButton(
                text = stringResource(R.string.reports_pdf_open_debtors),
                onClick = { onAction(ReportsContract.Action.OpenDebtors) },
                enabled = state.report?.businessId != null && !state.isVoiding,
                modifier = Modifier.fillMaxWidth().testTag(ReportsTestTags.OPEN_DEBTORS),
            )
            Text(stringResource(R.string.reports_pdf_daily_help), style = MaterialTheme.typography.bodySmall)
            FacturaStockSecondaryButton(
                text = stringResource(R.string.reports_pdf_daily_action),
                onClick = { onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DAILY_SALES_WITH_DEBTORS)) },
                enabled = state.canExportPdf,
                modifier = Modifier.fillMaxWidth().testTag(ReportsTestTags.PDF_DAILY),
            )
            Text(stringResource(R.string.reports_pdf_debtors_help), style = MaterialTheme.typography.bodySmall)
            FacturaStockSecondaryButton(
                text = stringResource(R.string.reports_pdf_debtors_action),
                onClick = { onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS)) },
                enabled = state.canExportPdf,
                modifier = Modifier.fillMaxWidth().testTag(ReportsTestTags.PDF_DEBTORS),
            )
            if (state.pdfStage != ReportsContract.PdfStage.IDLE) {
                Column(Modifier.testTag(ReportsTestTags.PDF_PROGRESS).semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (state.pdfStage != ReportsContract.PdfStage.CHOOSING_DESTINATION) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(when (state.pdfStage) {
                        ReportsContract.PdfStage.PREPARING -> R.string.reports_pdf_preparing
                        ReportsContract.PdfStage.CHOOSING_DESTINATION -> R.string.reports_pdf_choosing
                        ReportsContract.PdfStage.WRITING -> R.string.reports_pdf_writing
                        ReportsContract.PdfStage.IDLE -> R.string.reports_pdf_preparing
                    }))
                }
            }
            state.pdfFailure?.let { failure ->
                Text(stringResource(failure.messageRes()), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ReportsTestTags.PDF_ERROR).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.pdfSaved) {
                Text(stringResource(R.string.reports_pdf_saved),
                    modifier = Modifier.testTag(ReportsTestTags.PDF_SUCCESS).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.pdfSaved) {
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.reports_pdf_open_saved),
                    onClick = { onAction(ReportsContract.Action.OpenSavedPdf) },
                    modifier = Modifier.fillMaxWidth().testTag(ReportsTestTags.PDF_OPEN_SAVED),
                )
            }
            if (state.pdfViewerUnavailable) {
                Text(stringResource(R.string.reports_pdf_no_viewer),
                    modifier = Modifier.testTag(ReportsTestTags.PDF_NO_VIEWER).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.pdfFailure != null || state.pdfSaved) {
                TextButton(onClick = { onAction(ReportsContract.Action.PdfNoticeDismissed) }) {
                    Text(stringResource(R.string.reports_pdf_dismiss_notice))
                }
            }
        }
    }
}

@StringRes
private fun ReportsContract.PdfFailure.messageRes(): Int = when (this) {
    ReportsContract.PdfFailure.PREPARATION_FAILED -> R.string.reports_pdf_preparation_failed
    ReportsContract.PdfFailure.NO_ACTIVE_BUSINESS -> R.string.reports_pdf_no_business
    ReportsContract.PdfFailure.CONTEXT_CHANGED -> R.string.reports_pdf_context_changed
    ReportsContract.PdfFailure.DESTINATION_CLEAN -> R.string.reports_pdf_destination_clean
    ReportsContract.PdfFailure.DESTINATION_MAY_CONTAIN_PARTIAL_DATA -> R.string.reports_pdf_destination_partial
    ReportsContract.PdfFailure.INTERRUPTED -> R.string.reports_pdf_interrupted
}

@Composable
private fun VoidSaleDialog(
    state: ReportsContract.State,
    onAction: (ReportsContract.Action) -> Unit,
) {
    val preview = state.voidPreview
    val busy = state.isLoadingVoidPreview || state.isVoiding
    FacturaStockDialog(
        title = stringResource(R.string.reports_void_title),
        message = stringResource(
            when {
                state.isVoiding -> R.string.reports_void_saving
                state.isLoadingVoidPreview -> R.string.reports_void_loading
                else -> R.string.reports_void_message
            },
        ),
        confirmLabel = stringResource(
            if (preview != null || busy) R.string.reports_void_action else R.string.reports_void_retry,
        ),
        dismissLabel = stringResource(R.string.reports_void_cancel),
        onConfirm = {
            onAction(
                if (preview != null) ReportsContract.Action.VoidConfirmed
                else ReportsContract.Action.VoidPreviewRetry,
            )
        },
        onDismiss = { onAction(ReportsContract.Action.VoidDismissed) },
        confirmEnabled = !busy,
        dismissEnabled = !state.isVoiding,
        modifier = Modifier.testTag(ReportsTestTags.VOID_DIALOG),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
                state.voidFailure?.let { failure ->
                    Text(
                        text = stringResource(failure.messageRes()),
                        modifier = Modifier.testTag(ReportsTestTags.VOID_ERROR)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (preview != null) {
                    Column(
                        modifier = Modifier.testTag(ReportsTestTags.VOID_IMPACT),
                        verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm),
                    ) {
                        Text(stringResource(
                            R.string.reports_void_date,
                            preview.postedAt.formatForDisplay(state.report?.range?.zoneId ?: java.time.ZoneId.systemDefault()),
                        ))
                        Text(stringResource(R.string.reports_void_total, preview.total.formatForDisplay()))
                        Text(
                            text = stringResource(R.string.reports_void_stock),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        preview.lines.forEach { line ->
                            Text(stringResource(
                                R.string.reports_void_line,
                                line.productName,
                                line.quantity.value.stripTrailingZeros().toPlainString(),
                                line.unitCode,
                                line.locationName,
                            ))
                        }
                        preview.debtBalanceToCancel?.let { balance ->
                            Text(stringResource(R.string.reports_void_debt, balance.formatForDisplay()))
                        }
                        Text(
                            if (preview.refundAmount.minorUnits > 0L) {
                                stringResource(R.string.reports_void_refund, preview.refundAmount.formatForDisplay())
                            } else {
                                stringResource(R.string.reports_void_no_refund)
                            },
                        )
                    }
                }
            }
        },
    )
}

@StringRes
private fun ReportsContract.VoidFailure.messageRes(): Int = when (this) {
    ReportsContract.VoidFailure.LOAD_FAILED -> R.string.reports_void_error_load
    ReportsContract.VoidFailure.OPERATION_FAILED -> R.string.reports_void_error_save
    ReportsContract.VoidFailure.STALE -> R.string.reports_void_error_stale
    ReportsContract.VoidFailure.NO_ACTIVE_BUSINESS -> R.string.reports_void_error_business
    ReportsContract.VoidFailure.NOT_FOUND -> R.string.reports_void_error_missing
    ReportsContract.VoidFailure.UNAUTHORIZED -> R.string.reports_void_error_unauthorized
    ReportsContract.VoidFailure.SHARED_BUSINESS_UNSUPPORTED -> R.string.reports_void_error_shared
    ReportsContract.VoidFailure.INVALID_HISTORY -> R.string.reports_void_error_history
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
    enabled: Boolean,
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
                        enabled = enabled,
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
    voidEnabled: Boolean,
    onVoid: () -> Unit,
    detailsEnabled: Boolean,
) {
    val spacing = FacturaStockDesign.spacing
    var expanded by rememberSaveable(sale.saleId.value) { mutableStateOf(false) }
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
                label = stringResource(R.string.reports_total_charged),
                value = sale.totalCharged.formatForDisplay(),
                modifier = Modifier
                    .testTag(ReportsTestTags.saleTotal(sale.saleId.value))
                    .semantics(mergeDescendants = true) {},
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                SaleIdentity(sale = sale, range = range, modifier = Modifier.weight(2f))
                LabeledAmount(
                    label = stringResource(R.string.reports_total_charged),
                    value = sale.totalCharged.formatForDisplay(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ReportsTestTags.saleTotal(sale.saleId.value))
                        .semantics(mergeDescendants = true) {},
                    textAlign = TextAlign.End,
                )
            }
        }
        FacturaStockSecondaryButton(
            text = stringResource(R.string.reports_void_action),
            onClick = onVoid,
            enabled = voidEnabled,
            modifier = Modifier.testTag(ReportsTestTags.saleVoid(sale.saleId.value)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ReportsTestTags.saleProducts(sale.saleId.value)),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            sale.lines.forEach { line -> SaleLineSummary(line) }
        }
        val toggleLabel = stringResource(
            if (expanded) R.string.reports_sale_collapse_details
            else R.string.reports_sale_expand_details,
        )
        val toggleState = stringResource(
            if (expanded) R.string.reports_sale_details_expanded
            else R.string.reports_sale_details_collapsed,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.minimumTouchTarget)
                .semantics { stateDescription = toggleState }
                .clickable(enabled = detailsEnabled, role = Role.Button) { expanded = !expanded }
                .testTag(ReportsTestTags.saleToggle(sale.saleId.value)),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = toggleLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ReportsTestTags.saleDetails(sale.saleId.value)),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                sale.lines.forEach { line ->
                    SaleLineDetails(
                        saleId = sale.saleId.value,
                        line = line,
                        singleColumn = singleColumn,
                    )
                }
                if (sale.issues.isNotEmpty()) {
                    ProfitUnavailableNotice(
                        issues = sale.issues,
                        modifier = Modifier.testTag(
                            ReportsTestTags.saleWarning(sale.saleId.value),
                        ),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SaleLineSummary(line: RealizedSaleLineProfit) {
    val spacing = FacturaStockDesign.spacing
    val quantity = line.quantity?.let {
        stringResource(
            R.string.reports_sale_product_quantity,
            it.value.stripTrailingZeros().toPlainString(),
            line.unitCode,
        )
    } ?: stringResource(R.string.reports_amount_unavailable)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.productName,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = quantity,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SaleLineDetails(
    saleId: String,
    line: RealizedSaleLineProfit,
    singleColumn: Boolean,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ReportsTestTags.saleLine(saleId, line.saleLineId.value)),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = line.productName,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        LabeledAmount(
            label = stringResource(R.string.reports_sale_line_profit),
            value = line.grossProfit?.formatForReport()
                ?: stringResource(R.string.reports_amount_unavailable),
            modifier = Modifier
                .testTag(ReportsTestTags.saleLineProfit(saleId, line.saleLineId.value))
                .semantics(mergeDescendants = true) {},
        )
        ThreeAmounts(
            totalCharged = line.totalCharged.formatForDisplay(),
            netRevenue = line.netRevenue.formatForDisplay(),
            historicalCost = line.historicalCost?.formatForReport()
                ?: stringResource(R.string.reports_amount_unavailable),
            singleColumn = singleColumn,
            totalLabel = stringResource(R.string.reports_sale_line_total),
        )
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
                R.string.reports_sale_title,
                sale.postedAt.formatForDisplay(range.zoneId),
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = pluralStringResource(
                R.plurals.reports_line_count,
                sale.lineCount,
                sale.lineCount,
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
    totalLabel: String = stringResource(R.string.reports_total_charged),
) {
    val spacing = FacturaStockDesign.spacing
    val amounts = listOf(
        ReportMetric(totalLabel, totalCharged),
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
    enabled: Boolean = true,
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
                enabled = enabled,
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
