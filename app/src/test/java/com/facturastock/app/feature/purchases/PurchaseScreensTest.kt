package com.facturastock.app.feature.purchases

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.facturastock.app.data.demo.DemoInvoiceFixture
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseReadAuditEvent
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseReadDuplicateOverride
import com.facturastock.app.domain.model.PurchaseReadLine
import com.facturastock.app.domain.model.PurchaseReadMovement
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseRetainedImage
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PurchaseScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listShowsSearchFiltersSupplierDocumentTotalStatusAndSyncChip() {
        val purchase = sampleDetail().summary
        val opened = mutableListOf<PurchaseId>()
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseListScreen(
                    purchases = listOf(purchase),
                    query = "",
                    statusFilter = null,
                    syncFilter = null,
                    onQueryChange = {},
                    onStatusFilterChange = {},
                    onSyncFilterChange = {},
                    onPurchaseClick = opened::add,
                    onNewPurchase = {},
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseListTestTags.SEARCH).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseListTestTags.statusFilter(null)).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseListTestTags.syncFilter(null)).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseListTestTags.row(purchase.purchaseId))
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Registrada", substring = true)
            .assertTextContains("Respaldo pendiente", substring = true)
            .performClick()
        composeRule.onNodeWithText("Proveedor Video SAC").assertIsDisplayed()
        composeRule.onNodeWithText("Factura · F001-42").assertIsDisplayed()
        assertEquals(listOf(purchase.purchaseId), opened)
    }

    @Test
    fun listShowsOneObjectiveLoadMoreActionOnlyWhenAnotherPageExists() {
        var loadMoreCalls = 0
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseListScreen(
                    purchases = listOf(sampleDetail().summary),
                    query = "",
                    statusFilter = null,
                    syncFilter = null,
                    onQueryChange = {},
                    onStatusFilterChange = {},
                    onSyncFilterChange = {},
                    onPurchaseClick = {},
                    onNewPurchase = {},
                    hasMore = true,
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseListTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseListTestTags.LOAD_MORE))
        composeRule.onNodeWithTag(PurchaseListTestTags.LOAD_MORE)
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, loadMoreCalls)
    }

    @Test
    fun successShowsCanonicalDemoTotalPositiveAdjustmentReasonAnd38Lines() {
        val detail = canonicalDemoDetail()
        assertCanonicalDemoDetail(detail)
        var detailClicks = 0
        var inventoryClicks = 0
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseSuccessScreen(
                    detail = detail,
                    onViewDetail = { detailClicks++ },
                    onViewInventory = { inventoryClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseSuccessTestTags.SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText("38 líneas · 1 producto creado · 6 productos vinculados")
            .assertIsDisplayed()
        composeRule.onNodeWithText("97.83", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseSuccessTestTags.PERSISTENCE).performScrollTo()
        composeRule.onNodeWithText("Guardado en este celular").assertIsDisplayed()
        composeRule.onNodeWithText("Respaldo pendiente").assertIsDisplayed()
        composeRule.onNodeWithTag(
            PurchaseSuccessTestTags.ADJUSTMENT,
            useUnmergedTree = true,
        )
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("+S/", substring = true)
            .assertTextContains("0.03", substring = true)
        composeRule.onNodeWithText(
            DemoInvoiceFixture.REQUIRED_ADJUSTMENT_REASON,
            substring = true,
            useUnmergedTree = true,
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseSuccessTestTags.VIEW_DETAIL)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(PurchaseSuccessTestTags.VIEW_INVENTORY)
            .performScrollTo()
            .performClick()
        assertEquals(1, detailClicks)
        assertEquals(1, inventoryClicks)
    }

    @Test
    fun successKeepsLegacyProductProvenanceExplicitInsteadOfInventingCounts() {
        val current = sampleDetail()
        val legacy = current.copy(
            summary = current.summary.copy(
                createdProductCount = 0,
                existingProductCount = 0,
                unknownProductCount = 1,
            ),
            lines = current.lines.map { line ->
                line.copy(productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY)
            },
        )
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseSuccessScreen(
                    detail = legacy,
                    onViewDetail = {},
                    onViewInventory = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "1 línea · 0 productos creados · 0 productos vinculados · " +
                "1 producto sin procedencia registrada",
        ).assertIsDisplayed()
    }

    @Test
    fun backupErrorKeepsLocalGuaranteeVisibleAndOffersRealRetryAction() {
        val failed = sampleDetail().let { detail ->
            detail.copy(
                summary = detail.summary.copy(
                    syncState = PurchaseSyncState.ERROR,
                    lastSyncError = "INTERNAL_STACK_MUST_NOT_BE_RENDERED",
                    lastSyncAttemptAt = Instant.parse("2026-08-14T16:00:00Z"),
                ),
            )
        }
        var retries = 0
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseSuccessScreen(
                    detail = failed,
                    onViewDetail = {},
                    onViewInventory = {},
                    onRetryBackup = { retries++ },
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseSuccessTestTags.PERSISTENCE).performScrollTo()
        composeRule.onNodeWithText("Guardado en este celular").assertIsDisplayed()
        composeRule.onNodeWithText("Error de respaldo").assertIsDisplayed()
        composeRule.onNodeWithText("La compra local está segura", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("INTERNAL_STACK_MUST_NOT_BE_RENDERED")
            .assertDoesNotExist()
        composeRule.onNodeWithTag(PurchaseSuccessTestTags.RETRY_BACKUP)
            .performScrollTo()
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun detailShowsCanonicalDemoTotalPositiveAdjustmentReasonAnd38Movements() {
        val detail = canonicalDemoDetail()
        val technicalDetailsVisible = mutableStateOf(false)
        assertCanonicalDemoDetail(detail)
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseDetailScreen(
                    detail = detail,
                    onBack = {},
                    technicalDetailsVisible = technicalDetailsVisible.value,
                    onTechnicalDetailsToggle = {
                        technicalDetailsVisible.value = !technicalDetailsVisible.value
                    },
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseDetailTestTags.TOTALS)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("97.83", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Diferencia aceptada").assertIsDisplayed()
        composeRule.onNodeWithText("+S/", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("0.03", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(DemoInvoiceFixture.REQUIRED_ADJUSTMENT_REASON)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.LINES))
        composeRule.onNodeWithText("Productos (38)").assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.line(37)))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.line(37))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.MOVEMENTS).assertDoesNotExist()
        showTechnicalDetails()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.MOVEMENTS))
        composeRule.onNodeWithText("Movimientos de inventario (38)").assertIsDisplayed()
        val lastMovementTag = PurchaseDetailTestTags.movement(
            detail.movements.last().movementId,
        )
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(lastMovementTag))
        composeRule.onNodeWithTag(lastMovementTag)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.AUDIT))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.AUDIT).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.IMAGES))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.IMAGES).assertIsDisplayed()
        composeRule.onNodeWithText("ya no está retenida", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.INTEGRITY))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.INTEGRITY)
            .assertIsDisplayed()
    }

    @Test
    fun remoteDocumentIsClearlyTemporaryAndNeverPresentedAsRestored() {
        val detail = sampleDetail()
        val image = detail.images.single()
        val technicalDetailsVisible = mutableStateOf(false)
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseDetailScreen(
                    detail = detail,
                    onBack = {},
                    retainedImages = mapOf(
                        image.imageId to PurchasesContract.RetainedImageContent.Available.from(
                            byteArrayOf(1, 2, 3),
                            PurchasesContract.RetainedImageContent.Available.Source.REMOTE,
                        ),
                    ),
                    technicalDetailsVisible = technicalDetailsVisible.value,
                    onTechnicalDetailsToggle = {
                        technicalDetailsVisible.value = !technicalDetailsVisible.value
                    },
                )
            }
        }

        showTechnicalDetails()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.IMAGES))
        composeRule.onNodeWithText(
            "Vista temporal desde el respaldo. El archivo no se restauró ni se guardó en este celular.",
        )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun detailShowsDurableExactDuplicateOverrideMetadata() {
        val targetPurchaseId = PurchaseId.from(uuid(12))
        val reason = "Comprobante repetido validado contra el documento físico"
        val actorId = "firebase-uid-authorizer"
        val detail = sampleDetail().copy(
            duplicateOverride = PurchaseReadDuplicateOverride(
                existingPurchaseId = targetPurchaseId,
                reason = reason,
                actorId = actorId,
                actorRole = PurchaseOverrideRole.MANAGER,
            ),
        )
        val technicalDetailsVisible = mutableStateOf(false)
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseDetailScreen(
                    detail = detail,
                    onBack = {},
                    technicalDetailsVisible = technicalDetailsVisible.value,
                    onTechnicalDetailsToggle = {
                        technicalDetailsVisible.value = !technicalDetailsVisible.value
                    },
                )
            }
        }

        showTechnicalDetails()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.DUPLICATE_OVERRIDE))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.DUPLICATE_OVERRIDE)
            .assertIsDisplayed()
        composeRule.onNodeWithText(targetPurchaseId.value).assertIsDisplayed()
        composeRule.onNodeWithText(reason).assertIsDisplayed()
        composeRule.onNodeWithText(actorId).assertIsDisplayed()
        composeRule.onNodeWithText("Administrador").assertIsDisplayed()
    }

    @Test
    fun onlyPostedDetailOffersVoidAndVoidedHistoryRemainsConsultable() {
        val detailState = mutableStateOf(sampleDetail())
        var voidClicks = 0
        composeRule.setContent {
            FacturaStockTheme {
                PurchaseDetailScreen(
                    detail = detailState.value,
                    onBack = {},
                    onVoidPurchase = { voidClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.VOID_ACTION))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.VOID_ACTION)
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, voidClicks)

        composeRule.runOnIdle {
            detailState.value = detailState.value.copy(
                summary = detailState.value.summary.copy(status = PurchaseStatus.VOIDED),
            )
        }
        composeRule.onNodeWithTag(PurchaseDetailTestTags.VOID_ACTION).assertDoesNotExist()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.VOIDED_NOTICE))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.VOIDED_NOTICE)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Compra anulada").assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.DOCUMENT))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.DOCUMENT)
            .assertIsDisplayed()
        assertEquals(1, voidClicks)
    }

    private fun canonicalDemoDetail(): PurchaseReadDetail {
        val currency = CurrencyCode.of("PEN")
        val businessId = BusinessId.from(uuid(350))
        val purchaseId = PurchaseId.from(uuid(351))
        val locationId = LocationId.from(uuid(352))
        val lines = DemoInvoiceFixture.lines.map { fixtureLine ->
            val purchaseLineId = uuid(800 + fixtureLine.position).toString()
            val productId = canonicalProductId(fixtureLine.position)
            val unitId = UnitId.from(
                uuid(if (fixtureLine.unitCode == "LTR") 601 else 600),
            )
            val unitCost = UnitCost.of(
                BigDecimal.valueOf(fixtureLine.unitPriceMinorUnits, 2).toPlainString(),
                currency,
            )
            PurchaseReadLine(
                purchaseLineId = purchaseLineId,
                position = fixtureLine.position,
                productId = productId,
                productName = fixtureLine.description,
                unitId = unitId,
                unitCode = fixtureLine.unitCode,
                unitSymbol = if (fixtureLine.unitCode == "LTR") "L" else "und",
                rawText = "${fixtureLine.supplierCode} ${fixtureLine.description}",
                description = fixtureLine.description,
                quantity = BigDecimal.ONE,
                readUnitCost = unitCost,
                tax = Money.zero(currency),
                total = Money.ofMinor(fixtureLine.lineTotalMinorUnits, currency),
                appliedUnitCost = unitCost,
                inventoryQuantity = BigDecimal.ONE,
                discount = BigDecimal.ZERO,
                productProvenance = if (
                    fixtureLine.position == DemoPurchaseScenario.TOTAL_LINE_COUNT - 1
                ) {
                    PurchaseProductProvenance.CREATED_IN_DRAFT
                } else {
                    PurchaseProductProvenance.EXISTING
                },
            )
        }
        val movements = lines.map { line ->
            PurchaseReadMovement(
                movementId = uuid(900 + line.position).toString(),
                purchaseId = purchaseId,
                purchaseLineId = line.purchaseLineId,
                productId = line.productId,
                productName = line.productName,
                locationId = locationId,
                locationName = "[DEMO] Almacén principal",
                type = StockMovementType.PURCHASE,
                quantityDelta = BigDecimal.ONE,
                unitCost = line.appliedUnitCost,
                occurredAt = Instant.parse("2026-08-14T15:00:00Z"),
            )
        }
        val summary = PurchaseReadSummary(
            purchaseId = purchaseId,
            businessId = businessId,
            sourceDraftId = DemoPurchaseScenario.draftIdFor(businessId),
            supplierRuc = DemoInvoiceFixture.SUPPLIER_RUC,
            supplierLegalName = DemoInvoiceFixture.SUPPLIER_LEGAL_NAME,
            documentType = PurchaseDocumentType.INVOICE,
            documentSeries = DemoInvoiceFixture.DOCUMENT_NUMBER.substringBefore('-'),
            documentNumber = DemoInvoiceFixture.DOCUMENT_NUMBER.substringAfter('-'),
            issueDate = DemoInvoiceFixture.issueDate,
            currency = currency,
            total = Money.ofMinor(DemoInvoiceFixture.TARGET_TOTAL_MINOR_UNITS, currency),
            status = PurchaseStatus.POSTED,
            syncState = PurchaseSyncState.PENDING_SYNC,
            lineCount = lines.size,
            productCount = lines.map(PurchaseReadLine::productId).distinct().size,
            postedAt = Instant.parse("2026-08-14T15:00:00Z"),
            createdProductCount = 1,
            existingProductCount = DemoPurchaseScenario.EXISTING_PRODUCTS.size,
            unknownProductCount = 0,
        )
        return PurchaseReadDetail(
            summary = summary,
            supplierId = SupplierId.from(uuid(353)),
            subtotal = Money.ofMinor(DemoInvoiceFixture.SUBTOTAL_MINOR_UNITS, currency),
            tax = Money.ofMinor(DemoInvoiceFixture.IGV_MINOR_UNITS, currency),
            otherCharges = Money.zero(currency),
            adjustment = Money.ofMinor(
                DemoInvoiceFixture.REQUIRED_ADJUSTMENT_MINOR_UNITS,
                currency,
            ),
            lines = lines,
            movements = movements,
            auditEvents = listOf(
                PurchaseReadAuditEvent(
                    auditEventId = uuid(354).toString(),
                    eventType = AuditEventType.PURCHASE_POSTED,
                    entityType = "PURCHASE",
                    entityId = purchaseId.value,
                    occurredAt = Instant.parse("2026-08-14T15:00:00Z"),
                ),
            ),
            images = listOf(
                PurchaseRetainedImage(
                    imageId = ImageId.from(uuid(355)),
                    pageIndex = 0,
                    relativeFilePath = "draft_images/demo/invoice-f035.jpg",
                    mimeType = "image/jpeg",
                    widthPx = DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
                    heightPx = DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
                    rotationDegrees = 0,
                    cropLeftFraction = null,
                    cropTopFraction = null,
                    cropRightFraction = null,
                    cropBottomFraction = null,
                ),
            ),
            preparedLogicalHash = "d".repeat(64),
            acceptedWarnings = listOf("LINES_TOTAL_DIFFERENCE"),
            adjustmentReason = DemoInvoiceFixture.REQUIRED_ADJUSTMENT_REASON,
        )
    }

    private fun showTechnicalDetails() {
        composeRule.onNodeWithTag(PurchaseDetailTestTags.LIST)
            .performScrollToNode(hasTestTag(PurchaseDetailTestTags.TECHNICAL_TOGGLE))
        composeRule.onNodeWithTag(PurchaseDetailTestTags.TECHNICAL_TOGGLE)
            .assertIsDisplayed()
            .performClick()
    }

    private fun canonicalProductId(position: Int): ProductId {
        val productIndex = when {
            position < DemoPurchaseScenario.EXISTING_LINE_COUNT ->
                position % DemoPurchaseScenario.EXISTING_PRODUCTS.size

            position == DemoPurchaseScenario.EXISTING_LINE_COUNT -> 0
            else -> DemoPurchaseScenario.EXISTING_PRODUCTS.size
        }
        return ProductId.from(uuid(500 + productIndex))
    }

    private fun assertCanonicalDemoDetail(detail: PurchaseReadDetail) {
        assertEquals(DemoInvoiceFixture.EXPECTED_LINE_COUNT, detail.lines.size)
        assertEquals(
            DemoInvoiceFixture.CALCULATED_TOTAL_MINOR_UNITS,
            detail.lines.sumOf { line -> line.total.minorUnits },
        )
        assertEquals(DemoInvoiceFixture.TARGET_TOTAL_MINOR_UNITS, detail.summary.total.minorUnits)
        assertEquals(
            DemoInvoiceFixture.REQUIRED_ADJUSTMENT_MINOR_UNITS,
            detail.adjustment?.minorUnits,
        )
        assertEquals(DemoInvoiceFixture.REQUIRED_ADJUSTMENT_REASON, detail.adjustmentReason)
        assertEquals(7, detail.lines.map(PurchaseReadLine::productId).distinct().size)
        assertEquals(7, detail.productCount)
        assertEquals(1, detail.summary.createdProductCount)
        assertEquals(6, detail.summary.existingProductCount)
        assertEquals(0, detail.summary.unknownProductCount)
        assertEquals(DemoInvoiceFixture.EXPECTED_LINE_COUNT, detail.movements.size)
        assertEquals(
            DemoInvoiceFixture.EXPECTED_LINE_COUNT,
            detail.movements.map(PurchaseReadMovement::purchaseLineId).distinct().size,
        )
        assertEquals(
            DemoInvoiceFixture.EXPECTED_LINE_COUNT,
            detail.movements.count { movement -> movement.type == StockMovementType.PURCHASE },
        )
    }

    private fun sampleDetail(): PurchaseReadDetail {
        val currency = CurrencyCode.of("PEN")
        val purchaseId = PurchaseId.from(uuid(1))
        val productId = ProductId.from(uuid(5))
        val purchaseLineId = uuid(8).toString()
        val summary = PurchaseReadSummary(
            purchaseId = purchaseId,
            businessId = BusinessId.from(uuid(2)),
            sourceDraftId = DraftId.from(uuid(3)),
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor Video SAC",
            documentType = PurchaseDocumentType.INVOICE,
            documentSeries = "F001",
            documentNumber = "42",
            issueDate = LocalDate.of(2026, 8, 14),
            currency = currency,
            total = Money.ofMinor(11_800L, currency),
            status = PurchaseStatus.POSTED,
            syncState = PurchaseSyncState.PENDING_SYNC,
            lineCount = 1,
            productCount = 1,
            postedAt = Instant.parse("2026-08-14T15:00:00Z"),
            createdProductCount = 0,
            existingProductCount = 1,
            unknownProductCount = 0,
        )
        val line = PurchaseReadLine(
            purchaseLineId = purchaseLineId,
            position = 0,
            productId = productId,
            productName = "Producto vinculado",
            unitId = UnitId.from(uuid(6)),
            unitCode = "NIU",
            unitSymbol = "und",
            rawText = "1 PRODUCTO 118.03",
            description = "Producto vinculado",
            quantity = BigDecimal.ONE,
            readUnitCost = UnitCost.of("118.03", currency),
            tax = Money.ofMinor(1_800L, currency),
            total = Money.ofMinor(11_803L, currency),
            appliedUnitCost = UnitCost.of("100.03", currency),
            inventoryQuantity = BigDecimal.ONE,
            discount = BigDecimal.ZERO,
            productProvenance = PurchaseProductProvenance.EXISTING,
        )
        return PurchaseReadDetail(
            summary = summary,
            supplierId = SupplierId.from(uuid(4)),
            subtotal = Money.ofMinor(10_000L, currency),
            tax = Money.ofMinor(1_800L, currency),
            otherCharges = Money.zero(currency),
            adjustment = Money.ofMinor(-3L, currency),
            lines = listOf(line),
            movements = listOf(
                PurchaseReadMovement(
                    movementId = uuid(9).toString(),
                    purchaseId = purchaseId,
                    purchaseLineId = purchaseLineId,
                    productId = productId,
                    productName = line.productName,
                    locationId = LocationId.from(uuid(7)),
                    locationName = "Almacén principal",
                    type = StockMovementType.PURCHASE,
                    quantityDelta = BigDecimal.ONE,
                    unitCost = UnitCost.of("100.03", currency),
                    occurredAt = Instant.parse("2026-08-14T15:00:00Z"),
                ),
            ),
            auditEvents = listOf(
                PurchaseReadAuditEvent(
                    auditEventId = uuid(10).toString(),
                    eventType = AuditEventType.PURCHASE_POSTED,
                    entityType = "PURCHASE",
                    entityId = purchaseId.value,
                    occurredAt = Instant.parse("2026-08-14T15:00:00Z"),
                ),
            ),
            images = listOf(
                PurchaseRetainedImage(
                    imageId = ImageId.from(uuid(11)),
                    pageIndex = 0,
                    relativeFilePath = "draft_images/missing/invoice.jpg",
                    mimeType = "image/jpeg",
                    widthPx = 1_200,
                    heightPx = 1_600,
                    rotationDegrees = 0,
                    cropLeftFraction = null,
                    cropTopFraction = null,
                    cropRightFraction = null,
                    cropBottomFraction = null,
                ),
            ),
            preparedLogicalHash = "a".repeat(64),
            acceptedWarnings = listOf("LINES_TOTAL_DIFFERENCE"),
            adjustmentReason = "Redondeo documentado por el proveedor",
        )
    }

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
