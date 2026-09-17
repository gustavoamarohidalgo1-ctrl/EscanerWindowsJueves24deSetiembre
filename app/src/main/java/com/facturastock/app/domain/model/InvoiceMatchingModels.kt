package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.LineId
import java.math.BigDecimal

/** La cantidad y el costo leídos se expresan juntos en esta unidad. */
enum class InvoiceMatchUnitChoice { INVENTORY, PURCHASE }

enum class InvoiceMatchingIssue {
    QUANTITY_REQUIRED,
    COST_REQUIRED,
    CURRENCY_REQUIRED,
    CURRENCY_MISMATCH,
    UNIT_REQUIRED,
    PRODUCT_REQUIRED,
}

enum class MatchStatus {
    AUTO_LINKED,
    MANUAL_LINKED,
    CREATED_NEW,
    UNMATCHED,
}

data class ScannedItemMatch(
    val lineIndex: Int,
    val rawDescription: String,
    val quantity: BigDecimal? = null,
    val unitCost: BigDecimal? = null,
    /** Código impreso de la factura (SKU / código de proveedor), nunca un código de barras. */
    val printedCode: String? = null,
    /** Código de barras real, solo si la persona lo escanea o lo escribe. */
    val barcode: String? = null,
    val matchedProduct: Product? = null,
    val status: MatchStatus = MatchStatus.UNMATCHED,
    val matchReasonLabel: String? = null,
    val suggestedProducts: List<Product> = emptyList(),
    val sourceLineId: LineId? = null,
    /** Identidad durable de una fila adicional escrita explícitamente durante la revisión. */
    val manualLineId: LineId? = null,
    val sourceUnitCode: String? = null,
    val sourceCurrency: CurrencyCode? = null,
    val unitChoice: InvoiceMatchUnitChoice? = null,
    /** Etiquetas informativas; el commit vuelve a leer las unidades del catálogo. */
    val inventoryUnitCode: String? = null,
    val purchaseUnitCode: String? = null,
) {
    val hasUnresolvedDescription: Boolean
        get() = rawDescription.isNotBlank() && matchedProduct == null

    val hasStockableQuantity: Boolean
        get() = matchedProduct != null && quantity != null && quantity.signum() > 0
}
