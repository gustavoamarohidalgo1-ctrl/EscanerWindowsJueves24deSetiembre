package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId

/** Cobro de una deuda, fechado por el pago; no crea otra venta ni otro movimiento de stock. */
data class DebtPaymentReportItem(
    val businessId: BusinessId,
    val saleId: SaleId,
    val debtorName: String,
    val payment: DebtPayment,
) {
    init {
        require(debtorName == normalizeDebtorName(debtorName))
    }
}

internal fun validateDebtPaymentsInReport(
    payments: List<DebtPaymentReportItem>,
    businessId: BusinessId?,
    range: SalesReportRange,
) {
    require(payments.all { it.businessId == businessId }) { "El cobro debe pertenecer al negocio del reporte" }
    require(payments.mapTo(mutableSetOf()) { it.payment.paymentId }.size == payments.size) {
        "El reporte no puede repetir un cobro"
    }
    require(payments.all { it.payment.occurredAt >= range.startInclusive && it.payment.occurredAt < range.endExclusive }) {
        "El cobro debe pertenecer al intervalo del reporte"
    }
}
