package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.math.BigInteger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Properties reproducibles sobre todo el rango Long. La semilla forma parte del contrato del test:
 * un fallo puede reproducirse localmente sin publicar el importe que lo provocó.
 */
class MoneyDeterministicPropertyTest {

    @Test
    fun `minor-major round trip is exact across currencies and the full Long range`() {
        val random = Random(ROUND_TRIP_SEED)
        val currencies = listOf("PEN", "USD", "JPY", "KWD").map(CurrencyCode::of)
        val boundaries = longArrayOf(
            Long.MIN_VALUE,
            Long.MIN_VALUE + 1,
            -1L,
            0L,
            1L,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE,
        )

        repeat(PROPERTY_CASES) { iteration ->
            val minorUnits = boundaries.getOrNull(iteration) ?: random.nextLong()
            val currency = currencies[random.nextInt(currencies.size)]
            val original = Money.ofMinor(minorUnits, currency)

            val restored = Money.fromMajor(original.toMajor(), currency)

            assertEquals(
                "seed=$ROUND_TRIP_SEED case=$iteration",
                original,
                restored,
            )
        }
    }

    @Test
    fun `addition and subtraction agree with an unbounded integer oracle or fail closed`() {
        val random = Random(ARITHMETIC_SEED)
        val pen = CurrencyCode.of("PEN")
        val minimum = BigInteger.valueOf(Long.MIN_VALUE)
        val maximum = BigInteger.valueOf(Long.MAX_VALUE)

        repeat(PROPERTY_CASES) { iteration ->
            val left = random.nextLong()
            val right = random.nextLong()
            val addition = random.nextBoolean()
            val expected = if (addition) {
                BigInteger.valueOf(left).add(BigInteger.valueOf(right))
            } else {
                BigInteger.valueOf(left).subtract(BigInteger.valueOf(right))
            }
            val label = "seed=$ARITHMETIC_SEED case=$iteration"

            if (expected in minimum..maximum) {
                val actual = if (addition) {
                    Money.ofMinor(left, pen) + Money.ofMinor(right, pen)
                } else {
                    Money.ofMinor(left, pen) - Money.ofMinor(right, pen)
                }
                assertEquals(label, expected.longValueExact(), actual.minorUnits)
            } else {
                val failure = assertThrows(DomainRuleViolation::class.java) {
                    if (addition) {
                        Money.ofMinor(left, pen) + Money.ofMinor(right, pen)
                    } else {
                        Money.ofMinor(left, pen) - Money.ofMinor(right, pen)
                    }
                }
                assertEquals(
                    label,
                    ValidationError.ArithmeticOverflow(
                        if (addition) "money.add" else "money.subtract",
                    ),
                    failure.error,
                )
            }
        }
    }

    private companion object {
        const val ROUND_TRIP_SEED = 0x51A7E001
        const val ARITHMETIC_SEED = 0x51A7E002
        const val PROPERTY_CASES = 20_000
    }
}
