package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.math.BigDecimal
import java.math.RoundingMode

class Quantity private constructor(val value: BigDecimal) {
    val scale: Int
        get() = value.scale()

    operator fun plus(other: Quantity): Quantity = of(value.add(other.value))

    /**
     * Multiplica por un factor exacto (p. ej. unidades por caja). El resultado debe seguir siendo
     * exacto y positivo según [ExactDecimalPolicy]; cualquier redondeo se exige explícito con
     * [withScale] en el llamador.
     */
    operator fun times(factor: BigDecimal): Quantity {
        val result = value.multiply(factor)
        if (result.signum() <= 0 || !ExactDecimalPolicy.supportsValue(result)) {
            throw invalid("${ExactDecimalPolicy.safeText(value)} * ${ExactDecimalPolicy.safeText(factor)}")
        }
        return of(result)
    }

    fun withScale(targetScale: Int, roundingMode: RoundingMode): Quantity {
        if (targetScale !in 0..ExactDecimalPolicy.MAX_VALUE_SCALE) {
            throw invalid(ExactDecimalPolicy.safeText(value))
        }
        val scaled = try {
            value.setScale(targetScale, roundingMode)
        } catch (exception: ArithmeticException) {
            throw DomainRuleViolation(
                error = ValidationError.DecimalScaleLoss(
                    value = ExactDecimalPolicy.safeText(value),
                    targetScale = targetScale,
                ),
                cause = exception,
            )
        }
        return of(scaled)
    }

    override fun toString(): String = value.toPlainString()

    override fun equals(other: Any?): Boolean =
        this === other || other is Quantity && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun of(input: String): Quantity {
            if (!ExactDecimalPolicy.supportsInput(input)) {
                throw invalid(ExactDecimalPolicy.safeInput(input))
            }
            val decimal = try {
                BigDecimal(input)
            } catch (exception: NumberFormatException) {
                throw invalid(input, exception)
            }
            if (decimal.signum() <= 0 || !ExactDecimalPolicy.supportsValue(decimal)) {
                throw invalid(input)
            }
            return Quantity(decimal)
        }

        fun of(value: BigDecimal): Quantity {
            if (value.signum() <= 0 || !ExactDecimalPolicy.supportsValue(value)) {
                throw invalid(ExactDecimalPolicy.safeText(value))
            }
            return Quantity(value)
        }

        private fun invalid(
            input: String,
            cause: Throwable? = null,
        ): DomainRuleViolation = DomainRuleViolation(
            error = ValidationError.InvalidQuantity(input),
            cause = cause,
        )
    }
}
