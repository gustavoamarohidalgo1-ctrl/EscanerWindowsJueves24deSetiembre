package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.AsciiPatterns
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import java.time.Instant

data class SaleVoidLine(
    val productName: String,
    val locationName: String,
    val unitCode: String,
    val quantity: Quantity,
) {
    init {
        require(productName.isNotBlank() && locationName.isNotBlank() && unitCode.isNotBlank())
    }
}

/** Impacto local que debe revisarse antes de confirmar. El sello incluye la identidad autorizada. */
data class SaleVoidPreview(
    val businessId: BusinessId,
    val saleId: SaleId,
    val postedAt: Instant,
    val total: Money,
    val refundAmount: Money,
    val debtBalanceToCancel: Money?,
    val lines: List<SaleVoidLine>,
    val impactHash: String,
) {
    init {
        require(lines.isNotEmpty())
        require(total.currency == refundAmount.currency)
        require(total.minorUnits >= 0L && refundAmount.minorUnits in 0L..total.minorUnits)
        if (debtBalanceToCancel == null) {
            require(refundAmount == total)
        } else {
            require(debtBalanceToCancel.currency == total.currency)
            require(debtBalanceToCancel.minorUnits in 0L..total.minorUnits)
            require(refundAmount.minorUnits == total.minorUnits - debtBalanceToCancel.minorUnits)
        }
        require(AsciiPatterns.isLowerHex(impactHash, 64))
    }
}

sealed interface SaleVoidPreviewResult {
    data class Ready(
        val preview: SaleVoidPreview,
    ) : SaleVoidPreviewResult

    data object AlreadyVoided : SaleVoidPreviewResult

    data object NoActiveBusiness : SaleVoidPreviewResult

    data object NotFound : SaleVoidPreviewResult

    data object Unauthorized : SaleVoidPreviewResult

    data object SharedBusinessUnsupported : SaleVoidPreviewResult

    data object InvalidHistory : SaleVoidPreviewResult
}

sealed interface SaleVoidResult {
    data object Voided : SaleVoidResult

    data object AlreadyVoided : SaleVoidResult

    data object Stale : SaleVoidResult

    data object NoActiveBusiness : SaleVoidResult

    data object NotFound : SaleVoidResult

    data object Unauthorized : SaleVoidResult

    data object SharedBusinessUnsupported : SaleVoidResult

    data object InvalidHistory : SaleVoidResult
}

/** Conserva la venta y los pagos; compensa el inventario una sola vez mediante un recibo separado. */
interface SaleVoidRepository {
    suspend fun preview(
        businessId: BusinessId,
        saleId: SaleId,
    ): SaleVoidPreviewResult

    suspend fun confirm(preview: SaleVoidPreview): SaleVoidResult
}
