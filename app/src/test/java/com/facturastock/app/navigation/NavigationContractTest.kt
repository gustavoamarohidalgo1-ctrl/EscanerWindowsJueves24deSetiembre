package com.facturastock.app.navigation

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.feature.sales.SalesContract
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationContractTest {
    private val draftUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    private val captureUuid = UUID.fromString("223e4567-e89b-12d3-a456-426614174001")
    private val lineUuid = UUID.fromString("323e4567-e89b-12d3-a456-426614174002")
    private val purchaseUuid = UUID.fromString("423e4567-e89b-12d3-a456-426614174003")
    private val productUuid = UUID.fromString("523e4567-e89b-12d3-a456-426614174004")
    private val debtUuid = UUID.fromString("623e4567-e89b-12d3-a456-426614174005")

    @Test
    fun registryContainsThreeCommercialTopLevelsAndLegacyAliases() {
        assertEquals(32, AppRoutes.all.size)
        assertEquals(32, AppRoutes.all.map { it.pattern }.distinct().size)
        assertEquals(
            setOf(
                AppRoutes.SALES,
                AppRoutes.INVENTORY,
                AppRoutes.REPORTS,
            ),
            AppRoutes.topLevel.map { it.pattern }.toSet(),
        )
        assertEquals(3, AppRoutes.topLevel.size)
        assertEquals(
            AppRoutes.topLevel.map { it.pattern },
            TopLevelDestination.entries.map { it.route },
        )
        assertFalse(AppRoutes.HOME in AppRoutes.topLevel.map { it.pattern })
        assertFalse(AppRoutes.INVOICES in AppRoutes.topLevel.map { it.pattern })
        assertFalse(AppRoutes.PRODUCTS in AppRoutes.topLevel.map { it.pattern })
        assertFalse(AppRoutes.PURCHASES in AppRoutes.topLevel.map { it.pattern })
        // Ajustes se retiró: ninguna ruta, ni principal ni secundaria, debe volver a exponerla.
        assertFalse("settings" in AppRoutes.all.map { it.pattern })
        assertTrue(AppRoutes.SYNC in AppRoutes.all.map { it.pattern })
    }

    @Test
    fun preparedSummaryClassifiesOnlyMutableDraftDestinationsAsEditableHistory() {
        assertEquals(
            setOf(
                AppRoutes.PURCHASE_SOURCE,
                AppRoutes.CAMERA,
                AppRoutes.IMAGE_PREVIEW,
                AppRoutes.PROCESSING,
                AppRoutes.INVOICE_HEADER,
                AppRoutes.INVOICE_LINES,
                AppRoutes.INVOICE_MATCHING,
                AppRoutes.PRODUCT_LINKING,
            ),
            AppRoutes.editableDraftPatterns,
        )
        assertFalse(AppRoutes.PURCHASE_SUMMARY in AppRoutes.editableDraftPatterns)
        assertFalse(AppRoutes.PURCHASE_CONFIRMATION in AppRoutes.editableDraftPatterns)
    }

    @Test
    fun routeMetadataMatchesEveryPlaceholderAndCarriesOnlyIdentityRevisionOrExplicitEntryTokens() {
        val placeholder = Regex("""\{([^}]+)\}""")
        AppRoutes.all.forEach { definition ->
            assertEquals(
                placeholder.findAll(definition.pattern)
                    .map { it.groupValues[1] }
                    .toSet(),
                definition.argumentNames,
            )
            assertTrue(
                definition.argumentNames.all { argument ->
                    argument.endsWith("Id") ||
                        argument == AppRoutes.EXPECTED_PREPARED_HASH ||
                        argument == AppRoutes.SCAN_RETAKE ||
                        argument == AppRoutes.PREFILL_BARCODE ||
                        (definition.pattern == AppRoutes.PRODUCTS_PATTERN &&
                            argument in setOf(AppRoutes.SPECIAL_PRODUCT, AppRoutes.MANUAL_PRODUCT))
                },
            )
            // El origen especial solo existe en Productos; no transporta datos de stock.
            // Las otras consultas conservan códigos, identidades o el reintento de captura.
            if ('?' in definition.pattern) {
                assertTrue(
                    definition.pattern == AppRoutes.PURCHASE_SOURCE ||
                        definition.pattern == AppRoutes.CAMERA ||
                        definition.pattern == AppRoutes.PRODUCTS_PATTERN ||
                        definition.pattern == AppRoutes.SALES_PRODUCT_REGISTRATION,
                )
            }
            assertFalse(definition.pattern.contains('#'))
        }
    }

    @Test
    fun salesRegistrationResultDistinguishesCancellationFromAValidatedSavedIdentity() {
        val businessId = BusinessId.from(draftUuid)
        val productId = ProductId.from(productUuid)
        assertEquals(
            SalesContract.ProductRegistrationResult("request-1"),
            salesRegistrationResultFromFields(listOf("request-1", "", "")),
        )
        assertEquals(
            SalesContract.ProductRegistrationResult("request-1", productId, businessId),
            salesRegistrationResultFromFields(listOf("request-1", productId.value, businessId.value)),
        )
        listOf(
            emptyList(),
            listOf("", productId.value, businessId.value),
            listOf("request-1", productId.value, ""),
            listOf("request-1", "", businessId.value),
            listOf("request-1", "invalid", businessId.value),
            listOf("request-1", productId.value, "invalid"),
            listOf("request-1", productId.value, businessId.value, "extra"),
        ).forEach { assertNull(salesRegistrationResultFromFields(it)) }
        assertNull(salesRegistrationResultFromFields(null))
    }

    @Test
    fun buildersProduceCanonicalTypedRoutes() {
        val draftId = DraftId.from(draftUuid)
        val captureId = CaptureId.from(captureUuid)
        val lineId = LineId.from(lineUuid)
        val purchaseId = PurchaseId.from(purchaseUuid)
        val productId = ProductId.from(productUuid)
        val debtId = DebtId.from(debtUuid)

        assertEquals(
            "products?editProductId=${productUuid}",
            AppRoutes.editInventoryProduct(productId),
        )
        assertEquals("products?specialProduct=true", AppRoutes.specialProductRegistration())
        assertEquals("products?manualProduct=true", AppRoutes.manualProductRegistration())
        assertEquals(
            listOf(AppRoutes.PRODUCTS_PATTERN),
            AppRoutes.all.filter { AppRoutes.MANUAL_PRODUCT in it.argumentNames }.map { it.pattern },
        )
        assertEquals(
            listOf(AppRoutes.PRODUCTS_PATTERN),
            AppRoutes.all.filter { AppRoutes.SPECIAL_PRODUCT in it.argumentNames }.map { it.pattern },
        )

        assertEquals(
            "purchase/draft/${draftUuid}/source",
            AppRoutes.source(draftId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/camera",
            AppRoutes.camera(draftId),
        )
        // "Repetir" transporta el ID tipado de la página a reemplazar, nunca la página.
        val replaceId = ImageId.from(captureUuid)
        assertEquals(
            "purchase/draft/${draftUuid}/source?replace=${captureUuid}",
            AppRoutes.source(draftId, replaceId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/camera?replace=${captureUuid}",
            AppRoutes.camera(draftId, replaceId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/camera?scanRetake=true",
            AppRoutes.invoiceScanRetakeCamera(draftId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/preview/${captureUuid}",
            AppRoutes.imagePreview(draftId, captureId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/processing",
            AppRoutes.processing(draftId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/header",
            AppRoutes.invoiceHeader(draftId),
        )
        assertEquals(
            AppRoutes.invoiceHeader(draftId),
            AppRoutes.manualInvoiceReview(draftId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/lines",
            AppRoutes.invoiceLines(draftId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/linking/${lineUuid}",
            AppRoutes.productLinking(draftId, lineId),
        )
        assertEquals(
            "purchase/draft/${draftUuid}/summary",
            AppRoutes.purchaseSummary(draftId),
        )
        assertEquals(
            "purchase/draft/$draftUuid/confirmation/" + "a".repeat(64),
            AppRoutes.purchaseConfirmation(draftId, "a".repeat(64)),
        )
        assertEquals(
            "purchase/success/${purchaseUuid}",
            AppRoutes.purchaseSuccess(purchaseId),
        )
        assertEquals(
            "purchases/${purchaseUuid}",
            AppRoutes.purchaseDetail(purchaseId),
        )
        assertEquals(
            "purchases/${purchaseUuid}/void",
            AppRoutes.purchaseVoid(purchaseId),
        )
        assertEquals(
            "inventory/${productUuid}",
            AppRoutes.inventoryDetail(productId),
        )
        assertEquals(
            "debtors/${debtUuid}",
            AppRoutes.debtDetail(debtId),
        )
    }

    @Test
    fun identifiersRejectNonCanonicalAndNilValues() {
        val canonical = draftUuid.toString()

        assertEquals(canonical, DraftId.parse(canonical)?.value)
        assertEquals(canonical, CaptureId.parse(canonical)?.value)
        assertEquals(canonical, LineId.parse(canonical)?.value)
        assertEquals(canonical, PurchaseId.parse(canonical)?.value)
        assertEquals(canonical, ProductId.parse(canonical)?.value)
        assertEquals(canonical, DebtId.parse(canonical)?.value)
        assertEquals(canonical, ImageId.parse(canonical)?.value)

        listOf(
            null,
            "",
            "1-1-1-1-1",
            canonical.uppercase(),
            " $canonical",
            "$canonical ",
            canonical.replace("-", ""),
            "00000000-0000-0000-0000-000000000000",
            "$canonical/extra",
            "$canonical?query=true",
            "$canonical%2Fextra",
        ).forEach { invalid ->
            assertNull(DraftId.parse(invalid))
            assertNull(CaptureId.parse(invalid))
            assertNull(LineId.parse(invalid))
            assertNull(PurchaseId.parse(invalid))
            assertNull(ProductId.parse(invalid))
            assertNull(DebtId.parse(invalid))
            assertNull(ImageId.parse(invalid))
        }
    }

    @Test
    fun internalPurchaseDeepLinkIsStrictAndRoundTrips() {
        val purchaseId = PurchaseId.from(purchaseUuid)
        val valid = InternalDeepLinks.purchaseDetail(purchaseId)

        assertEquals(
            InternalDeepLinkTarget.PurchaseDetail(purchaseId),
            InternalDeepLinks.resolve(valid),
        )

        listOf(
            "",
            "%",
            "FACTURASTOCK://internal/purchases/${purchaseUuid}",
            "https://internal/purchases/${purchaseUuid}",
            "facturastock://external/purchases/${purchaseUuid}",
            "facturastock://internal.evil/purchases/${purchaseUuid}",
            "facturastock://user@internal/purchases/${purchaseUuid}",
            "facturastock://internal:443/purchases/${purchaseUuid}",
            "facturastock://internal//purchases/${purchaseUuid}",
            "facturastock://internal/purchases//${purchaseUuid}",
            "facturastock://internal/purchases/${purchaseUuid}/",
            "facturastock://internal/purchases/${purchaseUuid}?source=test",
            "facturastock://internal/purchases/${purchaseUuid}#section",
            "facturastock://internal/purchases/${purchaseUuid.toString().uppercase()}",
            "facturastock://internal/purchases/00000000-0000-0000-0000-000000000000",
            "facturastock://internal/purchases/${purchaseUuid}%2Fextra",
            "facturastock://internal/${"x".repeat(160)}",
        ).forEach { invalid ->
            assertNull(invalid, InternalDeepLinks.resolve(invalid))
        }
    }
}
