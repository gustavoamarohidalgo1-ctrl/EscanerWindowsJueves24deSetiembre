package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeruvianValueParserTest {
    @Test
    fun `money fixtures produce exact PEN minor units for both separator conventions`() {
        val fixtures = listOf(
            MoneyFixture("S/ 1,234.56", 123_456L),
            MoneyFixture("PEN 1.234,56", 123_456L),
            MoneyFixture("Total: S/ 12,34", 1_234L),
            MoneyFixture("S / 10,50", 1_050L),
            MoneyFixture("PEN:10,50", 1_050L),
            MoneyFixture("Ｓ／ １２,５０", 1_250L),
            MoneyFixture("IGV 18%  TOTAL S/ 100.00", 10_000L),
        )

        fixtures.forEach { fixture ->
            val candidate = requireNotNull(PeruvianValueParser.parseMoney(fixture.raw))
            assertEquals(fixture.raw, fixture.expectedMinorUnits, candidate.value?.minorUnits)
            assertEquals("PEN", candidate.value?.currency?.value)
            assertFalse(fixture.raw, candidate.requiresReview)
        }
    }

    @Test
    fun `ambiguous separator is surfaced and never chosen silently`() {
        listOf("S/ 1,234", "S/ 1.234").forEach { raw ->
            val candidate = requireNotNull(PeruvianValueParser.parseMoney(raw))

            assertNull(raw, candidate.value)
            assertTrue(raw, candidate.requiresReview)
            assertTrue(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR in candidate.warnings)
            assertEquals(raw, candidate.evidence.rawText)
        }
    }

    @Test
    fun `numeric OCR correction only happens in monetary context and remains reviewable`() {
        val raw = "Total S/ 1.O34,5O"
        val box = InvoiceTextBoundingBox(5, 6, 90, 30)

        val candidate = requireNotNull(
            PeruvianValueParser.parseMoney(raw, box, confidencePermille = 812),
        )

        assertNull(candidate.value)
        assertEquals(listOf(103_450L), candidate.alternatives.map { it.minorUnits })
        assertTrue(CandidateWarning.OCR_CORRECTION_APPLIED in candidate.warnings)
        assertEquals(raw, candidate.evidence.rawText)
        assertEquals("1.O34,5O", candidate.evidence.corrections.single().originalFragment)
        assertEquals("1.034,50", candidate.evidence.corrections.single().correctedFragment)
        assertEquals(box, candidate.boundingBox)
        assertEquals(812, candidate.confidencePermille)
    }

    @Test
    fun `currency supplied only by context stays doubtful`() {
        val candidate = requireNotNull(
            PeruvianValueParser.parseMoney(
                rawText = "1,234.56",
                currencyContext = CurrencyCode.of("PEN"),
            ),
        )

        assertNull(candidate.value)
        assertEquals(123_456L, candidate.alternatives.single().minorUnits)
        assertTrue(CandidateWarning.CURRENCY_FROM_CONTEXT in candidate.warnings)
    }

    @Test
    fun `conflicting explicit currencies stay unresolved`() {
        val candidate = requireNotNull(PeruvianValueParser.parseMoney("S/ 10.00 USD"))

        assertNull(candidate.value)
        assertEquals(setOf("PEN", "USD"), candidate.alternatives.map { it.currency.value }.toSet())
        assertTrue(CandidateWarning.CURRENCY_CONFLICT in candidate.warnings)

        val conflictsWithContext = requireNotNull(
            PeruvianValueParser.parseMoney(
                rawText = "USD 10.00",
                currencyContext = CurrencyCode.of("PEN"),
            ),
        )
        assertNull(conflictsWithContext.value)
        assertEquals(
            setOf("PEN", "USD"),
            conflictsWithContext.alternatives.map { it.currency.value }.toSet(),
        )
        assertTrue(CandidateWarning.CURRENCY_CONFLICT in conflictsWithContext.warnings)
    }

    @Test
    fun `each amount remains attached only to its adjacent currency evidence`() {
        val candidate = requireNotNull(
            PeruvianValueParser.parseMoney("USD 10.00; PEN 20.00"),
        )

        assertNull(candidate.value)
        assertEquals(
            setOf("USD:1000", "PEN:2000"),
            candidate.alternatives.map { "${it.currency.value}:${it.minorUnits}" }.toSet(),
        )
        assertTrue(CandidateWarning.MULTIPLE_VALUES_FOUND in candidate.warnings)
        assertTrue(CandidateWarning.CURRENCY_CONFLICT in candidate.warnings)

        val markerBetweenNumbers = requireNotNull(
            PeruvianValueParser.parseMoney("10 USD 20"),
        )
        assertNull(markerBetweenNumbers.value)
        assertEquals(
            setOf(1_000L, 2_000L),
            markerBetweenNumbers.alternatives.map { it.minorUnits }.toSet(),
        )
        assertTrue(CandidateWarning.MULTIPLE_VALUES_FOUND in markerBetweenNumbers.warnings)

        val allAdjacentPairs = requireNotNull(
            PeruvianValueParser.parseMoney("10 USD 20 PEN 30"),
        )
        assertEquals(
            setOf("USD:1000", "USD:2000", "PEN:2000", "PEN:3000"),
            allAdjacentPairs.alternatives
                .map { "${it.currency.value}:${it.minorUnits}" }
                .toSet(),
        )
    }

    @Test
    fun `invalid grouping precision signs and exponents are never rounded or guessed`() {
        listOf(
            "S/ 1,23.45",
            "S/ 1,,234",
            "S/ 1..234",
            "S/ 1,234XYZ",
            "S/ 1 E-3",
            "S/ 1 E −3",
            "S/ -10.00",
            "S/ - 10.00",
            "- S/ 10.00",
            "+ PEN 10.00",
            "− S/ 10.00",
            "(S/ 10.00)",
            "(10.00 PEN)",
            "S/ 10.00 PEN -",
            "S/ 10.00 PEN−",
            "S/ 13/08/2026",
        ).forEach { raw -> assertNull(raw, PeruvianValueParser.parseMoney(raw)) }
        listOf(
            "+1",
            "1E-3",
            "1 E-3",
            "1 e-3",
            "1 E 3",
            ".50",
            ",50",
            "10.",
            "10,",
            "- 10",
            "‒10",
            "2 100",
        )
            .forEach { raw -> assertNull(raw, PeruvianValueParser.parseQuantity(raw)) }

        val excessivePrecision = requireNotNull(PeruvianValueParser.parseMoney("S/ 1.005"))
        assertNull(excessivePrecision.value)
        assertTrue(excessivePrecision.requiresReview)
        assertTrue(
            CandidateWarning.UNSUPPORTED_FRACTION_PRECISION in excessivePrecision.warnings,
        )

        assertEquals(
            33L,
            PeruvianValueParser.parseMoney("PEN 0,3300")?.value?.minorUnits,
        )
        assertEquals(
            123L,
            PeruvianValueParser.parseMoney("S/ 1.2300")?.value?.minorUnits,
        )
        assertNull(PeruvianValueParser.parseMoney("S/ 2 100"))
        assertNull(PeruvianValueParser.parseMoney("S/. 10.00"))
    }

    @Test
    fun `consecutive incompatible currency markers stay doubtful`() {
        listOf("S/ USD 10.00", "USD PEN 10.00").forEach { raw ->
            val candidate = requireNotNull(PeruvianValueParser.parseMoney(raw))

            assertNull(raw, candidate.value)
            assertEquals(
                raw,
                setOf("PEN", "USD"),
                candidate.alternatives.map { it.currency.value }.toSet(),
            )
            assertTrue(raw, CandidateWarning.CURRENCY_CONFLICT in candidate.warnings)
        }
    }

    @Test
    fun `quantity fixtures preserve scale and expose single-separator ambiguity`() {
        val certain = listOf(
            "1,234.500" to BigDecimal("1234.500"),
            "1.234,500" to BigDecimal("1234.500"),
            "12,3456" to BigDecimal("12.3456"),
            "1,234,567" to BigDecimal("1234567"),
            "Cantidad: 2" to BigDecimal("2"),
        )
        certain.forEach { (raw, expected) ->
            val candidate = requireNotNull(PeruvianValueParser.parseQuantity(raw))
            assertEquals(raw, expected, candidate.value?.value)
            assertFalse(raw, candidate.requiresReview)
        }

        listOf("1,234", "1.234").forEach { raw ->
            val candidate = requireNotNull(PeruvianValueParser.parseQuantity(raw))
            assertNull(candidate.value)
            assertEquals(
                setOf(BigDecimal("1234"), BigDecimal("1.234")),
                candidate.alternatives.map { it.value }.toSet(),
            )
            assertTrue(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR in candidate.warnings)
        }
        assertNull(PeruvianValueParser.parseQuantity("0"))
    }

    @Test
    fun `fractional unit cost remains exact without forcing cents`() {
        val candidate = requireNotNull(PeruvianValueParser.parseUnitCost("S/ 0,333300"))

        assertEquals(BigDecimal("0.333300"), candidate.value?.amount)
        assertEquals("PEN", candidate.value?.currency?.value)
        assertFalse(candidate.requiresReview)

        val ambiguousZero = requireNotNull(PeruvianValueParser.parseUnitCost("S/ 0,000"))
        assertNull(ambiguousZero.value)
        assertEquals(
            setOf(BigDecimal("0"), BigDecimal("0.000")),
            ambiguousZero.alternatives.map { it.amount }.toSet(),
        )
        assertTrue(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR in ambiguousZero.warnings)
    }

    @Test
    fun `dates accept strict unambiguous forms and reject impossible calendar values`() {
        val certain = listOf(
            "2026-08-07" to LocalDate.of(2026, 8, 7),
            "13/08/2026" to LocalDate.of(2026, 8, 13),
            "31-12-2026" to LocalDate.of(2026, 12, 31),
            "29/02/2024" to LocalDate.of(2024, 2, 29),
            "12/12/2026" to LocalDate.of(2026, 12, 12),
        )
        certain.forEach { (raw, expected) ->
            val candidate = requireNotNull(PeruvianValueParser.parseDate(raw))
            assertEquals(raw, expected, candidate.value)
            assertFalse(raw, candidate.requiresReview)
        }

        listOf(
            "31/04/2026",
            "29/02/2025",
            "29/02/1900",
            "0000-01-01",
            "00/12/2026",
            "32/01/2026",
            "15/13/2026",
            "13/08-2026",
            "13/08/2026/99",
            "13/08/2026.5",
            "13/08/2026,5",
            "13/08/2026%5",
            "13/08/2026+5",
            "13/08/2026'5",
            "13/08/2026|5",
            "99/99/99",
            "00/00/00",
            "31/04/25",
        ).forEach { raw -> assertNull(raw, PeruvianValueParser.parseDate(raw)) }

        assertEquals(
            LocalDate.of(2000, 2, 29),
            PeruvianValueParser.parseDate("29/02/2000")?.value,
        )
    }

    @Test
    fun `ambiguous non-Peruvian noisy and short-year dates remain unresolved`() {
        val ambiguous = requireNotNull(PeruvianValueParser.parseDate("07/08/2026"))
        assertNull(ambiguous.value)
        assertEquals(
            setOf(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 7, 8)),
            ambiguous.alternatives.toSet(),
        )
        assertTrue(CandidateWarning.AMBIGUOUS_DATE_ORDER in ambiguous.warnings)

        val nonPeruvian = requireNotNull(PeruvianValueParser.parseDate("08/13/2026"))
        assertNull(nonPeruvian.value)
        assertEquals(LocalDate.of(2026, 8, 13), nonPeruvian.alternatives.single())
        assertTrue(CandidateWarning.NON_PERUVIAN_DATE_ORDER in nonPeruvian.warnings)

        val noisy = requireNotNull(PeruvianValueParser.parseDate("Fecha 3I/O1/2O26"))
        assertNull(noisy.value)
        assertEquals(LocalDate.of(2026, 1, 31), noisy.alternatives.single())
        assertTrue(CandidateWarning.OCR_CORRECTION_APPLIED in noisy.warnings)
        assertEquals("Fecha 3I/O1/2O26", noisy.evidence.rawText)

        val shortYear = requireNotNull(PeruvianValueParser.parseDate("01/02/03"))
        assertNull(shortYear.value)
        assertTrue(CandidateWarning.TWO_DIGIT_YEAR in shortYear.warnings)
        assertTrue(shortYear.alternatives.isEmpty())
    }

    @Test
    fun `unit fixtures map SUNAT codes and aliases without changing their evidence`() {
        val fixtures = listOf(
            UnitFixture("NIU", InvoiceUnitCode.NIU, alias = false),
            UnitFixture("UND", InvoiceUnitCode.NIU, alias = true),
            UnitFixture("UN", InvoiceUnitCode.NIU, alias = true),
            UnitFixture("KGM", InvoiceUnitCode.KGM, alias = false),
            UnitFixture("kg", InvoiceUnitCode.KGM, alias = true),
            UnitFixture("LTR", InvoiceUnitCode.LTR, alias = false),
        )
        fixtures.forEach { fixture ->
            val candidate = requireNotNull(PeruvianValueParser.parseUnit(fixture.raw))
            assertEquals(fixture.raw, fixture.expected, candidate.value)
            assertEquals(
                fixture.raw,
                fixture.alias,
                CandidateWarning.UNIT_ALIAS_NORMALIZED in candidate.warnings,
            )
            assertEquals(fixture.raw, candidate.evidence.rawText)
            assertFalse(fixture.raw, candidate.requiresReview)
        }

        val noisy = requireNotNull(PeruvianValueParser.parseUnit("K6"))
        assertNull(noisy.value)
        assertEquals(listOf(InvoiceUnitCode.KGM), noisy.alternatives)
        assertTrue(CandidateWarning.OCR_CORRECTION_APPLIED in noisy.warnings)
        assertEquals("K6", noisy.evidence.rawText)

        assertNull(PeruvianValueParser.parseUnit("UN PRODUCTO"))
        listOf("KG/M", "KG-H", "KG.M", "LTR/MIN", "NIU/CAJA")
            .forEach { raw -> assertNull(raw, PeruvianValueParser.parseUnit(raw)) }
        listOf("1TR", "|TR").forEach { raw ->
            val corrected = requireNotNull(PeruvianValueParser.parseUnit(raw))
            assertNull(raw, corrected.value)
            assertEquals(raw, listOf(InvoiceUnitCode.LTR), corrected.alternatives)
            assertTrue(raw, CandidateWarning.OCR_CORRECTION_APPLIED in corrected.warnings)
        }
    }

    @Test
    fun `parsing does not depend on the process default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            assertEquals(
                123_456L,
                PeruvianValueParser.parseMoney("PEN 1.234,56")?.value?.minorUnits,
            )
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                BigDecimal("1234.500"),
                PeruvianValueParser.parseQuantity("1,234.500")?.value?.value,
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `seeded monetary formats round trip to their exact minor units`() {
        val random = Random(0x50_45_52_55)

        repeat(10_000) { sample ->
            val expectedMinor = random.nextLong(from = 1L, until = 100_000_000_000L)
            val integerPart = expectedMinor / 100L
            val fractionPart = (expectedMinor % 100L).toString().padStart(2, '0')
            val groupedUs = integerPart.toString().reversed().chunked(3).joinToString(",").reversed()
            val groupedEu = integerPart.toString().reversed().chunked(3).joinToString(".").reversed()
            val representations = listOf(
                "PEN $groupedUs.$fractionPart",
                "S/ $groupedEu,$fractionPart",
            )

            representations.forEach { raw ->
                val candidate = requireNotNull(PeruvianValueParser.parseMoney(raw)) {
                    "seed=0x50455255 sample=$sample raw=$raw"
                }
                assertEquals(raw, expectedMinor, candidate.value?.minorUnits)
                assertFalse(raw, candidate.requiresReview)
                assertTrue(raw, candidate.alternatives.isEmpty())
            }
        }
    }

    @Test
    fun `seeded hostile text fuzz never throws and preserves candidate invariants`() {
        val random = Random(0x46_55_5A_5A)
        val alphabet = (
            "0123456789.,/-+() %:;|EeOIL" +
                "PENSUSDIGVFechaTOTALNº\u0000\u0001\u001f" +
                "٠١٢٣٤٥٦٧٨٩０１２３，．−"
            ).toCharArray()

        repeat(5_000) { sample ->
            val raw = buildString {
                repeat(random.nextInt(from = 0, until = 161)) {
                    append(alphabet[random.nextInt(alphabet.size)])
                }
            }
            val candidates = listOf(
                PeruvianValueParser.parseMoney(raw),
                PeruvianValueParser.parseUnitCost(raw),
                PeruvianValueParser.parseQuantity(raw),
                PeruvianValueParser.parseDate(raw),
                PeruvianValueParser.parseUnit(raw),
            )

            candidates.filterNotNull().forEach { candidate ->
                assertEquals(
                    "seed=0x46555A5A sample=$sample raw=$raw",
                    candidate.value == null,
                    candidate.requiresReview,
                )
                assertEquals(
                    "seed=0x46555A5A sample=$sample raw=$raw",
                    candidate.alternatives.distinct(),
                    candidate.alternatives,
                )
                if (candidate.value != null) {
                    assertTrue(raw, candidate.alternatives.isEmpty())
                }
            }
        }
    }

    @Test
    fun `oversized OCR fragments fail closed before normalization`() {
        val oversized = "1".repeat(70_000)

        assertNull(PeruvianValueParser.parseMoney(oversized))
        assertNull(PeruvianValueParser.parseUnitCost(oversized))
        assertNull(PeruvianValueParser.parseQuantity(oversized))
        assertNull(PeruvianValueParser.parseDate(oversized))
        assertNull(PeruvianValueParser.parseUnit(oversized))
    }

    @Test(timeout = 5_000L)
    fun `large bounded monetary OCR stays linear`() {
        val large = buildString {
            repeat(5_000) { append("S/ 1.00 X ") }
        }

        val candidate = requireNotNull(PeruvianValueParser.parseMoney(large))
        assertTrue(candidate.requiresReview)
        assertEquals(1, candidate.alternatives.size)
    }

    private data class MoneyFixture(val raw: String, val expectedMinorUnits: Long)
    private data class UnitFixture(
        val raw: String,
        val expected: InvoiceUnitCode,
        val alias: Boolean,
    )
}
