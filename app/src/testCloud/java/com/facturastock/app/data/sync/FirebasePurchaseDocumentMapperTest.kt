package com.facturastock.app.data.sync

import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseReadAuditEvent
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseReadDuplicateOverride
import com.facturastock.app.domain.model.PurchaseReadLine
import com.facturastock.app.domain.model.PurchaseReadMovement
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseRetainedImage
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseProductProvenance
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
import com.facturastock.app.domain.repository.BackupEnvelope
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebasePurchaseDocumentMapperTest {

    @Test
    fun `cien lineas se proyectan completas en orden sin fotos rutas ni OCR crudo`() {
        val detail = detail(lineCount = 100)

        val document = FirebasePurchaseDocumentMapper.map(detail, ENVELOPE, CLOUD_BUSINESS_ID)
        val secondPass = FirebasePurchaseDocumentMapper.map(detail, ENVELOPE, CLOUD_BUSINESS_ID)

        assertEquals(document, secondPass)
        assertEquals(100, (document.getValue("lines") as List<*>).size)
        assertEquals(100, (document.getValue("movements") as List<*>).size)
        assertEquals(CLOUD_BUSINESS_ID.value, document["businessId"])
        assertEquals(ENVELOPE.idempotencyKey, document["idempotencyKey"])
        assertEquals("POSTED", document["status"])

        val lastLine = (document.getValue("lines") as List<*>).last() as Map<*, *>
        assertEquals(99, lastLine["position"])
        assertEquals(uuid(10_099).toString(), lastLine["purchaseLineId"])
        assertEquals("2", lastLine["quantity"])

        val wireText = document.toString()
        assertFalse(wireText.contains(PRIVATE_IMAGE_PATH))
        assertFalse(wireText.contains(RAW_OCR_MARKER))
    }

    @Test
    fun `alta pendiente reconstruida tras anulacion conserva exactamente el wire POSTED`() {
        val originallyPosted = detail(lineCount = 1)
        val alreadyVoided = detail(
            lineCount = 1,
            status = PurchaseStatus.VOIDED,
            includeVoidHistory = true,
        )

        val originalDocument = FirebasePurchaseDocumentMapper.map(
            originallyPosted,
            ENVELOPE,
            CLOUD_BUSINESS_ID,
        )
        val replayDocument = FirebasePurchaseDocumentMapper.map(
            alreadyVoided,
            ENVELOPE,
            CLOUD_BUSINESS_ID,
        )

        assertEquals(PurchaseStatus.VOIDED, alreadyVoided.summary.status)
        assertEquals(originalDocument, replayDocument)
        assertEquals("POSTED", replayDocument["status"])
        assertEquals(1, (replayDocument.getValue("movements") as List<*>).size)
        assertEquals(listOf(uuid(30_000).toString()), replayDocument["auditEventIds"])
    }

    @Test
    fun `outbox v2 con override conserva exactamente documento legacy v1`() {
        val legacy = FirebasePurchaseDocumentMapper.map(
            detail(lineCount = 1, withDuplicateOverride = true),
            ENVELOPE,
            CLOUD_BUSINESS_ID,
        )
        val replay = FirebasePurchaseDocumentMapper.map(
            detail(lineCount = 1, withDuplicateOverride = true),
            ENVELOPE,
            CLOUD_BUSINESS_ID,
        )

        assertEquals(legacy, replay)
        assertEquals(1, legacy["version"])
        assertFalse(legacy.containsKey("duplicateOverride"))
        val line = (legacy.getValue("lines") as List<*>).single() as Map<*, *>
        assertFalse(line.containsKey("taxTreatment"))
        assertFalse(line.containsKey("taxEvidence"))
        assertFalse(line.containsKey("productProvenance"))
        val movement = (legacy.getValue("movements") as List<*>).single() as Map<*, *>
        assertFalse(movement.containsKey("locationName"))
        assertFalse(movement.containsKey("appliedCostTotal"))
        assertEquals(
            listOf(OVERRIDE_AUDIT_ID, uuid(30_000).toString()),
            legacy["auditEventIds"],
        )
    }

    @Test
    fun `payload v3 proyecta documento v2 con decision tributaria y procedencia explicitas`() {
        val document = FirebasePurchaseDocumentMapper.map(
            detail(lineCount = 1, currentWire = true),
            CURRENT_ENVELOPE,
            CLOUD_BUSINESS_ID,
        )

        assertEquals(2, document["version"])
        assertEquals(null, document["duplicateOverride"])
        val line = (document.getValue("lines") as List<*>).single() as Map<*, *>
        assertEquals("INCLUDED", line["taxTreatment"])
        assertEquals("EXISTING", line["productProvenance"])
        assertEquals(
            mapOf("type" to "EXPLICIT_AMOUNT", "value" to "1.80"),
            line["taxEvidence"],
        )
        val movement = (document.getValue("movements") as List<*>).single() as Map<*, *>
        assertFalse(movement.containsKey("locationName"))
        assertFalse(movement.containsKey("appliedCostTotal"))
    }

    @Test
    fun `payload v4 proyecta documento v3 con ubicacion y costo total aplicado exactos`() {
        val document = FirebasePurchaseDocumentMapper.map(
            detail(lineCount = 1, currentWire = true),
            CURRENT_INVENTORY_ENVELOPE,
            CLOUD_BUSINESS_ID,
        )

        assertEquals(3, document["version"])
        val movement = (document.getValue("movements") as List<*>).single() as Map<*, *>
        assertEquals("Almacén principal", movement["locationName"])
        assertEquals("10.000", movement["appliedCostTotal"])
        assertEquals("5.00", movement["unitCost"])
        val line = (document.getValue("lines") as List<*>).single() as Map<*, *>
        assertFalse(line.containsKey("appliedCostTotal"))
    }

    @Test
    fun `compra usa identidad remota durable en linea y movimiento`() {
        val localProductId = uuid(1_000).toString()
        val document = FirebasePurchaseDocumentMapper.map(
            detail = detail(lineCount = 1, currentWire = true),
            envelope = CURRENT_INVENTORY_ENVELOPE,
            cloudBusinessId = CLOUD_BUSINESS_ID,
            remoteProductIds = mapOf(localProductId to REMOTE_PRODUCT_ID),
        )

        val line = (document.getValue("lines") as List<*>).single() as Map<*, *>
        val movement = (document.getValue("movements") as List<*>).single() as Map<*, *>
        assertEquals(REMOTE_PRODUCT_ID, line["productId"])
        assertEquals(REMOTE_PRODUCT_ID, movement["productId"])
    }

    @Test
    fun `anulacion remapea producto sin alterar evidencia ni otros campos`() {
        val localProductId = uuid(1_000).toString()
        val payload = """
            {"version":1,"purchaseId":"${PURCHASE_ID.value}","impactHash":"${"a".repeat(64)}","actorId":"${uuid(40)}","role":"OWNER","reason":"Corrección autorizada","negativeStockPolicy":"ALLOW_WITH_VISIBLE_WARNING","averageUnitCostPolicy":"PRESERVE_CURRENT","negativeImpactCount":0,"impacts":[{"productId":"$localProductId","locationId":"${LOCATION_ID.value}","currentQuantity":"2","reversalQuantity":"-2","resultingQuantity":"0","currentAverageUnitCost":"5.00","currency":"PEN","balanceVersion":1,"negative":false}]}
        """.trimIndent()

        assertEquals(setOf(localProductId), FirebasePurchaseVoidDocumentMapper.productIds(payload))
        val remapped = FirebasePurchaseVoidDocumentMapper.remap(
            payload,
            mapOf(localProductId to REMOTE_PRODUCT_ID),
        )

        assertTrue(remapped.contains("\"productId\":\"$REMOTE_PRODUCT_ID\""))
        assertTrue(remapped.contains("\"impactHash\":\"${"a".repeat(64)}\""))
        assertFalse(remapped.contains("\"productId\":\"$localProductId\""))
    }

    @Test
    fun `override v2 envia target motivo y audit id pero nunca actor local`() {
        val document = FirebasePurchaseDocumentMapper.map(
            detail(lineCount = 1, currentWire = true, withDuplicateOverride = true),
            CURRENT_ENVELOPE,
            CLOUD_BUSINESS_ID,
        )

        assertEquals(
            mapOf(
                "existingPurchaseId" to OVERRIDE_TARGET_ID.value,
                "sourceDraftId" to DRAFT_ID.value,
                "auditEventId" to OVERRIDE_AUDIT_ID,
                "reason" to OVERRIDE_REASON,
            ),
            document["duplicateOverride"],
        )
        assertFalse(document.toString().contains("local-owner-id"))
        assertFalse(document.toString().contains("actorRole"))
    }

    private fun detail(
        lineCount: Int,
        status: PurchaseStatus = PurchaseStatus.POSTED,
        includeVoidHistory: Boolean = false,
        currentWire: Boolean = false,
        withDuplicateOverride: Boolean = false,
    ): PurchaseReadDetail {
        val lines = (0 until lineCount).map { index ->
            PurchaseReadLine(
                purchaseLineId = uuid(10_000 + index).toString(),
                position = index,
                productId = ProductId.from(uuid(1_000 + index)),
                productName = "Producto $index",
                unitId = UNIT_ID,
                unitCode = "NIU",
                unitSymbol = "u",
                rawText = "$RAW_OCR_MARKER $index",
                description = "Producto $index",
                quantity = BigDecimal("2"),
                readUnitCost = UnitCost.of("5.00", PEN),
                tax = Money.ofMinor(180L, PEN),
                total = Money.ofMinor(1_180L, PEN),
                appliedUnitCost = UnitCost.of("5.00", PEN),
                appliedCostTotal = if (currentWire) BigDecimal("10.000") else null,
                inventoryQuantity = BigDecimal("2"),
                discount = BigDecimal.ZERO,
                productProvenance = if (currentWire) {
                    PurchaseProductProvenance.EXISTING
                } else {
                    PurchaseProductProvenance.UNKNOWN_LEGACY
                },
                taxTreatment = if (currentWire) InventoryTaxTreatment.INCLUDED else null,
                taxEvidence = if (currentWire) {
                    InventoryTaxEvidence.ExplicitAmount(BigDecimal("1.80"))
                } else {
                    null
                },
            )
        }
        val postingMovements = lines.map { line ->
            PurchaseReadMovement(
                movementId = uuid(20_000 + line.position).toString(),
                purchaseId = PURCHASE_ID,
                purchaseLineId = line.purchaseLineId,
                productId = line.productId,
                productName = line.productName,
                locationId = LOCATION_ID,
                locationName = "Almacén principal",
                type = StockMovementType.PURCHASE,
                quantityDelta = BigDecimal("2"),
                unitCost = UnitCost.of("5.00", PEN),
                occurredAt = POSTED_AT,
            )
        }
        val summary = PurchaseReadSummary(
            purchaseId = PURCHASE_ID,
            businessId = LOCAL_BUSINESS_ID,
            sourceDraftId = DRAFT_ID,
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor Rendimiento SAC",
            documentType = PurchaseDocumentType.INVOICE,
            documentSeries = "F001",
            documentNumber = "00000100",
            issueDate = LocalDate.of(2026, 8, 20),
            currency = PEN,
            total = Money.ofMinor(118_000L, PEN),
            status = status,
            syncState = PurchaseSyncState.PENDING_SYNC,
            lineCount = lineCount,
            productCount = lineCount,
            postedAt = POSTED_AT,
            existingProductCount = if (currentWire) lineCount else 0,
            unknownProductCount = if (currentWire) 0 else lineCount,
        )
        val movements = if (includeVoidHistory) {
            postingMovements + postingMovements.map { movement ->
                movement.copy(
                    movementId = uuid(25_000 + lines.indexOfFirst {
                        it.purchaseLineId == movement.purchaseLineId
                    }).toString(),
                    type = StockMovementType.VOID,
                    quantityDelta = movement.quantityDelta.negate(),
                    occurredAt = POSTED_AT.plusSeconds(60),
                )
            }
        } else {
            postingMovements
        }
        val postingAudit = PurchaseReadAuditEvent(
            auditEventId = uuid(30_000).toString(),
            eventType = AuditEventType.PURCHASE_POSTED,
            entityType = "PURCHASE",
            entityId = PURCHASE_ID.value,
            occurredAt = POSTED_AT,
        )
        val postingHistory = if (withDuplicateOverride) {
            listOf(
                PurchaseReadAuditEvent(
                    auditEventId = OVERRIDE_AUDIT_ID,
                    eventType = AuditEventType.PURCHASE_DUPLICATE_OVERRIDE,
                    entityType = "PURCHASE",
                    entityId = PURCHASE_ID.value,
                    occurredAt = POSTED_AT,
                ),
                postingAudit,
            )
        } else {
            listOf(postingAudit)
        }
        val auditEvents = if (includeVoidHistory) {
            listOf(
                postingAudit,
                PurchaseReadAuditEvent(
                    auditEventId = uuid(30_001).toString(),
                    eventType = AuditEventType.PURCHASE_VOIDED,
                    entityType = "PURCHASE",
                    entityId = PURCHASE_ID.value,
                    occurredAt = POSTED_AT.plusSeconds(60),
                ),
            )
        } else {
            postingHistory
        }
        return PurchaseReadDetail(
            summary = summary,
            supplierId = SUPPLIER_ID,
            subtotal = Money.ofMinor(100_000L, PEN),
            tax = Money.ofMinor(18_000L, PEN),
            otherCharges = Money.zero(PEN),
            adjustment = null,
            lines = lines,
            movements = movements,
            auditEvents = auditEvents,
            images = listOf(
                PurchaseRetainedImage(
                    imageId = ImageId.from(uuid(9_001)),
                    pageIndex = 0,
                    relativeFilePath = PRIVATE_IMAGE_PATH,
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
            acceptedWarnings = emptyList(),
            duplicateOverride = if (withDuplicateOverride) {
                PurchaseReadDuplicateOverride(
                    existingPurchaseId = OVERRIDE_TARGET_ID,
                    reason = OVERRIDE_REASON,
                    actorId = "local-owner-id",
                    actorRole = PurchaseOverrideRole.OWNER,
                )
            } else {
                null
            },
        )
    }

    private companion object {
        const val RAW_OCR_MARKER = "OCR_RAW_SECRET"
        const val PRIVATE_IMAGE_PATH = "draft_images/private-receipt.jpg"
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val POSTED_AT: Instant = Instant.parse("2026-08-20T12:00:00Z")
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(uuid(2))
        val PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(3))
        val DRAFT_ID: DraftId = DraftId.from(uuid(4))
        val SUPPLIER_ID: SupplierId = SupplierId.from(uuid(5))
        val UNIT_ID: UnitId = UnitId.from(uuid(6))
        val LOCATION_ID: LocationId = LocationId.from(uuid(7))
        val OVERRIDE_TARGET_ID: PurchaseId = PurchaseId.from(uuid(9))
        val REMOTE_PRODUCT_ID: String = uuid(90_000).toString()
        val OVERRIDE_AUDIT_ID: String = uuid(30_002).toString()
        const val OVERRIDE_REASON = "Factura repetida autorizada por recepción separada"
        val ENVELOPE = BackupEnvelope(
            operationId = uuid(8).toString(),
            businessId = LOCAL_BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
            purchaseId = PURCHASE_ID,
            idempotencyKey = "sync-purchase:v1:${PURCHASE_ID.value}",
            operationType = "SYNC_PURCHASE",
            payloadVersion = 2,
            payload = "{\"version\":2}",
        )
        val CURRENT_ENVELOPE = ENVELOPE.copy(
            payloadVersion = 3,
            payload = "{\"version\":3}",
        )
        val CURRENT_INVENTORY_ENVELOPE = ENVELOPE.copy(
            payloadVersion = 4,
            payload = "{\"version\":4}",
        )

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-4000-8000-%012d".format(seed))
    }
}
