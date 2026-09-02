package com.facturastock.app.domain.model

/**
 * Límite interoperable con el transporte JSON de catálogo, cuyo backend usa enteros seguros de
 * JavaScript. Evita crear una operación de outbox que nunca podría ser aceptada remotamente.
 */
object ProductSalePricePolicy {
    const val MAX_MINOR_UNITS: Long = 9_007_199_254_740_991L

    fun supports(price: Money): Boolean = price.minorUnits in 1L..MAX_MINOR_UNITS
}
