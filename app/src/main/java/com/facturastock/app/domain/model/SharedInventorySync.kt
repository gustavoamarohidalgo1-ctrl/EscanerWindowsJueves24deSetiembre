package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** Tipo de hecho que produjo una nueva instantanea de inventario compartido. */
enum class SharedInventoryChangeKind { SALE, PURCHASE, PURCHASE_VOID, DEBT_PAYMENT }

/** Datos personales mínimos que convierten una venta publicada en cuenta por cobrar. */
data class SharedSaleCredit(
    val debtId: DebtId,
    val debtorNameSnapshot: String,
    val dueAt: Instant?,
) {
    init {
        require(debtorNameSnapshot == normalizeDebtorName(debtorNameSnapshot))
        dueAt?.let { require(it.toEpochMilli() >= 0L) }
    }
}

/**
 * Linea inmutable de una venta que cruza la frontera cloud. Los identificadores representan la
 * identidad del emisor; al aplicar el hecho otro dispositivo resuelve producto y almacen mediante
 * sus enlaces de catalogo y el nombre canonico del almacen.
 */
data class SharedSaleLine(
    val saleLineId: SaleLineId,
    val position: Int,
    val productId: ProductId,
    val unitId: UnitId,
    val locationId: LocationId,
    val productName: String,
    val unitCode: String,
    val locationName: String,
    val barcode: String?,
    val quantity: Quantity,
    val unitPrice: Money,
    val discount: Money,
    val tax: Money,
    val lineTotal: Money,
) {
    init {
        require(position >= 0)
        require(productName.isNotBlank() && unitCode.isNotBlank() && locationName.isNotBlank())
        require(unitPrice.minorUnits > 0L)
        require(
            setOf(unitPrice.currency, discount.currency, tax.currency, lineTotal.currency).size == 1,
        )
        require(lineTotal == calculateSaleLineTotal(unitPrice, quantity, discount, tax))
    }
}

/** Documento exacto enviado a `postSale`; no contiene imagenes ni datos OCR. */
data class SharedSaleDocument(
    val saleId: SaleId,
    val currency: CurrencyCode,
    val subtotal: Money,
    val discount: Money,
    val tax: Money,
    val total: Money,
    val contentHash: String,
    val checkoutIdempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val postedAt: Instant,
    val lines: List<SharedSaleLine>,
    val credit: SharedSaleCredit? = null,
) {
    init {
        require(lines.isNotEmpty())
        require(lines.map(SharedSaleLine::position) == lines.indices.toList())
        require(lines.map(SharedSaleLine::saleLineId).distinct().size == lines.size)
        require(CONTENT_HASH.matches(contentHash))
        require(checkoutIdempotencyKey.isNotBlank() && checkoutIdempotencyKey.length <= 256)
        require(createdAt <= updatedAt && updatedAt <= postedAt)
        require(setOf(subtotal.currency, discount.currency, tax.currency, total.currency) == setOf(currency))
        require(lines.all { it.unitPrice.currency == currency })
        val expectedSubtotal = lines.fold(0L) { sum, line ->
            Math.addExact(sum, calculateSaleLineGross(line.unitPrice, line.quantity).minorUnits)
        }
        val expectedDiscount = lines.fold(0L) { sum, line ->
            Math.addExact(sum, line.discount.minorUnits)
        }
        val expectedTax = lines.fold(0L) { sum, line -> Math.addExact(sum, line.tax.minorUnits) }
        val expectedTotal = Math.addExact(Math.subtractExact(expectedSubtotal, expectedDiscount), expectedTax)
        require(
            subtotal.minorUnits == expectedSubtotal && discount.minorUnits == expectedDiscount &&
                tax.minorUnits == expectedTax && total.minorUnits == expectedTotal,
        )
    }

    private companion object {
        val CONTENT_HASH = Regex("^[0-9a-f]{64}$")
    }
}

/** Estado autoritativo de una cuenta por cobrar después de un hecho cloud. */
data class SharedDebtSnapshot(
    val debtId: DebtId,
    val businessId: BusinessId,
    val saleId: SaleId,
    val debtorNameSnapshot: String,
    val currency: CurrencyCode,
    val originalAmount: Money,
    val balance: Money,
    val status: DebtStatus,
    val dueAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val paidAt: Instant?,
) {
    init {
        require(debtorNameSnapshot == normalizeDebtorName(debtorNameSnapshot))
        require(originalAmount.currency == currency && balance.currency == currency)
        require(originalAmount.minorUnits > 0L)
        require(balance.minorUnits in 0L..originalAmount.minorUnits)
        require(version >= 1L)
        require(createdAt <= updatedAt)
        dueAt?.let { require(it.toEpochMilli() >= 0L) }
        when (status) {
            DebtStatus.OPEN -> require(balance.minorUnits > 0L && paidAt == null)
            DebtStatus.PAID -> require(
                balance.minorUnits == 0L && paidAt != null && paidAt in createdAt..updatedAt,
            )
        }
    }
}

/** Pago inmutable transportado por el mismo feed secuencial que las ventas. */
data class SharedDebtPayment(
    val paymentId: DebtPaymentId,
    val debtId: DebtId,
    val businessId: BusinessId,
    val amount: Money,
    val method: DebtPaymentMethod,
    val note: String?,
    val reference: String?,
    val expectedDebtVersion: Long,
    val balanceAfter: Money,
    val idempotencyKey: String,
    val occurredAt: Instant,
    val createdAt: Instant,
) {
    init {
        require(amount.minorUnits > 0L)
        require(amount.currency == balanceAfter.currency)
        require(balanceAfter.minorUnits >= 0L)
        require(expectedDebtVersion >= 1L)
        require(note == normalizeOptionalDebtText(note, 500))
        require(reference == normalizeOptionalDebtText(reference, 120))
        require(idempotencyKey == "debt-payment:v1:${debtId.value}:${paymentId.value}")
        require(occurredAt.toEpochMilli() >= 0L && createdAt >= occurredAt)
    }
}

/** Saldo autoritativo posterior al hecho remoto. */
data class SharedInventoryBalance(
    val productId: ProductId,
    val locationName: String,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: BigDecimal,
    val currency: CurrencyCode,
    val version: Long,
    val updatedAt: Instant,
) {
    init {
        require(locationName.isNotBlank() && locationName.length <= 100)
        require(averageUnitCost.signum() >= 0)
        require(version >= 0L)
        require(InventoryCostingDecimalPolicy.supportsPersisted(quantityOnHand.abs()))
        require(InventoryCostingDecimalPolicy.supportsPersisted(averageUnitCost))
    }
}

/** Cambio de secuencia contigua; solo SALE transporta el grafo comercial completo. */
data class SharedInventoryChange(
    val seq: Long,
    val kind: SharedInventoryChangeKind,
    val balances: List<SharedInventoryBalance>,
    val sale: SharedSaleDocument?,
    val debt: SharedDebtSnapshot? = null,
    val payment: SharedDebtPayment? = null,
) {
    init {
        require(seq in 1..MAX_SAFE_SYNC_SEQUENCE)
        require(balances.map { it.productId to canonicalLocationName(it.locationName) }.distinct().size == balances.size)
        when (kind) {
            SharedInventoryChangeKind.SALE -> {
                require(balances.isNotEmpty() && sale != null)
                require(debt == null && payment == null)
            }
            SharedInventoryChangeKind.PURCHASE,
            SharedInventoryChangeKind.PURCHASE_VOID,
            -> {
                require(balances.isNotEmpty() && sale == null)
                require(debt == null && payment == null)
            }
            SharedInventoryChangeKind.DEBT_PAYMENT -> {
                require(balances.isEmpty() && sale == null)
                require(debt != null && payment != null)
                require(payment.debtId == debt.debtId)
                require(payment.businessId == debt.businessId)
                require(payment.amount.currency == debt.currency)
                require(payment.balanceAfter == debt.balance)
                require(debt.version == payment.expectedDebtVersion + 1L)
                require(debt.updatedAt == payment.createdAt)
            }
        }
    }
}

data class SharedInventoryPullPage(
    val changes: List<SharedInventoryChange>,
    val nextCursor: Long,
    val hasMore: Boolean,
) {
    init {
        require(nextCursor in 0..MAX_SAFE_SYNC_SEQUENCE)
        require(!hasMore || changes.isNotEmpty())
        changes.lastOrNull()?.let { require(it.seq == nextCursor) }
    }
}

/** Misma identidad semantica que usa el backend para un almacen entre dispositivos. */
fun canonicalLocationName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)
        .replace(WhitespaceRun, " ")

// Compilada una vez: antes se creaba una expresión regular nueva en cada normalización.
private val WhitespaceRun = Regex("\\s+")
