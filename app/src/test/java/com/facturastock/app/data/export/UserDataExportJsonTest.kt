package com.facturastock.app.data.export

import com.facturastock.app.data.repository.jsonEscapedForPayload
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExportedAuditEvent
import com.facturastock.app.domain.model.ExportedBusiness
import com.facturastock.app.domain.model.ExportedInventoryBalance
import com.facturastock.app.domain.model.ExportedInventoryLocation
import com.facturastock.app.domain.model.ExportedProduct
import com.facturastock.app.domain.model.ExportedPurchase
import com.facturastock.app.domain.model.ExportedPurchaseLine
import com.facturastock.app.domain.model.ExportedRetainedImage
import com.facturastock.app.domain.model.ExportedStockMovement
import com.facturastock.app.domain.model.ExportedSupplier
import com.facturastock.app.domain.model.ExportedSupplierProductAlias
import com.facturastock.app.domain.model.ExportedUnit
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.UserDataExport
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrato ficticio del JSON contable: alcance, determinismo, precisión y ausencia de rutas. */
class UserDataExportJsonTest {
    @Test
    fun `serializa todas las secciones del libro y declara sus exclusiones`() {
        val root = parse(fullExport())

        assertEquals(4, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("ACCOUNTING_LEDGER", root.string("exportKind"))
        assertFalse(root.getValue("imageFilesIncluded").jsonPrimitive.boolean)
        assertEquals(UserDataExport.EXCLUDED_DATA, root.strings("excludedData"))
        assertTrue(root.strings("excludedData").contains("DEBTS_AND_PAYMENTS"))
        listOf(
            "inventoryBalances",
            "inventoryLocations",
            "products",
            "purchases",
            "stockMovements",
            "supplierProductAliases",
            "suppliers",
            "units",
        ).forEach { key -> assertEquals("$key debe contener el fixture", 1, root.array(key).size) }
        assertEquals(2, root.array("auditEvents").size)
        assertEquals(
            "null",
            root.array("auditEvents")[1].jsonObject.getValue("purchaseId").toString(),
        )

        val purchase = root.array("purchases").single().jsonObject
        assertEquals("1.250", purchase.array("lines").single().jsonObject.string("quantity"))
        assertEquals(3L, purchase.string("adjustmentMinorUnits").toLong())
        assertEquals(IMAGE_ID, purchase.array("retainedImages").single().jsonObject.string("imageId"))
        assertFalse(UserDataExportJson.serialize(fullExport()).contains("draft_images/"))
    }

    @Test
    fun `la serializacion es determinista y conserva escaping sin notacion exponencial`() {
        val export = fullExport().let { value ->
            value.copy(
                business = value.business?.copy(
                    legalName = "Bodega \"El \\ Patrón\"\nsalto\t tab",
                ),
                inventoryBalances = value.inventoryBalances.map {
                    it.copy(quantityOnHand = BigDecimal("1000000000000000000.000000000000000001"))
                },
            )
        }

        val first = UserDataExportJson.serialize(export)
        val second = UserDataExportJson.serialize(export)

        assertEquals(first, second)
        assertEquals(
            "Bodega \"El \\ Patrón\"\nsalto\t tab",
            Json.parseToJsonElement(first).jsonObject.getValue("business").jsonObject
                .string("legalName"),
        )
        assertTrue(first.contains("1000000000000000000.000000000000000001"))
        assertFalse(first.contains("E+"))
        assertFalse(first.contains("/data/user/"))
        assertFalse(first.contains("filesDir"))
    }

    @Test
    fun `la escritura incremental conserva exactamente el contrato sin emitir el documento entero`() {
        val export = fullExport().let { value ->
            value.copy(auditEvents = List(500) { index ->
                value.auditEvents[index % value.auditEvents.size].copy(
                    auditEventId = "evento-$index",
                    entityId = "entidad-$index",
                )
            })
        }
        val expected = UserDataExportJson.serialize(export)
        val destination = BoundedAppendable(maxChunkLength = 128)

        UserDataExportJson.writeTo(export, destination)

        assertEquals(expected, destination.toString())
        assertTrue(destination.appendCalls > export.auditEvents.size)
    }

    @Test
    fun `la salida incremental conserva el escape canonico para todos los controles JSON`() {
        val edgeValue = buildString {
            append("comillas=\" barra=\\")
            (0x00..0x1f).forEach { append(it.toChar()) }
            append(" unicode=Patrón")
        }
        val export = fullExport().let { value ->
            value.copy(business = value.business?.copy(legalName = edgeValue))
        }

        val serialized = UserDataExportJson.serialize(export)

        assertTrue(
            serialized.contains("\"legalName\":\"${edgeValue.jsonEscapedForPayload()}\""),
        )
        assertEquals(edgeValue, parse(export).getValue("business").jsonObject.string("legalName"))
    }

    @Test
    fun `sin negocio el libro es vacio pero conserva alcance auto descriptivo`() {
        val root = parse(emptyExport())

        assertEquals("null", root.getValue("business").toString())
        assertEquals("ACCOUNTING_LEDGER", root.string("exportKind"))
        assertTrue(root.array("purchases").isEmpty())
        assertTrue(root.array("stockMovements").isEmpty())
        assertTrue(root.array("auditEvents").isEmpty())
    }

    private fun parse(export: UserDataExport): JsonObject =
        Json.parseToJsonElement(UserDataExportJson.serialize(export)).jsonObject

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String): List<String> = array(key).map { it.jsonPrimitive.content }

    private fun emptyExport(): UserDataExport = UserDataExport(
        exportedAt = EXPORTED_AT,
        business = null,
        suppliers = emptyList(),
        units = emptyList(),
        inventoryLocations = emptyList(),
        products = emptyList(),
        supplierProductAliases = emptyList(),
        purchases = emptyList(),
        inventoryBalances = emptyList(),
        stockMovements = emptyList(),
        auditEvents = emptyList(),
    )

    private fun fullExport(): UserDataExport = UserDataExport(
        exportedAt = EXPORTED_AT,
        business = ExportedBusiness(
            businessId = BUSINESS_ID,
            legalName = "Bodega Central SAC",
            ruc = "20123456789",
            tradeName = "La Central",
            status = "ACTIVE",
            createdAt = CREATED_AT,
            updatedAt = UPDATED_AT,
        ),
        suppliers = listOf(
            ExportedSupplier(
                supplierId = SUPPLIER_ID,
                legalName = "Molinos SA",
                ruc = "20987654321",
                tradeName = null,
                status = "ACTIVE",
                createdAt = CREATED_AT,
                updatedAt = UPDATED_AT,
                version = 2,
            ),
        ),
        units = listOf(
            ExportedUnit(
                unitId = UNIT_ID,
                code = "NIU",
                name = "Unidad",
                symbol = "und",
                status = "ACTIVE",
                createdAt = CREATED_AT,
                updatedAt = UPDATED_AT,
            ),
        ),
        inventoryLocations = listOf(
            ExportedInventoryLocation(
                locationId = LOCATION_ID,
                name = "Almacén",
                status = "ACTIVE",
                createdAt = CREATED_AT,
                updatedAt = UPDATED_AT,
            ),
        ),
        products = listOf(
            ExportedProduct(
                productId = PRODUCT_ID,
                unitId = UNIT_ID,
                locationId = LOCATION_ID,
                name = "Arroz extra",
                sku = "ARR-1",
                barcode = null,
                purchaseUnitId = UNIT_ID,
                purchaseFactor = BigDecimal("12"),
                status = "ACTIVE",
                createdAt = CREATED_AT,
                updatedAt = UPDATED_AT,
                version = 3,
            ),
        ),
        supplierProductAliases = listOf(
            ExportedSupplierProductAlias(
                aliasId = ALIAS_ID,
                supplierId = SUPPLIER_ID,
                productId = PRODUCT_ID,
                alias = "ARROZ X12",
                createdAt = CREATED_AT,
                updatedAt = UPDATED_AT,
            ),
        ),
        purchases = listOf(
            ExportedPurchase(
                purchaseId = PURCHASE_ID,
                sourceDraftId = DRAFT_ID,
                supplierId = SUPPLIER_ID,
                supplierRuc = "20987654321",
                supplierLegalName = "Molinos SA",
                documentType = "INVOICE",
                documentSeries = "F001",
                documentNumber = "000123",
                issueDate = LocalDate.of(2026, 8, 10),
                currency = PEN,
                subtotal = Money.ofMinor(937, PEN),
                tax = Money.ofMinor(169, PEN),
                otherCharges = Money.zero(PEN),
                adjustment = Money.ofMinor(3, PEN),
                adjustmentReason = "Redondeo explícito del comprobante",
                total = Money.ofMinor(1_109, PEN),
                status = "POSTED",
                postedAt = UPDATED_AT,
                preparedLogicalHash = "a".repeat(64),
                acceptedWarnings = listOf("TOTAL_ADJUSTMENT_ACCEPTED"),
                duplicateOverride = null,
                lines = listOf(
                    ExportedPurchaseLine(
                        purchaseLineId = PURCHASE_LINE_ID,
                        position = 0,
                        productId = PRODUCT_ID,
                        productName = "Arroz extra",
                        unitId = UNIT_ID,
                        unitCode = "NIU",
                        unitSymbol = "und",
                        rawText = "1,25 ARROZ 11,06",
                        description = "Arroz extra x12",
                        quantity = BigDecimal("1.250"),
                        readUnitCost = BigDecimal("3.75"),
                        appliedUnitCost = BigDecimal("3.18"),
                        inventoryQuantity = BigDecimal("15.000"),
                        discount = BigDecimal.ZERO,
                        taxMinorUnits = 169,
                        totalMinorUnits = 1_106,
                        productProvenance = "EXISTING",
                        taxTreatment = "EXCLUDED",
                        taxEvidenceType = "EXPLICIT_AMOUNT",
                        taxEvidenceValue = BigDecimal("1.69"),
                    ),
                ),
                retainedImages = listOf(
                    ExportedRetainedImage(
                        imageId = IMAGE_ID,
                        pageIndex = 0,
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
            ),
        ),
        inventoryBalances = listOf(
            ExportedInventoryBalance(
                productId = PRODUCT_ID,
                locationId = LOCATION_ID,
                quantityOnHand = BigDecimal("15.000"),
                averageUnitCost = BigDecimal("3.18"),
                currency = "PEN",
                version = 1,
                updatedAt = UPDATED_AT,
                alerts = emptyList(),
            ),
        ),
        stockMovements = listOf(
            ExportedStockMovement(
                movementId = MOVEMENT_ID,
                productId = PRODUCT_ID,
                locationId = LOCATION_ID,
                type = "PURCHASE_IN",
                quantityDelta = BigDecimal("15.000"),
                unitCost = BigDecimal("3.18"),
                currency = "PEN",
                purchaseId = PURCHASE_ID,
                purchaseLineId = PURCHASE_LINE_ID,
                purchaseDocumentNumber = "F001-000123",
                occurredAt = UPDATED_AT,
                createdAt = UPDATED_AT,
                alerts = emptyList(),
            ),
        ),
        auditEvents = listOf(
            ExportedAuditEvent(
                auditEventId = AUDIT_ID,
                purchaseId = PURCHASE_ID,
                eventType = "PURCHASE_POSTED",
                entityType = "PURCHASE",
                entityId = PURCHASE_ID,
                occurredAt = UPDATED_AT,
            ),
            ExportedAuditEvent(
                auditEventId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                purchaseId = null,
                eventType = "SYNC_RECONCILED",
                entityType = "business",
                entityId = BUSINESS_ID,
                occurredAt = UPDATED_AT.plusSeconds(1),
            ),
        ),
    )

    private companion object {
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val CREATED_AT: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val UPDATED_AT: Instant = Instant.parse("2026-08-11T08:30:00Z")
        val EXPORTED_AT: Instant = Instant.parse("2026-08-17T12:00:00Z")
        const val BUSINESS_ID = "11111111-1111-4111-8111-111111111111"
        const val SUPPLIER_ID = "22222222-2222-4222-8222-222222222222"
        const val UNIT_ID = "33333333-3333-4333-8333-333333333333"
        const val LOCATION_ID = "44444444-4444-4444-8444-444444444444"
        const val PRODUCT_ID = "55555555-5555-4555-8555-555555555555"
        const val ALIAS_ID = "66666666-6666-4666-8666-666666666666"
        const val PURCHASE_ID = "77777777-7777-4777-8777-777777777777"
        const val DRAFT_ID = "88888888-8888-4888-8888-888888888888"
        const val PURCHASE_LINE_ID = "99999999-9999-4999-8999-999999999999"
        const val IMAGE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val MOVEMENT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val AUDIT_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    }
}

/** Rechaza una implementación que vuelva a entregar el JSON completo en un único append. */
private class BoundedAppendable(private val maxChunkLength: Int) : Appendable {
    private val value = StringBuilder()
    var appendCalls: Int = 0
        private set

    override fun append(charSequence: CharSequence?): Appendable {
        val text = charSequence ?: "null"
        check(text.length <= maxChunkLength) { "Chunk no acotado de ${text.length} caracteres" }
        appendCalls += 1
        value.append(text)
        return this
    }

    override fun append(charSequence: CharSequence?, startIndex: Int, endIndex: Int): Appendable {
        val text = charSequence ?: "null"
        check(endIndex - startIndex <= maxChunkLength) {
            "Chunk no acotado de ${endIndex - startIndex} caracteres"
        }
        appendCalls += 1
        value.append(text, startIndex, endIndex)
        return this
    }

    override fun append(character: Char): Appendable {
        appendCalls += 1
        value.append(character)
        return this
    }

    override fun toString(): String = value.toString()
}
