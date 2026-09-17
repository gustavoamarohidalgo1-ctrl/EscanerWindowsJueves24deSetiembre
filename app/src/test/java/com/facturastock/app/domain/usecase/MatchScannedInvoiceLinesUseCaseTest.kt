package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.MatchStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MatchScannedInvoiceLinesUseCaseTest {
    private val clock = AppClock { NOW }
    private lateinit var configuration: FakeAppConfigurationRepository
    private lateinit var products: FakeProductRepository
    private lateinit var aliases: FakeSupplierProductAliasRepository
    private lateinit var drafts: FakeInvoiceDraftRepository
    private lateinit var useCase: MatchScannedInvoiceLinesUseCase

    @Before
    fun setUp() {
        configuration = FakeAppConfigurationRepository()
        products = FakeProductRepository(clock)
        aliases = FakeSupplierProductAliasRepository(clock)
        drafts = FakeInvoiceDraftRepository(clock)
        useCase =
            MatchScannedInvoiceLinesUseCase(
                appConfigurationRepository = configuration,
                invoiceDraftRepository = drafts,
                productMatchingUseCase = ProductMatchingUseCase(products, aliases),
            )
    }

    @Test
    fun `barcode exacto auto vincula y no adivina un nombre ambiguo`() =
        runTest {
            configuration.completeOnboarding(
                BUSINESS_ID,
                AppConfiguration.DEFAULT_TAX_RATE,
                CostPolicy.NET,
            )
            val canned = seedProduct(10, name = "Atun en lata", sku = "7750001")
            seedProduct(11, name = "Arroz extra")
            seedProduct(12, name = "Arroz extra")
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    status = DraftStatus.NEEDS_REVIEW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            drafts.replaceLines(
                DRAFT_ID,
                listOf(
                    line(1, description = "Atun en lata", code = "7750001"),
                    line(2, description = "Arroz extra"),
                    line(3, description = "Arroz extra"),
                ),
            )

            val matches = useCase(DRAFT_ID)

            assertEquals(3, matches.size)
            assertEquals(MatchStatus.AUTO_LINKED, matches[0].status)
            assertEquals(canned.productId, matches[0].matchedProduct?.productId)
            assertEquals("SKU", matches[0].matchReasonLabel)
            assertEquals("7750001", matches[0].printedCode)
            assertNull(matches[0].barcode)
            assertEquals(MatchStatus.UNMATCHED, matches[1].status)
            assertNull(matches[1].matchedProduct)
            assertEquals(2, matches[1].suggestedProducts.size)
            assertEquals(matches[1].suggestedProducts.map { it.productId }, matches[2].suggestedProducts.map { it.productId })
        }

    @Test
    fun `el codigo impreso no se trata como codigo de barras`() =
        runTest {
            configuration.completeOnboarding(
                BUSINESS_ID,
                AppConfiguration.DEFAULT_TAX_RATE,
                CostPolicy.NET,
            )
            seedProduct(10, name = "Atun en lata", barcode = "7750001")
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    status = DraftStatus.NEEDS_REVIEW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            drafts.replaceLines(
                DRAFT_ID,
                listOf(line(1, description = "Azucar rubia", code = "7750001")),
            )

            val matches = useCase(DRAFT_ID)

            assertEquals(1, matches.size)
            assertEquals(MatchStatus.UNMATCHED, matches[0].status)
            assertNull(matches[0].matchedProduct)
            assertEquals("7750001", matches[0].printedCode)
            assertNull(matches[0].barcode)
        }

    @Test
    fun `una linea sin cantidad no inventa una unidad`() =
        runTest {
            configuration.completeOnboarding(
                BUSINESS_ID,
                AppConfiguration.DEFAULT_TAX_RATE,
                CostPolicy.NET,
            )
            seedProduct(10, name = "Atun en lata")
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    status = DraftStatus.NEEDS_REVIEW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            drafts.replaceLines(
                DRAFT_ID,
                listOf(line(1, description = "Atun en lata", withQuantity = false)),
            )

            val matches = useCase(DRAFT_ID)

            assertNull(matches.single().quantity)
            assertEquals(MatchStatus.AUTO_LINKED, matches.single().status)
        }

    @Test
    fun `preserva identidad unidad y moneda de la factura sin usar la moneda configurada`() =
        runTest {
            configuration.completeOnboarding(BUSINESS_ID, AppConfiguration.DEFAULT_TAX_RATE, CostPolicy.NET)
            drafts.createDraft(InvoiceDraft(draftId = DRAFT_ID, businessId = BUSINESS_ID, status = DraftStatus.NEEDS_REVIEW, createdAt = NOW, updatedAt = NOW))
            val source = line(1, "Arroz").copy(unitCodeNormalized = "CJA", unitCost = UnitCost.of("120", CurrencyCode.of("USD")))
            drafts.replaceLines(DRAFT_ID, listOf(source))
            val matched = useCase(DRAFT_ID).single()
            assertEquals(source.lineId, matched.sourceLineId)
            assertEquals("CJA", matched.sourceUnitCode)
            assertEquals(CurrencyCode.of("USD"), matched.sourceCurrency)
            assertEquals(source.unitCost!!.amount, matched.unitCost)
        }

    @Test
    fun `sin negocio activo no consulta el catálogo`() =
        runTest {
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    status = DraftStatus.NEEDS_REVIEW,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            drafts.replaceLines(DRAFT_ID, listOf(line(1, description = "Atun")))

            val matches = useCase(DRAFT_ID)

            assertTrue(matches.isEmpty())
        }

    private suspend fun seedProduct(
        seed: Int,
        name: String,
        barcode: String? = null,
        sku: String? = null,
    ): Product =
        products.create(
            Product(
                productId = ProductId.from(uuid(seed)),
                businessId = BUSINESS_ID,
                unitId = UNIT_ID,
                name = name,
                sku = sku,
                barcode = barcode,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun line(
        seed: Int,
        description: String,
        code: String? = null,
        withQuantity: Boolean = true,
    ) = InvoiceLine(
        lineId = LineId.from(uuid(seed + 100)),
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        position = seed - 1,
        descriptionRaw = description,
        codeRaw = code,
        quantity = if (withQuantity) Quantity.of("1") else null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-03T12:00:00Z")
        val BUSINESS_ID = BusinessId.from(uuid(1))
        val UNIT_ID = UnitId.from(uuid(2))
        val DRAFT_ID = DraftId.from(uuid(3))

        fun uuid(seed: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
