package com.facturastock.app.feature.catalogs

import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.repository.ProductStockEdit
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import java.math.BigDecimal

/** Acepta coma o punto decimal sin borrar caracteres y alterar silenciosamente el importe. */
internal fun String.productDecimalOrNull(): BigDecimal? {
    // Valida el texto recibido antes de trim: un exceso acotado por el formulario debe
    // seguir inválido aunque su prefijo termine en espacios.
    if (length > 24) return null
    val input = trim()
    if (!PRODUCT_DECIMAL.matches(input)) return null
    return input.replace(',', '.').toBigDecimalOrNull()?.takeIf(ExactDecimalPolicy::supportsValue)
}

internal fun Form.ProductForm.hasValidProductFields(currency: CurrencyCode): Boolean {
    if (isInventoryOrigin && (isSkuInputTooLong || isBarcodeInputTooLong || sku.trim().length > 64)) return false
    if (title.trim().length !in 1..200) return false
    val unchangedInventoryBarcode = isInventoryOrigin && barcode == original?.barcode.orEmpty()
    if (!unchangedInventoryBarcode && !BarcodeValue.isValidOptional(barcode)) return false
    if (isScannedRegistration && BarcodeValue.parse(barcode) == null) return false
    if (isSpecialRegistration && (barcode.isNotEmpty() || locationId == null || purchaseUnitId != null || purchaseFactor.isNotEmpty())) return false
    val requiresOpeningStock = isScannedRegistration || isSpecialRegistration
    if (!isInventoryOrigin && (quantity.isNotBlank() || requiresOpeningStock)) {
        val parsed = quantity.productDecimalOrNull() ?: return false
        if (parsed.signum() < 0 || (requiresOpeningStock && parsed.signum() == 0)) return false
    }
    if (salePrice.isNotBlank() || requiresOpeningStock) {
        val parsed = salePrice.productDecimalOrNull() ?: return false
        val money = runCatching { Money.fromMajor(parsed, currency) }.getOrNull() ?: return false
        if (!ProductSalePricePolicy.supports(money)) return false
    }
    if (requiresOpeningStock) {
        val cost = purchasePrice.productDecimalOrNull() ?: return false
        if (cost.signum() < 0) return false
    }
    return !isInventoryOrigin || inventoryStockEditsOrNull() != null
}

private val PRODUCT_DECIMAL = Regex("(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)")

/** Los valores originales pueden contener toda la precisión de costo derivado almacenada. */
internal fun String.inventoryDecimalOrNull(): BigDecimal? {
    if (length > 128 || !PRODUCT_DECIMAL.matches(trim())) return null
    return trim().replace(',', '.').toBigDecimalOrNull()?.takeIf(InventoryCostingDecimalPolicy::supportsPersisted)
}

/** Solo envía objetivos que el usuario cambió; un saldo no editado nunca se repone a su valor viejo. */
internal fun Form.ProductForm.inventoryStockEditsOrNull(): List<ProductStockEdit>? {
    val snapshot = inventorySnapshot ?: return null
    if (inventoryBalanceDrafts.size != snapshot.positions.size) return null
    val result = mutableListOf<ProductStockEdit>()
    for (draft in inventoryBalanceDrafts) {
        val position = snapshot.positions.singleOrNull { it.locationId == draft.locationId } ?: return null
        val originalQuantity = position.displayQuantity()
        val unchangedCost =
            draft.purchasePrice == position.displayUnitCost() ||
                draft.purchasePrice == position.averageUnitCost?.toPlainString().orEmpty()
        if (draft.quantity == originalQuantity && unchangedCost) continue
        val quantity =
            if (draft.quantity == originalQuantity && position.balanceVersion != null) {
                position.quantityOnHand
            } else {
                draft.quantity.inventoryDecimalOrNull() ?: return null
            }
        val cost =
            if (unchangedCost) {
                position.averageUnitCost
            } else if (draft.purchasePrice.isBlank() && position.averageUnitCost == null) {
                null
            } else {
                draft.purchasePrice.inventoryDecimalOrNull() ?: return null
            }
        if (quantity.signum() < 0 || (cost != null && cost.signum() < 0)) return null
        if (quantity > position.quantityOnHand && cost == null) return null
        val sameQuantity = quantity.compareTo(position.quantityOnHand) == 0
        val sameCost =
            if (cost == null) {
                position.averageUnitCost == null
            } else {
                position.averageUnitCost?.let { cost.compareTo(it) == 0 } == true
            }
        // Un promedio histórico conserva toda su precisión; los costos ingresados nuevos
        // respetan la precisión de UnitCost usada por el resto de los catálogos.
        if (!sameCost && cost != null && !ExactDecimalPolicy.supportsValue(cost)) return null
        if (!sameQuantity || !sameCost) result += ProductStockEdit(position.locationId, quantity, cost)
    }
    if (result.isNotEmpty() && !snapshot.inventoryEditable) return null
    return result
}
