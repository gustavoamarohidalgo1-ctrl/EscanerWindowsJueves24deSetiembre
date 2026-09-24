package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.BarcodeRecoveryDirection
import com.facturastock.app.domain.usecase.BarcodeRecoveryMatch
import com.facturastock.app.domain.usecase.findAutomaticBarcodeRecovery
import com.facturastock.app.domain.usecase.findSuspiciousExactBarcodeMatches
import com.facturastock.app.domain.usecase.requiresSuspiciousExactBarcodeReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SalesBarcodeRecoveryTest {
    @Test
    fun `exact review eligibility excludes valid gtins and inputs without any possible longer gtin`() {
        for (barcode in listOf("96385074", "036000291452", COMPLETE, "00036000291452", "", "12345", "ABC123456", "７51234500004", "99999999999999")) {
            assertFalse(barcode, requiresSuspiciousExactBarcodeReview(barcode))
        }
        for (barcode in listOf("385074", "751234500004", "51234500004", "7751234500005")) {
            assertTrue(barcode, requiresSuspiciousExactBarcodeReview(barcode))
        }
    }

    @Test
    fun `recovers one or two missing digits in the scan or the stored code`() =
        runTest {
            listOf("751234500004" to 1, "51234500004" to 2, "77123450004" to 2).forEach { (short, missing) ->
                val complete = product(1, COMPLETE)
                assertEquals(
                    BarcodeRecoveryMatch(complete.productId, COMPLETE, missing, BarcodeRecoveryDirection.SCANNED_CODE_INCOMPLETE),
                    findAutomaticBarcodeRecovery(short, BUSINESS_ID, listOf(complete)),
                )
                val incomplete = product(1, short)
                assertEquals(
                    BarcodeRecoveryMatch(incomplete.productId, short, missing, BarcodeRecoveryDirection.STORED_CODE_INCOMPLETE),
                    findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete)),
                )
            }
        }

    @Test
    fun `a valid gtin8 permits a six digit incomplete scan`() =
        runTest {
            val match = findAutomaticBarcodeRecovery("385074", BUSINESS_ID, listOf(product(1, "96385074")))
            assertEquals(2, match?.missingDigits)
            assertEquals(BarcodeRecoveryDirection.SCANNED_CODE_INCOMPLETE, match?.direction)
        }

    @Test
    fun `does not recover three omissions or an incomplete code shorter than six digits`() =
        runTest {
            assertNull(findAutomaticBarcodeRecovery("1234500004", BUSINESS_ID, listOf(product(1, COMPLETE))))
            assertNull(findAutomaticBarcodeRecovery("85074", BUSINESS_ID, listOf(product(1, "96385074"))))
            assertNull(findAutomaticBarcodeRecovery("96385074", BUSINESS_ID, listOf(product(1, "85074"))))
        }

    @Test
    fun `never chooses the closest when another product matches two or three omissions`() =
        runTest {
            val closest = product(1, "751234500004")
            listOf("51234500004", "1234500004").forEach { otherCode ->
                val competitor = product(2, otherCode)
                assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(closest, competitor)))
                assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(competitor, closest)))
            }
        }

    @Test
    fun `archived competitors count and archived sole candidates cannot be recovered`() =
        runTest {
            val active = product(1, "751234500004")
            val archived = product(2, "51234500004").copy(status = CatalogStatus.ARCHIVED)
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(active, archived)))
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(archived)))
            val duplicateArchived = active.copy(status = CatalogStatus.ARCHIVED)
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(duplicateArchived, active)))
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(active, duplicateArchived)))
        }

    @Test
    fun `invalid checksum and nonstandard length competitors still block automatic recovery`() =
        runTest {
            val scanned = "751234500004"
            val valid = product(1, COMPLETE)
            // Contains the entire scan but is too long to be a standard GTIN.
            val competitor = product(2, "999$scanned")
            assertNull(findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, listOf(valid, competitor)))
            assertNull(findAutomaticBarcodeRecovery("751234500005", BUSINESS_ID, listOf(product(1, "7751234500005"))))
        }

    @Test
    fun `valid gtins identifying different numbers are not treated as missing digits`() =
        runTest {
            // Both pass Mod10, but their canonical GTIN identities are different.
            val otherValidGtin = "775123450000"
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(product(1, otherValidGtin))))
            assertNull(findAutomaticBarcodeRecovery(otherValidGtin, BUSINESS_ID, listOf(product(1, COMPLETE))))
        }

    @Test
    fun `valid zero padded gtin representations recover the same identity in both directions`() =
        runTest {
            val forms = listOf("036000291452", "0036000291452", "00036000291452")
            forms.forEach { scanned ->
                forms.filter { it != scanned }.forEach { stored ->
                    val match = findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, listOf(product(1, stored)))
                    assertEquals(BarcodeRecoveryDirection.GTIN_EQUIVALENT, match?.direction)
                    assertEquals(stored, match?.barcode)
                }
            }
            assertEquals(
                BarcodeRecoveryDirection.GTIN_EQUIVALENT,
                findAutomaticBarcodeRecovery("00000096385074", BUSINESS_ID, listOf(product(1, "96385074")))?.direction,
            )
        }

    @Test
    fun `zero padding cannot choose between products sharing a canonical identity`() =
        runTest {
            val first = product(1, "036000291452")
            val second = product(2, "0036000291452")
            assertNull(findAutomaticBarcodeRecovery("00036000291452", BUSINESS_ID, listOf(first, second)))
            assertNull(
                findAutomaticBarcodeRecovery(
                    "00036000291452",
                    BUSINESS_ID,
                    listOf(first, second.copy(status = CatalogStatus.ARCHIVED)),
                ),
            )
        }

    @Test
    fun `a partial competitor also prevents zero padding from choosing a product`() =
        runTest {
            assertNull(
                findAutomaticBarcodeRecovery(
                    "0036000291452",
                    BUSINESS_ID,
                    listOf(product(1, "036000291452"), product(2, "36000291452")),
                ),
            )
        }

    @Test
    fun `exact barcode or sku claims take precedence even for archived products`() =
        runTest {
            val incomplete = product(1, "751234500004")
            val exact = product(2, COMPLETE).copy(status = CatalogStatus.ARCHIVED)
            val exactSku = product(3, null).copy(sku = COMPLETE)
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete, exact)))
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete, exactSku)))
        }

    @Test
    fun `other businesses and unrelated codes do not compete and duplicate rows are one product`() =
        runTest {
            val incomplete = product(1, "751234500004")
            val foreign = product(2, COMPLETE).copy(businessId = BusinessId.from(UUID(0L, 2L)))
            val unrelated = product(3, "1234567890128")
            assertEquals(
                incomplete.productId,
                findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(foreign, incomplete, unrelated, incomplete))?.productId,
            )
        }

    @Test
    fun `rejects non ASCII digits substitutions transpositions and unrecognized codes`() =
        runTest {
            val catalog = listOf(product(1, COMPLETE))
            for (scanned in listOf("", " 751234500004", "７51234500004", "75123450000A", "751243500004", "751234500005", "9999999999999")) {
                assertNull(scanned, findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, catalog))
            }
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, emptyList()))
        }

    @Test
    fun `one substituted digit cannot hide a same length competing identity`() =
        runTest {
            val scanned = "751234500004"
            val complete = product(1, COMPLETE)
            val competitor = product(2, "751234500001")
            assertEquals(complete.productId, findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, listOf(complete))?.productId)
            listOf(CatalogStatus.ACTIVE, CatalogStatus.ARCHIVED).forEach { status ->
                val other = competitor.copy(status = status)
                assertNull(findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, listOf(complete, other)))
                assertNull(findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, listOf(other, complete)))
            }
        }

    @Test
    fun `an adjacent swap vetoes recovery even when both complete gtins pass their checksum`() =
        runTest {
            val incomplete = product(1, "751234500004")
            val competitor = product(2, "7751234050004")
            assertEquals(incomplete.productId, findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete))?.productId)
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete, competitor)))
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(competitor, incomplete)))
        }

    @Test
    fun `numeric sku competitors veto both substitutions and adjacent swaps without a barcode`() =
        runTest {
            val complete = product(1, COMPLETE)
            val incomplete = product(2, "751234500004")
            val changedSku = product(3, null).copy(sku = "751234500001", status = CatalogStatus.ARCHIVED)
            val swappedSku = product(4, null).copy(sku = "7751234050004")
            assertNull(findAutomaticBarcodeRecovery("751234500004", BUSINESS_ID, listOf(complete, changedSku)))
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete, swappedSku)))
        }

    @Test
    fun `sku omissions veto other identities in both directions through three missing digits`() =
        runTest {
            val scannedShort = "751234500004"
            val complete = product(1, COMPLETE)
            val incomplete = product(2, scannedShort)
            listOf(CatalogStatus.ACTIVE, CatalogStatus.ARCHIVED).forEach { status ->
                (1..3).forEach { missing ->
                    val competitor = product(3, null).copy(sku = "9".repeat(missing) + scannedShort, status = status)
                    assertNull(findAutomaticBarcodeRecovery(scannedShort, BUSINESS_ID, listOf(complete, competitor)))
                    assertNull(findAutomaticBarcodeRecovery(scannedShort, BUSINESS_ID, listOf(competitor, complete)))
                }
                listOf(scannedShort, "51234500004", "1234500004").forEach { sku ->
                    val competitor = product(3, null).copy(sku = sku, status = status)
                    assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete, competitor)))
                    assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(competitor, incomplete)))
                }
            }
        }

    @Test
    fun `a canonical gtin sku competitor vetoes even more than three padding zeros`() =
        runTest {
            val complete = product(1, "96385074")
            val competitor = product(2, null).copy(sku = "000096385074")
            assertNull(findAutomaticBarcodeRecovery("00000096385074", BUSINESS_ID, listOf(complete, competitor)))
            assertNull(findAutomaticBarcodeRecovery("96385074", BUSINESS_ID, listOf(product(3, "00000096385074"), competitor)))
        }

    @Test
    fun `compatible sku belongs to the winner or another business without inventing ambiguity`() =
        runTest {
            val scanned = "751234500004"
            val complete = product(1, COMPLETE).copy(sku = "9$scanned")
            val foreign = product(2, null).copy(sku = "99$scanned", businessId = BusinessId.from(UUID(0L, 2L)))
            assertEquals(
                complete.productId,
                findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, listOf(complete, foreign))?.productId,
            )
            val canonical = product(3, "96385074").copy(sku = "000096385074")
            assertEquals(
                canonical.productId,
                findAutomaticBarcodeRecovery("00000096385074", BUSINESS_ID, listOf(canonical))?.productId,
            )
        }

    @Test
    fun `compatible sku alone never becomes a recovery candidate`() =
        runTest {
            assertNull(findAutomaticBarcodeRecovery("751234500004", BUSINESS_ID, listOf(product(1, null).copy(sku = COMPLETE))))
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(product(1, null).copy(sku = "751234500004"))))
            assertNull(findAutomaticBarcodeRecovery("00000096385074", BUSINESS_ID, listOf(product(1, null).copy(sku = "96385074"))))
        }

    @Test
    fun `the selected product own sku and repeated rows do not manufacture competitors`() =
        runTest {
            val complete = product(1, COMPLETE).copy(sku = "751234500001")
            assertEquals(
                complete.productId,
                findAutomaticBarcodeRecovery("751234500004", BUSINESS_ID, listOf(complete, complete))?.productId,
            )
            val incomplete = product(2, "751234500004").copy(sku = "7751234050004")
            assertEquals(
                incomplete.productId,
                findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(incomplete, incomplete))?.productId,
            )
            val other = product(3, null).copy(sku = "751234500002")
            assertNull(findAutomaticBarcodeRecovery("751234500004", BUSINESS_ID, listOf(complete, other)))
            assertNull(findAutomaticBarcodeRecovery("751234500004", BUSINESS_ID, listOf(other, complete)))
        }

    @Test
    fun `same length veto stays within its business numeric alphabet and supported errors`() =
        runTest {
            val complete = product(1, COMPLETE)
            val foreign = product(2, "751234500001").copy(businessId = BusinessId.from(UUID(0L, 2L)))
            val alphanumeric = product(3, "75123450000A")
            val twoChanges = product(4, "751234500015")
            assertEquals(
                complete.productId,
                findAutomaticBarcodeRecovery("751234500004", BUSINESS_ID, listOf(foreign, alphanumeric, twoChanges, complete))?.productId,
            )
            assertNull(findAutomaticBarcodeRecovery("751234500004", BUSINESS_ID, listOf(product(5, "751234500001"))))
            assertNull(findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, listOf(product(6, "7751234050004"))))
        }

    @Test
    fun `equal length competitors also prevent zero padding recovery`() =
        runTest {
            val equivalent = product(1, "036000291452")
            val competitor = product(2, "0036000291453")
            assertNull(findAutomaticBarcodeRecovery("0036000291452", BUSINESS_ID, listOf(equivalent, competitor)))
        }

    @Test
    fun `every single substitution and adjacent swap of known fixtures avoids selecting another identity`() =
        runTest {
            val catalog =
                listOf(COMPLETE, "751234500001", "7751234050004", "751234500004", "96385074", "385074")
                    .mapIndexed { index, barcode -> product(index.toLong() + 1L, barcode) }
            catalog.forEach { origin ->
                val code = requireNotNull(origin.barcode)
                val corruptions = mutableSetOf<String>()
                code.indices.forEach { index ->
                    ('0'..'9').filter { it != code[index] }.forEach { digit ->
                        corruptions += code.replaceRange(index, index + 1, digit.toString())
                    }
                }
                (0 until code.lastIndex).filter { code[it] != code[it + 1] }.forEach { index ->
                    corruptions += code.replaceRange(index, index + 2, "${code[index + 1]}${code[index]}")
                }
                corruptions.forEach { scanned ->
                    val match = findAutomaticBarcodeRecovery(scanned, BUSINESS_ID, catalog)
                    assertTrue("A supported corruption must not select another identity", match == null || match.productId == origin.productId)
                }
            }
        }

    @Test
    fun `suspicious exact numeric codes return complete competitors without choosing one`() =
        runTest {
            listOf("751234500004", "51234500004").forEach { scanned ->
                val exact = product(1, scanned)
                val complete = product(2, COMPLETE)
                assertEquals(
                    listOf(complete),
                    findSuspiciousExactBarcodeMatches(scanned, BUSINESS_ID, exact.productId, listOf(exact, complete)),
                )
            }
            val exact = product(1, "385074")
            val complete = product(2, "96385074")
            assertEquals(
                listOf(complete),
                findSuspiciousExactBarcodeMatches("385074", BUSINESS_ID, exact.productId, listOf(complete, exact)),
            )
        }

    @Test
    fun `suspicious exact detection includes archived competitors and deduplicates only their identity`() =
        runTest {
            val exact = product(1, "751234500004")
            val first = product(2, COMPLETE)
            val second = product(3, COMPLETE).copy(status = CatalogStatus.ARCHIVED)
            assertEquals(
                listOf(first, second),
                findSuspiciousExactBarcodeMatches(
                    "751234500004",
                    BUSINESS_ID,
                    exact.productId,
                    listOf(exact, first, second, first),
                ),
            )
        }

    @Test
    fun `valid exact gtins keep their identity even when a longer valid gtin contains them`() =
        runTest {
            listOf("775123450000", "036000291452").forEach { scanned ->
                val exact = product(1, scanned)
                val longer = product(2, if (scanned.startsWith("775")) COMPLETE else "0036000291452")
                assertTrue(findSuspiciousExactBarcodeMatches(scanned, BUSINESS_ID, exact.productId, listOf(exact, longer)).isEmpty())
            }
        }

    @Test
    fun `custom exact codes stay exact without a distinct valid complete barcode competitor`() =
        runTest {
            val scanned = "751234500004"
            val exact = product(1, scanned)
            val invalid = product(2, "7751234500005")
            val foreign = product(3, COMPLETE).copy(businessId = BusinessId.from(UUID(0L, 2L)))
            val skuOnly = product(4, null).copy(sku = COMPLETE)
            val sameIdentity = exact.copy(barcode = COMPLETE)
            assertTrue(
                findSuspiciousExactBarcodeMatches(
                    scanned,
                    BUSINESS_ID,
                    exact.productId,
                    listOf(exact, invalid, foreign, skuOnly, sameIdentity),
                ).isEmpty(),
            )
            for (unsupported in listOf("", " 751234500004", "７51234500004", "75123450000A", "1234500004", "85074")) {
                assertTrue(
                    unsupported,
                    findSuspiciousExactBarcodeMatches(unsupported, BUSINESS_ID, exact.productId, listOf(product(2, COMPLETE))).isEmpty(),
                )
            }
        }

    @Test
    fun `suspicious exact traversal never returns a partial catalog after cancellation`() =
        runTest {
            val job = Job()
            val exactId = product(1, "751234500004").productId
            val catalog =
                object : AbstractList<Product>() {
                    override val size = 256

                    override fun get(index: Int): Product {
                        if (index == 80) job.cancel()
                        return if (index == 0) product(2, COMPLETE) else product(index.toLong() + 3L, null)
                    }
                }
            var cancelled = false
            try {
                withContext(job) { findSuspiciousExactBarcodeMatches("751234500004", BUSINESS_ID, exactId, catalog) }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

    @Test
    fun `catalog traversal remains cancellable without returning a partial winner`() =
        runTest {
            val job = Job()
            val catalog =
                object : AbstractList<Product>() {
                    override val size = 256

                    override fun get(index: Int): Product {
                        if (index == 80) job.cancel()
                        return if (index == 0) product(1, "751234500004") else product(index.toLong() + 1L, null)
                    }
                }
            var cancelled = false
            try {
                withContext(job) { findAutomaticBarcodeRecovery(COMPLETE, BUSINESS_ID, catalog) }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

    private fun product(
        id: Long,
        barcode: String?,
    ): Product =
        Product(
            productId = ProductId.from(UUID(0L, id)),
            businessId = BUSINESS_ID,
            unitId = UnitId.from(UUID(0L, 1L)),
            name = "Producto $id",
            barcode = barcode,
            createdAt = Instant.parse("2026-09-19T12:00:00Z"),
            updatedAt = Instant.parse("2026-09-19T12:00:00Z"),
        )

    private companion object {
        const val COMPLETE = "7751234500004"
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 1L))
    }
}
