package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.ConfirmInvoiceMatchingResult

interface InvoiceMatchingCommitRepository {
    suspend fun findAppliedCount(
        businessId: BusinessId,
        draftId: DraftId,
    ): Int? = null

    /** Productos, existencias, recibo y cierre del borrador deben ser atómicos. */
    suspend fun confirm(
        businessId: BusinessId,
        currency: CurrencyCode,
        draftId: DraftId,
        items: List<ScannedItemMatch>,
    ): ConfirmInvoiceMatchingResult
}
