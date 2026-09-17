package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.BarcodeValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeSimilarityTest {
    @Test
    fun `detecta omisiones al inicio en medio y al final en ambas direcciones`() {
        val complete = "7751234567890"
        val cases =
            listOf(
                "751234567890" to 1,
                "775123467890" to 1,
                "775123456789" to 1,
                "51234567890" to 2,
                "77512367890" to 2,
                "77512345678" to 2,
                "1234567890" to 3,
                "7751237890" to 3,
                "7751234567" to 3,
            )

        cases.forEach { (incomplete, missing) ->
            assertEquals(incomplete, missing, BarcodeSimilarity.missingDigits(incomplete, complete))
            assertEquals(incomplete, missing, BarcodeSimilarity.missingDigits(complete, incomplete))
        }
    }

    @Test
    fun `detecta dos y tres omisiones no contiguas sin depender del lugar`() {
        assertEquals(2, BarcodeSimilarity.missingDigits("77124567890", "7751234567890"))
        assertEquals(3, BarcodeSimilarity.missingDigits("7512346789", "7751234567890"))
        assertEquals(3, BarcodeSimilarity.missingDigits("7751234567890", "7512346789"))
    }

    @Test
    fun `todas las combinaciones de una a tres omisiones conservan su distancia`() {
        val complete = "7751234567890"
        for (mask in 1 until (1 shl complete.length)) {
            val missing = Integer.bitCount(mask)
            if (missing !in 1..3) continue
            val incomplete = complete.filterIndexed { index, _ -> mask and (1 shl index) == 0 }

            assertEquals(
                "omisión $mask en la lectura",
                missing,
                BarcodeSimilarity.missingDigits(incomplete, complete),
            )
            assertEquals(
                "omisión $mask en el catálogo",
                missing,
                BarcodeSimilarity.missingDigits(complete, incomplete),
            )
        }
    }

    @Test
    fun `los digitos repetidos se emparejan sin reutilizar posiciones`() {
        assertEquals(1, BarcodeSimilarity.missingDigits("1123344", "11223344"))
        assertEquals(3, BarcodeSimilarity.missingDigits("11111", "11111111"))
        assertNull(BarcodeSimilarity.missingDigits("1111112", "11111222"))
    }

    @Test
    fun `los ceros iniciales cuentan como omisiones y nunca se eliminan`() {
        assertEquals(1, BarcodeSimilarity.missingDigits("012345678", "0012345678"))
        assertEquals(3, BarcodeSimilarity.missingDigits("12345", "00012345"))
        assertNull(BarcodeSimilarity.missingDigits("12345", "000012345"))
        assertNull(BarcodeSimilarity.missingDigits("0012345678", "0012345678"))
    }

    @Test
    fun `acepta los limites conservadores de longitud`() {
        assertEquals(1, BarcodeSimilarity.missingDigits("1234567", "12345678"))
        assertEquals(2, BarcodeSimilarity.missingDigits("123456", "12345678"))
        assertEquals(3, BarcodeSimilarity.missingDigits("12345", "12345678"))
        assertEquals(
            3,
            BarcodeSimilarity.missingDigits(
                "1".repeat(BarcodeValue.MAX_LENGTH - 3),
                "1".repeat(BarcodeValue.MAX_LENGTH),
            ),
        )
    }

    @Test
    fun `rechaza vacios codigos cortos mas de tres omisiones y longitud excesiva`() {
        val pairs =
            listOf(
                "" to "12345678",
                "123456" to "1234567",
                "1234" to "1234567",
                "12345" to "123456789",
                "1".repeat(BarcodeValue.MAX_LENGTH) to "1".repeat(BarcodeValue.MAX_LENGTH + 1),
            )

        pairs.forEach { (left, right) ->
            assertNull(BarcodeSimilarity.missingDigits(left, right))
            assertNull(BarcodeSimilarity.missingDigits(right, left))
        }
    }

    @Test
    fun `rechaza sustituciones transposiciones y parecidos sin subsecuencia`() {
        val pairs =
            listOf(
                "12345678" to "12345678",
                "12345678" to "12345679",
                "12345678" to "1234579",
                "123456789" to "12354689",
                "11223344" to "1234344",
                "12345678" to "8765432",
            )

        pairs.forEach { (left, right) ->
            assertNull("$left / $right", BarcodeSimilarity.missingDigits(left, right))
            assertNull("$right / $left", BarcodeSimilarity.missingDigits(right, left))
        }
    }

    @Test
    fun `rechaza caracteres no numericos ASCII incluso si solo falta ese caracter`() {
        val pairs =
            listOf(
                "12345678" to "A12345678",
                "A1234567" to "A12345678",
                "12345678" to " 12345678",
                "12345678" to "12345678 ",
                "12345678" to "1234-5678",
                "12345678" to "1234\t5678",
                "12345678" to "12345678\n",
                "12345678" to "\uFF1112345678",
                "\u06612345678" to "\u066123456789",
                "12345678" to "1234\u202E5678",
            )

        pairs.forEach { (left, right) ->
            assertNull(BarcodeSimilarity.missingDigits(left, right))
            assertNull(BarcodeSimilarity.missingDigits(right, left))
        }
    }
}
