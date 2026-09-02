package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.math.BigDecimal
import java.math.RoundingMode

class Money private constructor(
    val minorUnits: Long,
    val currency: CurrencyCode,
) {
    val scale: Int
        get() = currency.defaultFractionDigits

    fun toMajor(): BigDecimal = BigDecimal.valueOf(minorUnits, scale)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is Money && minorUnits == other.minorUnits && currency == other.currency

    override fun hashCode(): Int = 31 * minorUnits.hashCode() + currency.hashCode()

    override fun toString(): String = "${toMajor().toPlainString()} $currency"

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return ofMinor(
            minorUnits = exactArithmetic("money.add") {
                Math.addExact(minorUnits, other.minorUnits)
            },
            currency = currency,
        )
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return ofMinor(
            minorUnits = exactArithmetic("money.subtract") {
                Math.subtractExact(minorUnits, other.minorUnits)
            },
            currency = currency,
        )
    }

    private fun requireSameCurrency(other: Money) {
        if (currency != other.currency) {
            throw DomainRuleViolation(
                ValidationError.CurrencyMismatch(
                    expected = currency,
                    actual = other.currency,
                ),
            )
        }
    }

    companion object {
        fun ofMinor(minorUnits: Long, currency: CurrencyCode): Money =
            Money(minorUnits = minorUnits, currency = currency)

        fun zero(currency: CurrencyCode): Money = ofMinor(0L, currency)

        fun fromMajor(
            amount: String,
            currency: CurrencyCode,
            roundingMode: RoundingMode = RoundingMode.UNNECESSARY,
        ): Money {
            if (!ExactDecimalPolicy.supportsInput(amount)) {
                throw invalidAmount(ExactDecimalPolicy.safeInput(amount))
            }
            val decimal = try {
                BigDecimal(amount)
            } catch (exception: NumberFormatException) {
                throw invalidAmount(amount, exception)
            }
            if (!ExactDecimalPolicy.supportsMoneyInput(decimal)) {
                throw invalidAmount(amount)
            }
            return fromMajor(decimal, currency, roundingMode)
        }

        fun fromMajor(
            amount: BigDecimal,
            currency: CurrencyCode,
            roundingMode: RoundingMode = RoundingMode.UNNECESSARY,
        ): Money {
            if (!ExactDecimalPolicy.supportsMoneyInput(amount)) {
                throw invalidAmount(ExactDecimalPolicy.safeText(amount))
            }
            val scale = currency.defaultFractionDigits
            val rounded = try {
                amount.setScale(scale, roundingMode)
            } catch (exception: ArithmeticException) {
                throw DomainRuleViolation(
                    error = ValidationError.DecimalScaleLoss(
                        value = ExactDecimalPolicy.safeText(amount),
                        targetScale = scale,
                    ),
                    cause = exception,
                )
            }

            val minorUnits = exactArithmetic("money.fromMajor") {
                rounded.movePointRight(scale).longValueExact()
            }
            return ofMinor(minorUnits, currency)
        }

        private fun invalidAmount(
            input: String,
            cause: Throwable? = null,
        ): DomainRuleViolation = DomainRuleViolation(
            error = ValidationError.InvalidMoneyAmount(input),
            cause = cause,
        )

        private inline fun exactArithmetic(
            operation: String,
            block: () -> Long,
        ): Long = try {
            block()
        } catch (exception: ArithmeticException) {
            throw DomainRuleViolation(
                error = ValidationError.ArithmeticOverflow(operation),
                cause = exception,
            )
        }
    }
}
