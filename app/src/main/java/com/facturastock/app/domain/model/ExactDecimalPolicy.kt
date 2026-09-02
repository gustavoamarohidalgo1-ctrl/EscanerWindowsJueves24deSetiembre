package com.facturastock.app.domain.model

import java.math.BigDecimal

internal object ExactDecimalPolicy {
    const val MAX_INPUT_CHARACTERS = 128
    const val MAX_VALUE_PRECISION = 38
    const val MAX_VALUE_SCALE = 18

    private const val MIN_MONEY_SCALE = -18
    private const val MAX_MONEY_SCALE = MAX_VALUE_SCALE * 2
    private const val MAX_MONEY_PRECISION = MAX_VALUE_PRECISION * 2
    private const val ERROR_PREVIEW_CHARACTERS = 64

    fun supportsValue(value: BigDecimal): Boolean =
        value.precision() <= MAX_VALUE_PRECISION && value.scale() in 0..MAX_VALUE_SCALE

    fun supportsMoneyInput(value: BigDecimal): Boolean =
        value.precision() <= MAX_MONEY_PRECISION &&
            value.scale() in MIN_MONEY_SCALE..MAX_MONEY_SCALE

    fun supportsInput(input: String): Boolean = input.length <= MAX_INPUT_CHARACTERS

    fun safeText(value: BigDecimal): String = value.toString()

    fun safeInput(input: String): String =
        if (supportsInput(input)) {
            input
        } else {
            "${input.take(ERROR_PREVIEW_CHARACTERS)}…(length=${input.length})"
        }
}
