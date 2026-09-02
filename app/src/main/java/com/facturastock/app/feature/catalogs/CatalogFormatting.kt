package com.facturastock.app.feature.catalogs

import com.facturastock.app.domain.model.UnitCost
import java.math.BigDecimal

/** Formato legible que conserva precisión útil sin mostrar ceros técnicos de escala 18. */
internal fun UnitCost.toCatalogCostText(): String =
    "${amount.toCatalogAmountText()} ${currency.value}"

internal fun BigDecimal.toCatalogAmountText(): String {
    val meaningfulScale = stripTrailingZeros().scale().coerceAtLeast(MIN_DISPLAY_SCALE)
    return setScale(meaningfulScale).toPlainString()
}

private const val MIN_DISPLAY_SCALE = 2
