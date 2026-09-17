package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import java.time.Instant

enum class ReportPdfKind { DAILY_SALES_WITH_DEBTORS, DEBTORS }

/** Instantánea de lectura: pendientes de todas las fechas y ventas del intervalo solicitado. */
data class ReportPdfSnapshot(
    val businessId: BusinessId,
    val businessName: String,
    val generatedAt: Instant,
    val range: SalesReportRange,
    val primaryCurrency: CurrencyCode,
    val sales: List<RealizedSaleProfit>,
    val debts: List<DebtSummary>,
    /** Incluye créditos ya pagados para no clasificarlos como ventas al contado. */
    val saleDebtorNames: Map<SaleId, String>,
    val debtPayments: List<DebtPaymentReportItem> = emptyList(),
) {
    init {
        validateDebtPaymentsInReport(debtPayments, businessId, range)
        require(businessName.isNotBlank())
        require(debts.all { it.businessId == businessId && it.status == DebtStatus.OPEN })
        require(debts.map { it.debtId }.distinct().size == debts.size)
        val saleIds = sales.mapTo(mutableSetOf()) { it.saleId }
        require(saleIds.size == sales.size)
        require(sales.all { it.postedAt >= range.startInclusive && it.postedAt < range.endExclusive })
        require(saleDebtorNames.keys.all { it in saleIds })
        require(saleDebtorNames.values.all(String::isNotBlank))
    }
}

data class PreparedReportPdf(
    val kind: ReportPdfKind,
    val snapshot: ReportPdfSnapshot,
    val suggestedFileName: String,
)

sealed interface ReportPdfPreparation {
    data class Ready(
        val prepared: PreparedReportPdf,
    ) : ReportPdfPreparation

    data object NoActiveBusiness : ReportPdfPreparation

    data object ContextChanged : ReportPdfPreparation

    data object Failed : ReportPdfPreparation
}

enum class ReportPdfWriteStatus {
    WRITTEN,
    FAILED_DESTINATION_CLEAN,
    FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
    CONTEXT_CHANGED,
}
