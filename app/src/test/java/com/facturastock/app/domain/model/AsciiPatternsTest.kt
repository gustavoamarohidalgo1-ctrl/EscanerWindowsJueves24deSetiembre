package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.containsSubsequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Los recorridos ASCII reemplazan expresiones regulares en la lectura de cada fila. Deben
 * aceptar exactamente lo mismo que el patrón original para cualquier texto.
 */
class AsciiPatternsTest {
    private val random = Random(20260924)

    @Test
    fun decimalsMatchOriginalPatternsForGeneratedText() {
        val plain = Regex("^\\d+(\\.\\d+)?$")
        val signed = Regex("^-?\\d+(\\.\\d+)?$")
        val samples = generated("0123456789.-+e ", maxLength = 8, count = 60_000) + decimalEdgeCases
        samples.forEach { value ->
            assertEquals(value, plain.matches(value), AsciiPatterns.isPlainDecimal(value))
            assertEquals(value, signed.matches(value), AsciiPatterns.isSignedPlainDecimal(value))
        }
    }

    @Test
    fun uuidMatchesOriginalPatternIncludingMutations() {
        val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val alphabet = "0123456789abcdefABCDEFg-_ "
        repeat(40_000) {
            val canonical = UUID(random.nextLong(), random.nextLong()).toString()
            val chars = canonical.toCharArray()
            repeat(random.nextInt(0, 3)) { chars[random.nextInt(chars.size)] = alphabet.random(random) }
            val mutated =
                when (random.nextInt(6)) {
                    0 -> String(chars).drop(1)
                    1 -> String(chars) + alphabet.random(random)
                    2 -> String(chars).uppercase()
                    else -> String(chars)
                }
            assertEquals(mutated, pattern.matches(mutated), AsciiPatterns.isLowercaseUuid(mutated))
        }
        assertTrue(AsciiPatterns.isLowercaseUuid("00000000-0000-0000-0000-000000000000"))
        assertFalse(AsciiPatterns.isLowercaseUuid(""))
    }

    @Test
    fun fixedAlphabetPatternsMatchOriginals() {
        val hex64 = Regex("^[0-9a-f]{64}$")
        val ruc = Regex("^\\d{11}$")
        val currency = Regex("^[A-Z]{3}$")
        val unitCode = Regex("^[A-Z0-9]{1,16}$")
        generated("0123456789abcdefG", maxLength = 66, count = 20_000, minLength = 62).forEach {
            assertEquals(it, hex64.matches(it), AsciiPatterns.isLowerHex(it, 64))
        }
        generated("0123456789 a-", maxLength = 12, count = 20_000).forEach {
            assertEquals(it, ruc.matches(it), AsciiPatterns.isAsciiDigits(it, 11))
        }
        generated("PENUSDpen1 ", maxLength = 4, count = 20_000).forEach {
            assertEquals(it, currency.matches(it), AsciiPatterns.isUpperLetters(it, 3))
        }
        generated("KGMNIU09a-", maxLength = 18, count = 20_000).forEach {
            assertEquals(it, unitCode.matches(it), AsciiPatterns.isUpperAlphanumeric(it, 1, 16))
        }
    }

    @Test
    fun identifierParsingKeepsPreviousCanonicalRule() {
        val nil = UUID(0L, 0L)

        fun previousRule(input: String): String? {
            if (input.length != 36 || input != input.lowercase()) return null
            val parsed = runCatching { UUID.fromString(input) }.getOrNull() ?: return null
            return parsed.takeIf { it != nil && it.toString() == input }?.toString()
        }
        val inputs =
            List(20_000) {
                val canonical = UUID(random.nextLong(), random.nextLong()).toString()
                when (random.nextInt(5)) {
                    0 -> canonical.uppercase()
                    1 -> canonical.replaceFirst("-", "")
                    2 -> "1-1-1-1-1"
                    else -> canonical
                }
            } + listOf(nil.toString(), "", "not-a-uuid")
        inputs.forEach { input ->
            assertEquals(input, previousRule(input), ProductId.parse(input)?.value)
            assertEquals(input, previousRule(input), BusinessId.parse(input)?.value)
        }
    }

    @Test
    fun currencyCodeAcceptsPersistedAndTypedFormsThroughCache() {
        repeat(3) {
            assertEquals("PEN", CurrencyCode.of("PEN").value)
            assertEquals("PEN", CurrencyCode.of(" pen ").value)
            assertEquals("USD", CurrencyCode.of("usd").value)
        }
        assertEquals(2, CurrencyCode.of("PEN").defaultFractionDigits)
        assertNull(runCatching { CurrencyCode.of("ZZZ") }.getOrNull())
        assertNull(runCatching { CurrencyCode.of("PE") }.getOrNull())
    }

    @Test
    fun subsequenceMatchesReferenceDefinition() {
        fun reference(
            text: String,
            needle: String,
        ): Boolean {
            var index = 0
            text.forEach { if (index < needle.length && it == needle[index]) index++ }
            return index == needle.length
        }
        generated("0123", maxLength = 7, count = 20_000).forEach { text ->
            val needle = generated("0123", maxLength = 4, count = 1, minLength = 0).single()
            assertEquals("$text/$needle", reference(text, needle), containsSubsequence(text, needle))
        }
        assertTrue(containsSubsequence("7753176004930", "753176004930"))
        assertFalse(containsSubsequence("7753176004930", "753176004931"))
    }

    private fun generated(
        alphabet: String,
        maxLength: Int,
        count: Int,
        minLength: Int = 0,
    ): List<String> =
        List(count) {
            val length = random.nextInt(minLength, maxLength + 1)
            buildString(length) { repeat(length) { append(alphabet.random(random)) } }
        }

    private val decimalEdgeCases =
        listOf("", "0", "00", "0.0", ".5", "5.", "-", "-.", "-0", "-0.10", "--1", "1.2.3", "1 ", " 1", "1\n", "+1", "1e5")
}
