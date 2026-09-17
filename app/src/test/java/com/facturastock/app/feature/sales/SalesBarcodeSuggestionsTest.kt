package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.BarcodeSimilarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class SalesBarcodeSuggestionsTest {
    @Test
    fun `prioriza una dos y tres omisiones aunque el orden alfabetico indique lo contrario`() =
        runTest {
            val oneMissing = product(1, "912345678")
            val twoMissing = product(2, "0012345678")
            val threeMissing = product(3, "00012345678")
            val products = listOf(threeMissing, twoMissing, oneMissing)

            val matches =
                findBarcodeSuggestionMatches(
                    scannedBarcode = SCANNED_BARCODE,
                    businessId = BUSINESS_ID,
                    products = products,
                    availableProductIds = products.mapTo(mutableSetOf()) { it.productId },
                )

            assertEquals(
                listOf(oneMissing.productId, twoMissing.productId, threeMissing.productId),
                matches.map { it.productId },
            )
            assertEquals(listOf(1, 2, 3), matches.map { it.missingDigits })
            assertEquals(
                listOf(oneMissing.barcode, twoMissing.barcode, threeMissing.barcode),
                matches.map { it.barcode },
            )
        }

    @Test
    fun `limita a cinco productos y conserva mejores candidatos encontrados al final`() =
        runTest {
            val products =
                listOf(
                    product(1, "00012345678"),
                    product(2, "0012345678"),
                    product(3, "512345678"),
                    product(4, "412345678"),
                    product(5, "312345678"),
                    product(6, "212345678"),
                    product(7, "112345678"),
                    product(8, "012345678"),
                )

            val matches =
                findBarcodeSuggestionMatches(
                    scannedBarcode = SCANNED_BARCODE,
                    businessId = BUSINESS_ID,
                    products = products,
                    availableProductIds = products.mapTo(mutableSetOf()) { it.productId },
                )

            assertEquals(5, matches.size)
            assertEquals(listOf(8L, 7L, 6L, 5L, 4L).map(::productId), matches.map { it.productId })
            assertEquals(listOf(1, 1, 1, 1, 1), matches.map { it.missingDigits })
        }

    @Test
    fun `desempata por codigo y luego identificador sin depender del orden del catalogo`() =
        runTest {
            val products = (1L..6L).map { product(it, "912345678") } + product(7, "012345678")
            val expectedIds = listOf(7L, 1L, 2L, 3L, 4L).map(::productId)
            val availableIds = products.mapTo(mutableSetOf()) { it.productId }
            val catalogOrders =
                listOf(
                    products,
                    products.reversed(),
                    products.drop(3) + products.take(3),
                )

            catalogOrders.forEach { catalog ->
                val matches =
                    findBarcodeSuggestionMatches(
                        scannedBarcode = SCANNED_BARCODE,
                        businessId = BUSINESS_ID,
                        products = catalog,
                        availableProductIds = availableIds,
                    )

                assertEquals(expectedIds, matches.map { it.productId })
            }
        }

    @Test
    fun `excluye otro negocio archivados y productos sin opcion de venta disponible`() =
        runTest {
            val eligible = product(1, "912345678")
            val otherBusiness = product(2, "012345678").copy(businessId = BusinessId.from(UUID(0L, 2L)))
            val archived = product(3, "112345678").copy(status = CatalogStatus.ARCHIVED)
            val unavailable = product(4, "212345678")
            val products = listOf(otherBusiness, archived, unavailable, eligible)

            val matches =
                findBarcodeSuggestionMatches(
                    scannedBarcode = SCANNED_BARCODE,
                    businessId = BUSINESS_ID,
                    products = products,
                    availableProductIds = setOf(eligible.productId, otherBusiness.productId, archived.productId),
                )

            assertEquals(listOf(BarcodeSuggestionMatch(eligible.productId, "912345678", 1)), matches)
        }

    @Test
    fun `no sugiere coincidencias exactas codigos ausentes ni codigos ajenos`() =
        runTest {
            val near = product(1, "012345678")
            val exact = product(2, SCANNED_BARCODE)
            val missingBarcode = product(3, null).copy(sku = "912345678", name = SCANNED_BARCODE)
            val unrelated = product(4, "987654321")
            val products = listOf(exact, missingBarcode, unrelated, near)

            val matches =
                findBarcodeSuggestionMatches(
                    scannedBarcode = SCANNED_BARCODE,
                    businessId = BUSINESS_ID,
                    products = products,
                    availableProductIds = products.mapTo(mutableSetOf()) { it.productId },
                )

            assertEquals(listOf(BarcodeSuggestionMatch(near.productId, "012345678", 1)), matches)
        }

    @Test
    fun `bounded insertion matches full stable sorting across shuffled large catalogs`() =
        runTest {
            val random = Random(713)
            val catalog =
                (1L..2_000L).map { id ->
                    val prefix = (id % 1_000).toString().padStart((id % 3 + 1).toInt(), '0')
                    product(id, "$prefix$SCANNED_BARCODE")
                }
            val available = catalog.filterIndexed { index, _ -> index % 4 != 0 }.mapTo(mutableSetOf()) { it.productId }
            repeat(12) {
                val products = catalog.shuffled(random)
                val expected =
                    products
                        .filter { it.productId in available }
                        .mapNotNull { product ->
                            product.barcode?.let { barcode ->
                                BarcodeSimilarity.missingDigits(SCANNED_BARCODE, barcode)?.let { missing ->
                                    BarcodeSuggestionMatch(product.productId, barcode, missing)
                                }
                            }
                        }.sortedWith(compareBy<BarcodeSuggestionMatch> { it.missingDigits }.thenBy { it.barcode }.thenBy { it.productId.value })
                        .take(5)
                assertEquals(expected, findBarcodeSuggestionMatches(SCANNED_BARCODE, BUSINESS_ID, products, available))
            }
        }

    @Test
    fun `an impossible scan does not traverse the catalog`() =
        runTest {
            val untraversable =
                object : AbstractCollection<Product>() {
                    override val size = 10_000

                    override fun iterator(): Iterator<Product> = error("An invalid scan must not traverse products")
                }
            for (scan in listOf("ABCD12345", "1234", "1".repeat(129))) {
                assertTrue(findBarcodeSuggestionMatches(scan, BUSINESS_ID, untraversable, emptySet()).isEmpty())
            }
        }

    @Test
    fun `catalog scan remains cancellable without delivering partial suggestions`() =
        runTest {
            val job = Job()
            val catalog =
                object : AbstractList<Product>() {
                    override val size = 256

                    override fun get(index: Int): Product {
                        if (index == 80) job.cancel()
                        return product(index.toLong() + 1L, "0$SCANNED_BARCODE")
                    }
                }
            var cancelled = false
            try {
                withContext(job) {
                    findBarcodeSuggestionMatches(SCANNED_BARCODE, BUSINESS_ID, catalog, (1L..256L).mapTo(mutableSetOf(), ::productId))
                }
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
            productId = productId(id),
            businessId = BUSINESS_ID,
            unitId = UNIT_ID,
            name = "Producto $id",
            barcode = barcode,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun productId(id: Long): ProductId = ProductId.from(UUID(0L, id))

    private companion object {
        const val SCANNED_BARCODE = "12345678"
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 1L))
        val UNIT_ID: UnitId = UnitId.from(UUID(0L, 1L))
        val NOW: Instant = Instant.parse("2026-09-08T12:00:00Z")
    }
}
