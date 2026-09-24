package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

enum class DebtStatus { OPEN, PAID }

enum class DebtPaymentMethod { CASH, YAPE, PLIN, BANK_TRANSFER, OTHER }

/**
 * Nombre comercial limpio que puede cruzar la frontera cloud. Se conserva la capitalización de
 * la persona y se normaliza NFKC, espacios exteriores e interiores antes de persistirlo.
 */
fun normalizeDebtorName(raw: String): String {
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim()
        .replace(WhitespaceRun, " ")
    require(normalized.length in 2..120) {
        "El nombre del deudor debe tener entre 2 y 120 caracteres"
    }
    require(normalized.none { it.isISOControl() }) {
        "El nombre del deudor contiene caracteres de control"
    }
    return normalized
}

fun debtorNameSearchKey(cleanName: String): String =
    normalizeDebtorName(cleanName).lowercase(Locale.ROOT)

fun normalizeOptionalDebtText(raw: String?, maxLength: Int): String? = raw?.let {
    val normalized = Normalizer.normalize(it, Normalizer.Form.NFKC).trim()
    if (normalized.isEmpty()) return null
    require(normalized.length <= maxLength && normalized.none(Char::isISOControl)) {
        "El texto del pago excede el formato permitido"
    }
    normalized
}

/** Cuenta por cobrar de una venta ya publicada. */
data class DebtSummary(
    val debtId: DebtId,
    val businessId: BusinessId,
    val saleId: SaleId,
    val debtorName: String,
    val originalAmount: Money,
    val balance: Money,
    val status: DebtStatus,
    val lineCount: Int,
    val dueAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val paidAt: Instant?,
) {
    init {
        require(debtorName == normalizeDebtorName(debtorName)) {
            "debtorName debe estar normalizado"
        }
        require(originalAmount.currency == balance.currency)
        require(originalAmount.minorUnits > 0L)
        require(balance.minorUnits in 0L..originalAmount.minorUnits)
        require(lineCount > 0) { "Una deuda debe conservar al menos un producto" }
        require(version >= 1L)
        require(updatedAt >= createdAt)
        dueAt?.let { require(it.toEpochMilli() >= 0L) }
        when (status) {
            DebtStatus.OPEN -> require(balance.minorUnits > 0L && paidAt == null)
            DebtStatus.PAID -> require(
                balance.minorUnits == 0L && paidAt != null && paidAt in createdAt..updatedAt,
            )
        }
    }
}

/** Producto congelado por la venta: la deuda nunca duplica ni reinterpreta sus líneas. */
data class DebtLine(
    val saleLineId: SaleLineId,
    val productId: ProductId,
    val productName: String,
    val barcode: String?,
    val quantity: Quantity,
    val unitPrice: Money,
    val lineTotal: Money,
) {
    init {
        require(productName.isNotBlank())
        require(unitPrice.currency == lineTotal.currency)
        require(unitPrice.minorUnits > 0L)
        require(lineTotal.minorUnits >= 0L)
    }
}

data class DebtPayment(
    val paymentId: DebtPaymentId,
    val debtId: DebtId,
    val amount: Money,
    val method: DebtPaymentMethod,
    val note: String?,
    val reference: String?,
    val expectedDebtVersion: Long,
    val balanceAfter: Money,
    val occurredAt: Instant,
    val createdAt: Instant,
) {
    init {
        require(amount.currency == balanceAfter.currency)
        require(amount.minorUnits > 0L)
        require(balanceAfter.minorUnits >= 0L)
        require(expectedDebtVersion >= 1L)
        require(note == normalizeOptionalDebtText(note, 500))
        require(reference == normalizeOptionalDebtText(reference, 120))
        require(occurredAt.toEpochMilli() >= 0L && createdAt.toEpochMilli() >= 0L)
    }
}

data class DebtDetail(
    val debt: DebtSummary,
    val lines: List<DebtLine>,
    val payments: List<DebtPayment>,
) {
    init {
        require(lines.isNotEmpty()) { "Una deuda debe conservar al menos un producto" }
        require(debt.lineCount == lines.size) { "lineCount no coincide con el detalle" }
        require(lines.map(DebtLine::saleLineId).distinct().size == lines.size)
        require(lines.all { it.unitPrice.currency == debt.originalAmount.currency })
        require(payments.all { payment ->
            payment.debtId == debt.debtId && payment.amount.currency == debt.originalAmount.currency
        })
    }
}

// Compilada una vez: antes se creaba una expresión regular nueva en cada normalización.
private val WhitespaceRun = Regex("\\s+")
