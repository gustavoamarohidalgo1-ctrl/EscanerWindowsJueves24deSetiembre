package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import java.math.BigDecimal

/**
 * Política de corrección de existencias al anular una compra.
 *
 * Una anulación corrige el libro histórico y por ello no se bloquea aunque revele que unidades
 * de esa compra ya fueron consumidas. El saldo negativo debe mostrarse antes de confirmar y
 * permanece visible como dato que requiere conciliación; nunca se recorta silenciosamente a cero.
 */
enum class PurchaseNegativeStockPolicy {
    ALLOW_WITH_VISIBLE_WARNING,
}

/** Impacto exacto de la reversa sobre un producto y almacén. */
data class PurchaseVoidImpact(
    val productId: ProductId,
    val productName: String,
    val locationId: LocationId,
    val locationName: String,
    val unitCode: String,
    val currentQuantity: BigDecimal,
    /** Delta firmado que se añadirá al libro y a la proyección materializada. */
    val reversalQuantity: BigDecimal,
    val resultingQuantity: BigDecimal,
    val currency: CurrencyCode,
) {
    init {
        require(productName.isNotBlank()) { "productName no puede estar vacío" }
        require(locationName.isNotBlank()) { "locationName no puede estar vacío" }
        require(unitCode.isNotBlank()) { "unitCode no puede estar vacío" }
        require(reversalQuantity.signum() != 0) { "La reversa no puede ser cero" }
        require(currentQuantity.add(reversalQuantity).compareTo(resultingQuantity) == 0) {
            "El saldo resultante no coincide con el impacto"
        }
    }

    val becomesNegative: Boolean
        get() = resultingQuantity.signum() < 0
}

/** Instantánea que el usuario debe revisar antes de ejecutar la anulación. */
data class PurchaseVoidPreview(
    val purchaseId: PurchaseId,
    val documentNumber: String,
    /** Principal resuelto por la política de acceso; la UI no puede fabricarlo. */
    val actor: PurchaseOverrideActor,
    val impacts: List<PurchaseVoidImpact>,
    /** Liga la confirmación al saldo y libro exactos que se mostraron. */
    val expectedImpactHash: String,
    val negativeStockPolicy: PurchaseNegativeStockPolicy =
        PurchaseNegativeStockPolicy.ALLOW_WITH_VISIBLE_WARNING,
) {
    init {
        require(documentNumber.isNotBlank()) { "documentNumber no puede estar vacío" }
        require(impacts.isNotEmpty()) { "Una compra publicable debe tener impacto de stock" }
        require(IMPACT_HASH.matches(expectedImpactHash)) {
            "expectedImpactHash debe ser SHA-256 hex en minúsculas"
        }
    }

    val hasNegativeImpact: Boolean
        get() = impacts.any(PurchaseVoidImpact::becomesNegative)

    private companion object {
        val IMPACT_HASH = Regex("[0-9a-f]{64}")
    }
}
