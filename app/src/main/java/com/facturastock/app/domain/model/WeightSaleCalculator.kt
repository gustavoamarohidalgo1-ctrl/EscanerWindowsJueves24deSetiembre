package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Convierte importes y kilos con la misma política monetaria que la venta persistida. */
object WeightSaleCalculator {
    /** Devuelve kilos únicamente si el cálculo de la venta conserva el importe solicitado. */
    fun fromAmount(
        amount: Money,
        pricePerKg: Money,
    ): Quantity? {
        if (amount.minorUnits <= 0L || amount.currency != pricePerKg.currency ||
            !ProductSalePricePolicy.supports(pricePerKg)
        ) {
            return null
        }

        return try {
            // La misma moneda permite dividir unidades menores sin perder decimales del precio.
            val decimal =
                BigDecimal
                    .valueOf(amount.minorUnits)
                    .divide(
                        BigDecimal.valueOf(pricePerKg.minorUnits),
                        ExactDecimalPolicy.MAX_VALUE_SCALE,
                        RoundingMode.HALF_UP,
                    ).stripTrailingZeros()
            val quantity = Quantity.of(if (decimal.scale() < 0) decimal.setScale(0) else decimal)
            quantity.takeIf { calculateSaleLineGross(pricePerKg, it) == amount }
        } catch (_: DomainRuleViolation) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Reutiliza HALF_UP de ventas y rechaza pesos que producirían un importe nulo. */
    fun fromQuantity(
        quantity: Quantity,
        pricePerKg: Money,
    ): Money? {
        if (!ProductSalePricePolicy.supports(pricePerKg)) return null
        return try {
            calculateSaleLineGross(pricePerKg, quantity).takeIf { it.minorUnits > 0L }
        } catch (_: DomainRuleViolation) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun isKilogramUnit(code: String): Boolean =
        when (code.trim().uppercase(Locale.ROOT)) {
            "KGM", "KG" -> true
            else -> false
        }
}
