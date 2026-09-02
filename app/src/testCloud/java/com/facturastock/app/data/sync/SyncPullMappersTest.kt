package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.StockMovementType
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El mapper del pull es la única frontera entre el JSON del backend y el dominio: una página
 * bien formada se traduce exacta y cualquier desviación (seq no entera, UUID no canónico,
 * estado fuera del conjunto cerrado, decimal ilegible) es [AccountError.Unexpected].
 */
class SyncPullMappersTest {

    @Test
    fun `pagina bien formada se traduce exacta, con nulos permitidos`() {
        val page = SyncPullMappers.pullPage(
            mapOf(
                "changes" to listOf(
                    mapOf(
                        "seq" to 7L,
                        "purchaseId" to PURCHASE_ID,
                        "status" to "POSTED",
                        "documentType" to "INVOICE",
                        "documentSeries" to "F001",
                        "documentNumber" to "00000042",
                        "issueDate" to "2026-08-15",
                        "currency" to "PEN",
                        "supplierRuc" to "20123456789",
                        "supplierLegalName" to "Proveedor SAC",
                        "totalMinorUnits" to 1_180L,
                        "movementSummary" to listOf(
                            mapOf(
                                "productId" to PRODUCT_ID,
                                "productName" to "Arroz Extra",
                                "type" to "PURCHASE",
                                "quantityDelta" to "2.5",
                            ),
                            mapOf(
                                "productId" to PRODUCT_ID,
                                "productName" to null,
                                "type" to "PURCHASE",
                                "quantityDelta" to "1",
                            ),
                        ),
                        "receiptId" to RECEIPT_ID,
                        "syncedAtMillis" to 1_756_000_000_000L,
                        "syncedBy" to null,
                    ),
                ),
                "nextCursor" to 7L,
                "hasMore" to false,
            ),
        )

        assertEquals(7L, page.nextCursor)
        assertEquals(false, page.hasMore)
        val change = page.changes.single()
        assertEquals(7L, change.seq)
        assertEquals(PURCHASE_ID, change.purchaseId)
        assertEquals(PurchaseStatus.POSTED, change.status)
        assertEquals("20123456789", change.supplierRuc)
        assertEquals(1_180L, change.totalMinorUnits)
        assertEquals(RECEIPT_ID, change.receiptId)
        assertEquals(1_756_000_000_000L, change.syncedAtMillis)
        assertNull(change.syncedBy)
        assertEquals(2, change.movementSummary.size)
        assertEquals(StockMovementType.PURCHASE, change.movementSummary[0].type)
        assertEquals(BigDecimal("2.5"), change.movementSummary[0].quantityDelta)
        assertNull(change.movementSummary[1].productName)
        assertEquals(StockMovementType.PURCHASE, change.movementSummary[1].type)
        assertEquals(BigDecimal("1"), change.movementSummary[1].quantityDelta)
    }

    @Test
    fun `pagina vacia con cursor al dia es valida`() {
        val page = SyncPullMappers.pullPage(
            mapOf("changes" to emptyList<Any>(), "nextCursor" to 0L, "hasMore" to false),
        )

        assertTrue(page.changes.isEmpty())
        assertEquals(0L, page.nextCursor)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun `descripcion puntual bien formada con syncedAt ya convertido a millis`() {
        val description = SyncPullMappers.purchaseDescription(
            PURCHASE_ID,
            mapOf(
                "status" to "VOIDED",
                "documentType" to "INVOICE",
                "documentSeries" to "F001",
                "documentNumber" to "42",
                "issueDate" to "2026-08-10",
                "currency" to "PEN",
                "supplierRuc" to null,
                "supplierLegalName" to "Proveedor SAC",
                "totalMinorUnits" to 500L,
                "receiptId" to "receipt-9",
                "syncedAtMillis" to null,
                "syncedBy" to null,
            ),
        )

        assertEquals(PURCHASE_ID, description.purchaseId)
        assertEquals(PurchaseStatus.VOIDED, description.status)
        assertNull(description.supplierRuc)
        assertEquals("receipt-9", description.receiptId)
        assertNull(description.syncedAtMillis)
    }

    @Test
    fun `seq con fraccion o menor que uno es payload malformado`() {
        val valid = change()
        assertMalformed(valid + ("seq" to 1.5))
        assertMalformed(valid + ("seq" to 0L))
        assertMalformed(valid + ("seq" to "7"))
        assertMalformed(valid + ("seq" to 9_007_199_254_740_992L))
        assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(
                mapOf(
                    "changes" to emptyList<Any>(),
                    "nextCursor" to 9_007_199_254_740_992L,
                    "hasMore" to false,
                ),
            )
        }
    }

    @Test
    fun `enteros grandes se convierten exactamente sin truncar BigDecimal`() {
        val maximum = BigDecimal("9007199254740991")
        val accepted = SyncPullMappers.pullPage(
            mapOf(
                "changes" to listOf(change() + ("seq" to maximum)),
                "nextCursor" to maximum,
                "hasMore" to false,
            ),
        )
        assertEquals(9_007_199_254_740_991L, accepted.changes.single().seq)
        assertEquals(9_007_199_254_740_991L, accepted.nextCursor)

        val alsoAccepted = SyncPullMappers.pullPage(
            mapOf(
                "changes" to listOf(change() + ("seq" to BigInteger("7"))),
                "nextCursor" to BigInteger("7"),
                "hasMore" to false,
            ),
        )
        assertEquals(7L, alsoAccepted.nextCursor)

        val fractionalAtDoublePrecisionBoundary = BigDecimal("9007199254740990.5")
        assertPageMalformed(
            mapOf(
                "changes" to listOf(
                    change() + ("seq" to fractionalAtDoublePrecisionBoundary),
                ),
                "nextCursor" to fractionalAtDoublePrecisionBoundary,
                "hasMore" to false,
            ),
        )
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertPageMalformed(
                mapOf("changes" to emptyList<Any>(), "nextCursor" to value, "hasMore" to false),
            )
        }
    }

    @Test
    fun `purchaseId no canonico es payload malformado`() {
        assertMalformed(change() + ("purchaseId" to "no-es-uuid"))
        assertMalformed(change() + ("purchaseId" to PURCHASE_ID.uppercase()))
        assertMalformed(change() + ("purchaseId" to "00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `receiptId fuera del patron interno es payload malformado`() {
        assertMalformed(change() + ("receiptId" to "receipt-1"))
        assertMalformed(change() + ("receiptId" to "rcpt_${"A".repeat(32)}"))
    }

    @Test
    fun `el feed compacto rechaza identidad de cuenta aunque sea texto`() {
        assertMalformed(change() + ("syncedBy" to "uid-no-permitido"))
    }

    @Test
    fun `estado fuera del conjunto cerrado es payload malformado`() {
        assertMalformed(change() + ("status" to "DRAFT"))
        assertMalformed(change() + ("status" to "posted"))
        assertMalformed(change() + ("status" to null))
    }

    @Test
    fun `movimiento con tipo desconocido o cantidad ilegible es payload malformado`() {
        assertMalformed(
            change(
                movementSummary = listOf(
                    mapOf(
                        "productId" to PRODUCT_ID,
                        "productName" to "X",
                        "type" to "ADJUSMENT",
                        "quantityDelta" to "1",
                    ),
                ),
            ),
        )
        assertMalformed(
            change(
                movementSummary = listOf(
                    mapOf(
                        "productId" to PRODUCT_ID,
                        "productName" to "X",
                        "type" to "PURCHASE",
                        "quantityDelta" to "abc",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `resumen de movimientos conserva tipo PURCHASE signo documental y nunca cero`() {
        listOf("SALE", "ADJUSTMENT", "VOID").forEach { knownButImpossibleType ->
            assertMalformed(
                change(
                    movementSummary = listOf(
                        movement() + ("type" to knownButImpossibleType),
                    ),
                ),
            )
        }
        listOf("0", "0.0", "-0.00", "-1").forEach { invalidInvoiceQuantity ->
            assertMalformed(
                change(
                    movementSummary = listOf(
                        movement() + ("quantityDelta" to invalidInvoiceQuantity),
                    ),
                ),
            )
        }
        assertMalformed(
            change(
                movementSummary = listOf(movement()),
            ) + ("documentType" to "CREDIT_NOTE"),
        )

        val creditNote = SyncPullMappers.purchaseChange(
            change(
                movementSummary = listOf(movement() + ("quantityDelta" to "-2.5")),
            ) + mapOf(
                "status" to "VOIDED",
                "documentType" to "CREDIT_NOTE",
            ),
        )
        assertEquals(StockMovementType.PURCHASE, creditNote.movementSummary.single().type)
        assertEquals(BigDecimal("-2.5"), creditNote.movementSummary.single().quantityDelta)
    }

    @Test
    fun `nextCursor y hasMore ausentes o incoherentes son payload malformado`() {
        assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(
                mapOf("changes" to emptyList<Any>(), "hasMore" to false),
            )
        }.also { exception ->
            assertEquals(AccountError.Unexpected, exception.error)
        }
        assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(
                mapOf("changes" to emptyList<Any>(), "nextCursor" to -1L, "hasMore" to false),
            )
        }
        assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(
                mapOf("changes" to emptyList<Any>(), "nextCursor" to 0L),
            )
        }
        assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(
                mapOf("changes" to emptyList<Any>(), "nextCursor" to 0L, "hasMore" to true),
            )
        }
        assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(
                mapOf("changes" to listOf(change()), "nextCursor" to 9L, "hasMore" to false),
            )
        }
    }

    @Test
    fun `pagina cambios movimientos y descripcion exigen claves exactas`() {
        assertPageMalformed(
            page() + ("campoFuturo" to "no-permitido"),
        )
        assertPageMalformed(page().toMutableMap().apply { remove("hasMore") })
        assertMalformed(change() + ("campoFuturo" to true))
        assertMalformed(change().toMutableMap().apply { remove("currency") })
        assertMalformed(
            change(
                movementSummary = listOf(movement() + ("campoFuturo" to 1L)),
            ),
        )
        assertDescriptionMalformed(description() + ("lines" to emptyList<Any>()))
        assertDescriptionMalformed(description().toMutableMap().apply { remove("status") })
    }

    @Test
    fun `limites de pagina movimientos texto e importes se aplican antes de proyectar`() {
        assertPageMalformed(
            mapOf(
                "changes" to List(201) { change() },
                "nextCursor" to 1L,
                "hasMore" to false,
            ),
        )
        val maximumSummary = SyncPullMappers.purchaseChange(
            change(movementSummary = List(500) { movement() }),
        )
        assertEquals(500, maximumSummary.movementSummary.size)
        assertMalformed(change(movementSummary = List(501) { movement() }))
        assertMalformed(
            change(
                movementSummary = List(500) {
                    movement() + ("productName" to "Á".repeat(200))
                },
            ),
        )
        assertMalformed(change() + ("supplierLegalName" to "X".repeat(201)))
        assertMalformed(change() + ("documentSeries" to "F".repeat(21)))
        assertMalformed(change() + ("documentNumber" to "1".repeat(33)))
        assertMalformed(change() + ("totalMinorUnits" to 1_000_000_000_000_001L))
        assertMalformed(change() + ("totalMinorUnits" to -1L))
    }

    @Test
    fun `pagina exige secuencias estrictamente crecientes`() {
        val first = change() + ("seq" to 2L)
        val repeated = change() + ("seq" to 2L) + ("purchaseId" to SECOND_PURCHASE_ID)
        assertPageMalformed(
            mapOf("changes" to listOf(first, repeated), "nextCursor" to 2L, "hasMore" to false),
        )

        val descending = change() + ("seq" to 3L)
        assertPageMalformed(
            mapOf(
                "changes" to listOf(descending, repeated),
                "nextCursor" to 2L,
                "hasMore" to false,
            ),
        )
    }

    @Test
    fun `identidades moneda RUC tipo documental y timestamps son cerrados`() {
        assertMalformed(change(movementSummary = listOf(movement() + ("productId" to "producto"))))
        listOf("pen", "ZZZ", " PEN", "PEN ").forEach { currency ->
            assertMalformed(change() + ("currency" to currency))
        }
        listOf("2012345678", " 20123456789", "２０１２３４５６７８９").forEach { ruc ->
            assertMalformed(change() + ("supplierRuc" to ruc))
        }
        listOf("invoice", "UNKNOWN", "").forEach { type ->
            assertMalformed(change() + ("documentType" to type))
        }
        assertMalformed(change() + ("syncedAtMillis" to -1L))
        assertMalformed(change() + ("syncedAtMillis" to 9_007_199_254_740_992L))
    }

    @Test
    fun `decimal remoto replica exactamente la gramatica acotada de Functions`() {
        val maximum = "123456789012345678901.123456789012345678"
        val accepted = SyncPullMappers.purchaseChange(
            change(movementSummary = listOf(movement() + ("quantityDelta" to maximum))),
        )
        assertEquals(BigDecimal(maximum), accepted.movementSummary.single().quantityDelta)

        listOf(
            "1234567890123456789012",
            "1.1234567890123456789",
            "1E3",
            "+1",
            "01.",
            ".1",
        ).forEach { decimal ->
            assertMalformed(
                change(movementSummary = listOf(movement() + ("quantityDelta" to decimal))),
            )
        }
    }

    @Test
    fun `descripcion historica conserva receipt flexible pero limita identidad de SDK`() {
        val historical = SyncPullMappers.purchaseDescription(PURCHASE_ID, description())
        assertEquals("receipt-9", historical.receiptId)

        assertDescriptionMalformed(description() + ("receiptId" to "r".repeat(129)))
        assertDescriptionMalformed(description() + ("syncedBy" to "u".repeat(129)))
        assertDescriptionMalformed(description() + ("syncedAtMillis" to -1L))
    }

    @Test
    fun `fuzz determinista de formas hostiles siempre falla cerrado y es reproducible`() {
        val random = Random(FUZZ_SEED)
        repeat(FUZZ_CASES) { iteration ->
            val base = change()
            val mutation = random.nextInt(20)
            val hostilePage: Map<*, *> = when (mutation) {
                0 -> page() + ("unknown-$iteration" to iteration)
                1 -> page().toMutableMap().apply { remove("changes") }
                2 -> page() + ("changes" to "not-a-list")
                3 -> page() + ("nextCursor" to random.nextDouble())
                4 -> page(base + ("unknown-$iteration" to true))
                5 -> page(base.toMutableMap().apply { remove("purchaseId") })
                6 -> page(base + ("currency" to "ZZZ"))
                7 -> page(base + ("supplierRuc" to "9".repeat(random.nextInt(1, 11))))
                8 -> page(base + ("syncedAtMillis" to -random.nextLong(1, Long.MAX_VALUE)))
                9 -> page(base + ("totalMinorUnits" to 1_000_000_000_000_001L))
                10 -> page(
                    change(movementSummary = listOf(movement() + ("extra" to iteration))),
                )
                11 -> page(
                    change(
                        movementSummary = listOf(
                            movement() + ("productId" to "invalid-$iteration"),
                        ),
                    ),
                )
                12 -> page(
                    change(
                        movementSummary = listOf(movement() + ("quantityDelta" to "1E$iteration")),
                    ),
                )
                13 -> page(base + ("supplierLegalName" to "X".repeat(201 + random.nextInt(32))))
                14 -> mapOf(
                    "changes" to listOf(base, base + ("purchaseId" to SECOND_PURCHASE_ID)),
                    "nextCursor" to 1L,
                    "hasMore" to false,
                )
                15 -> page(base + ("syncedBy" to "uid-$iteration"))
                16 -> page(
                    change(movementSummary = listOf(movement() + ("type" to "VOID"))),
                )
                17 -> page(
                    change(movementSummary = listOf(movement() + ("quantityDelta" to "0.00"))),
                )
                18 -> page(
                    change(movementSummary = listOf(movement() + ("quantityDelta" to "-1"))),
                )
                else -> page(
                    change() + mapOf(
                        "documentType" to "CREDIT_NOTE",
                        "movementSummary" to listOf(movement() + ("quantityDelta" to "1")),
                    ),
                )
            }

            val failure = try {
                SyncPullMappers.pullPage(hostilePage)
                null
            } catch (caught: Throwable) {
                caught
            }
            assertTrue(
                "seed=$FUZZ_SEED case=$iteration mutation=$mutation no fallo cerrado",
                failure is AccountException && failure.error == AccountError.Unexpected,
            )
        }
    }

    private fun change(
        movementSummary: List<Map<String, Any?>> = listOf(
            mapOf(
                "productId" to PRODUCT_ID,
                "productName" to "Arroz",
                "type" to "PURCHASE",
                "quantityDelta" to "1",
            ),
        ),
    ): Map<String, Any?> = mapOf(
        "seq" to 1L,
        "purchaseId" to PURCHASE_ID,
        "status" to "POSTED",
        "documentType" to "INVOICE",
        "documentSeries" to "F001",
        "documentNumber" to "42",
        "issueDate" to "2026-08-15",
        "currency" to "PEN",
        "supplierRuc" to null,
        "supplierLegalName" to "Proveedor SAC",
        "totalMinorUnits" to 100L,
        "movementSummary" to movementSummary,
        "receiptId" to RECEIPT_ID,
        "syncedAtMillis" to null,
        "syncedBy" to null,
    )

    private fun movement(): Map<String, Any?> = mapOf(
        "productId" to PRODUCT_ID,
        "productName" to "Arroz",
        "type" to "PURCHASE",
        "quantityDelta" to "1",
    )

    private fun page(change: Map<String, Any?> = change()): Map<String, Any?> = mapOf(
        "changes" to listOf(change),
        "nextCursor" to change.getValue("seq"),
        "hasMore" to false,
    )

    private fun description(): Map<String, Any?> = mapOf(
        "status" to "VOIDED",
        "documentType" to "INVOICE",
        "documentSeries" to "F001",
        "documentNumber" to "42",
        "issueDate" to "2026-08-10",
        "currency" to "PEN",
        "supplierRuc" to null,
        "supplierLegalName" to "Proveedor SAC",
        "totalMinorUnits" to 500L,
        "receiptId" to "receipt-9",
        "syncedAtMillis" to null,
        "syncedBy" to null,
    )

    private fun assertMalformed(change: Map<String, Any?>) {
        val exception = assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(
                mapOf("changes" to listOf(change), "nextCursor" to 1L, "hasMore" to false),
            )
        }
        assertEquals(AccountError.Unexpected, exception.error)
    }

    private fun assertPageMalformed(page: Map<*, *>) {
        val exception = assertThrows(AccountException::class.java) {
            SyncPullMappers.pullPage(page)
        }
        assertEquals(AccountError.Unexpected, exception.error)
    }

    private fun assertDescriptionMalformed(description: Map<String, Any?>) {
        val exception = assertThrows(AccountException::class.java) {
            SyncPullMappers.purchaseDescription(PURCHASE_ID, description)
        }
        assertEquals(AccountError.Unexpected, exception.error)
    }

    private companion object {
        const val PURCHASE_ID = "10000000-0000-4000-8000-0000000000a1"
        const val SECOND_PURCHASE_ID = "10000000-0000-4000-8000-0000000000a2"
        const val PRODUCT_ID = "10000000-0000-4000-8000-0000000000b2"
        const val RECEIPT_ID = "rcpt_0123456789abcdef0123456789abcdef"
        const val FUZZ_SEED = 0x5EEDC0DE
        const val FUZZ_CASES = 2_000
    }
}
