package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.math.BigDecimal
import java.math.RoundingMode

class UnitCost private constructor(
    val amount: BigDecimal,
    val currency: CurrencyCode,
) {
    val scale: Int
        get() = amount.scale()

    fun withScale(targetScale: Int, roundingMode: RoundingMode): UnitCost {
        if (targetScale !in 0..ExactDecimalPolicy.MAX_VALUE_SCALE) {
            throw invalid(ExactDecimalPolicy.safeText(amount))
        }
        val scaled = try {
            amount.setScale(targetScale, roundingMode)
        } catch (exception: ArithmeticException) {
            throw DomainRuleViolation(
                error = ValidationError.DecimalScaleLoss(
                    value = ExactDecimalPolicy.safeText(amount),
                    targetScale = targetScale,
                ),
                cause = exception,
            )
        }
        return of(scaled, currency)
    }

    fun totalFor(quantity: Quantity, roundingMode: RoundingMode): Money =
        Money.fromMajor(
            amount = amount.multiply(quantity.value),
            currency = currency,
            roundingMode = roundingMode,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is UnitCost && amount == other.amount && currency == other.currency

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "${amount.toPlainString()} $currency"

    companion object {
        fun of(amount: String, currency: CurrencyCode): UnitCost {
            if (!ExactDecimalPolicy.supportsInput(amount)) {
                throw invalid(ExactDecimalPolicy.safeInput(amount))
            }
            val decimal = try {
                BigDecimal(amount)
            } catch (exception: NumberFormatException) {
                throw invalid(amount, exception)
            }
            if (decimal.signum() < 0 || !ExactDecimalPolicy.supportsValue(decimal)) {
                throw invalid(amount)
            }
            return UnitCost(amount = decimal, currency = currency)
        }

        fun of(amount: BigDecimal, currency: CurrencyCode): UnitCost {
            if (amount.signum() < 0 || !ExactDecimalPolicy.supportsValue(amount)) {
                throw invalid(ExactDecimalPolicy.safeText(amount))
            }
            return UnitCost(amount = amount, currency = currency)
        }

        private fun invalid(
            input: String,
            cause: Throwable? = null,
        ): DomainRuleViolation = DomainRuleViolation(
            error = ValidationError.InvalidUnitCost(input),
            cause = cause,
        )
    }
}
