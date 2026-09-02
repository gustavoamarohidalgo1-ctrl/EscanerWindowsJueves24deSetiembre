package com.facturastock.app.data.local.mapper

import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MappersTest {
    private val businessId = "123e4567-e89b-42d3-a456-426614174000"
    private val supplierId = "223e4567-e89b-42d3-a456-426614174000"
    private val unitId = "323e4567-e89b-42d3-a456-426614174000"
    private val locationId = "423e4567-e89b-42d3-a456-426614174000"
    private val productId = "523e4567-e89b-42d3-a456-426614174000"
    private val aliasId = "623e4567-e89b-42d3-a456-426614174000"
    private val draftId = "723e4567-e89b-42d3-a456-426614174000"
    private val imageId = "823e4567-e89b-42d3-a456-426614174000"
    private val lineId = "923e4567-e89b-42d3-a456-426614174000"
    private val purchaseId = "a23e4567-e89b-42d3-a456-426614174000"

    @Test
    fun `negocio sobrevive al ciclo entidad-dominio-entidad`() {
        val entity = BusinessEntity(
            businessId = businessId,
            legalName = "FacturaStock SAC",
            ruc = "20123456789",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            tradeName = "FacturaStock",
            status = CatalogStatus.ARCHIVED.name,
        )

        val domain = entity.toDomain()

        assertEquals(BusinessId.parse(businessId), domain.businessId)
        assertEquals(CatalogStatus.ARCHIVED, domain.status)
        assertEquals(Instant.ofEpochMilli(1_000L), domain.createdAt)
        assertEquals(Instant.ofEpochMilli(2_000L), domain.updatedAt)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `proveedor sobrevive al ciclo entidad-dominio-entidad`() {
        val entity = SupplierEntity(
            supplierId = supplierId,
            businessId = businessId,
            legalName = "Distribuidora Sur SRL",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            ruc = "20987654321",
            tradeName = "Disur",
        )

        val domain = entity.toDomain()

        assertEquals(SupplierId.parse(supplierId), domain.supplierId)
        assertEquals(BusinessId.parse(businessId), domain.businessId)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `unidad y ubicacion sobreviven al ciclo entidad-dominio-entidad`() {
        val unit = UnitEntity(
            unitId = unitId,
            businessId = businessId,
            code = "KGM",
            name = "Kilogramo",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            symbol = "kg",
        )
        val location = InventoryLocationEntity(
            locationId = locationId,
            businessId = businessId,
            name = "Almacén central",
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )

        assertEquals(UnitId.parse(unitId), unit.toDomain().unitId)
        assertEquals(unit, unit.toDomain().toEntity())
        assertEquals(location, location.toDomain().toEntity())
    }

    @Test
    fun `producto deriva normalizedName en la entidad y no lo expone al dominio`() {
        val domain = ProductEntity(
            productId = productId,
            businessId = businessId,
            unitId = unitId,
            name = "Arroz Extra Costeño",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            locationId = locationId,
            sku = "SKU-ARR-01",
            barcode = "7750001000011",
            salePriceMinorUnits = 650L,
            salePriceCurrencyCode = "PEN",
        ).toDomain()

        assertEquals(ProductId.parse(productId), domain.productId)
        assertEquals(UnitId.parse(unitId), domain.unitId)
        assertEquals("Arroz Extra Costeño", domain.name)
        assertEquals(Money.ofMinor(650L, CurrencyCode.of("PEN")), domain.salePrice)

        val restored = domain.toEntity()

        assertEquals("arroz extra costeño", restored.normalizedName)
        assertEquals(650L, restored.salePriceMinorUnits)
        assertEquals("PEN", restored.salePriceCurrencyCode)
        assertEquals(domain, restored.toDomain())
    }

    @Test
    fun `producto Unicode heredado se lee intacto pero no habilita una escritura nueva`() {
        val legacy = ProductEntity(
            productId = productId,
            businessId = businessId,
            unitId = unitId,
            name = "Café heredado",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            barcode = "café-旧",
        ).toDomain()

        assertEquals("café-旧", legacy.barcode)
        val failure = assertThrows(DomainRuleViolation::class.java) { legacy.toEntity() }
        assertEquals(ValidationError.InvalidBarcode, failure.error)
    }

    @Test
    fun `alias deriva aliasNormalized en la entidad y no lo expone al dominio`() {
        val domain = SupplierProductAliasEntity(
            aliasId = aliasId,
            businessId = businessId,
            supplierId = supplierId,
            productId = productId,
            alias = "ARROZ EXTRA COSTEÑO 50KG",
            createdAt = 1_000L,
            updatedAt = 1_000L,
        ).toDomain()

        val restored = domain.toEntity()

        assertEquals("arroz extra costeño 50kg", restored.aliasNormalized)
        assertEquals(domain, restored.toDomain())
    }

    @Test
    fun `borrador reconstruye los importes con la moneda de la cabecera`() {
        val entity = InvoiceDraftEntity(
            draftId = draftId,
            businessId = businessId,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            status = DraftStatus.NEEDS_REVIEW.name,
            supplierId = supplierId,
            supplierRucRaw = "RUC 20987654321",
            supplierRucNormalized = "20987654321",
            supplierLegalNameRaw = "DISTRIBUIDORA SUR S.A.C.",
            supplierLegalNameNormalized = "Distribuidora Sur S.A.C.",
            documentType = PurchaseDocumentType.INVOICE.name,
            documentNumberRaw = "F001-  12345",
            documentNumberNormalized = "F001-12345",
            issueDateRaw = "2026-08-07",
            issueDateNormalized = "2026-08-07",
            currencyCode = "PEN",
            subtotalMinorUnits = 800L,
            taxMinorUnits = 144L,
            otherChargesMinorUnits = 20L,
            totalMinorUnits = 944L,
            headerConfidence = 998,
            confirmedPurchaseId = purchaseId,
            lastError = "sin texto en la página 2",
        )

        val domain = entity.toDomain()

        assertEquals(DraftId.parse(draftId), domain.draftId)
        assertEquals(DraftStatus.NEEDS_REVIEW, domain.status)
        assertEquals(LocalDate.parse("2026-08-07"), domain.issueDate)
        assertEquals(CurrencyCode.of("PEN"), domain.currency)
        assertEquals(Money.ofMinor(800L, CurrencyCode.of("PEN")), domain.subtotal)
        assertEquals(Money.ofMinor(144L, CurrencyCode.of("PEN")), domain.tax)
        assertEquals(Money.ofMinor(20L, CurrencyCode.of("PEN")), domain.otherCharges)
        assertEquals(Money.ofMinor(944L, CurrencyCode.of("PEN")), domain.total)
        assertEquals("Distribuidora Sur S.A.C.", domain.supplierLegalNameNormalized)
        assertEquals(PurchaseDocumentType.INVOICE, domain.documentType)
        assertEquals(PurchaseId.parse(purchaseId), domain.confirmedPurchaseId)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `borrador sin importes persiste moneda nula`() {
        val entity = InvoiceDraftEntity(
            draftId = draftId,
            businessId = businessId,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )

        val domain = entity.toDomain()

        assertNull(domain.currency)
        assertNull(domain.subtotal)
        assertNull(domain.issueDate)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `borrador exige que los importes usen la moneda de la cabecera`() {
        val base = InvoiceDraftEntity(
            draftId = draftId,
            businessId = businessId,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            currencyCode = "PEN",
            totalMinorUnits = 944L,
        ).toDomain()

        assertThrows(IllegalArgumentException::class.java) {
            // Importes sin moneda de cabecera.
            base.copy(currency = null).toEntity()
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Importe en una moneda distinta a la de la cabecera.
            base.copy(total = Money.ofMinor(944L, CurrencyCode.of("USD"))).toEntity()
        }
    }

    @Test
    fun `imagen con recorte sobrevive al ciclo entidad-dominio-entidad`() {
        val entity = InvoiceImageEntity(
            imageId = imageId,
            draftId = draftId,
            businessId = businessId,
            pageIndex = 1,
            filePath = "captures/$draftId/page-1.jpg",
            sha256 = "a".repeat(64),
            mimeType = "image/jpeg",
            widthPx = 3_000,
            heightPx = 4_000,
            fileSizeBytes = 2_500_000L,
            createdAt = 1_000L,
            rotationDegrees = 90,
            cropLeftFraction = 250,
            cropTopFraction = 500,
            cropRightFraction = 9_000,
            cropBottomFraction = 9_500,
        )

        val domain = entity.toDomain()

        assertEquals(
            ImageCrop(left = 250, top = 500, right = 9_000, bottom = 9_500),
            domain.crop,
        )
        assertEquals(Instant.ofEpochMilli(1_000L), domain.createdAt)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `imagen sin recorte conserva el nulo en ambos sentidos`() {
        val entity = InvoiceImageEntity(
            imageId = imageId,
            draftId = draftId,
            businessId = businessId,
            pageIndex = 0,
            filePath = "captures/$draftId/page-0.jpg",
            sha256 = "b".repeat(64),
            mimeType = "image/png",
            widthPx = 3_000,
            heightPx = 4_000,
            fileSizeBytes = 1_000L,
            createdAt = 1_000L,
        )

        val domain = entity.toDomain()

        assertNull(domain.crop)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `linea conserva la escala decimal de cantidad y costo unitario`() {
        val entity = InvoiceLineEntity(
            lineId = lineId,
            draftId = draftId,
            businessId = businessId,
            position = 3,
            descriptionRaw = "ARROZ EXTRA COSTEÑO X 50 KG",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            descriptionNormalized = "arroz extra costeño x 50 kg",
            codeRaw = "COD A-1",
            codeNormalized = "A-1",
            quantity = "12.340",
            unitRaw = "UND",
            unitCodeNormalized = "NIU",
            unitCost = "0.5000",
            unitCostCurrency = "PEN",
            discountMinorUnits = 10L,
            taxMinorUnits = 100L,
            unitId = unitId,
            productId = productId,
            lineTotalMinorUnits = 617L,
            ocrConfidence = 998,
            linkConfidence = 500,
        )

        val domain = entity.toDomain()
        val quantity = domain.quantity!!
        val unitCost = domain.unitCost!!

        assertEquals(LineId.parse(lineId), domain.lineId)
        assertEquals("12.340", quantity.value.toPlainString())
        assertEquals(3, quantity.scale)
        assertEquals("0.5000", unitCost.amount.toPlainString())
        assertEquals(4, unitCost.scale)
        assertEquals("A-1", domain.codeNormalized)
        assertEquals("NIU", domain.unitCodeNormalized)
        assertEquals(Money.ofMinor(10L, CurrencyCode.of("PEN")), domain.discount)
        assertEquals(Money.ofMinor(100L, CurrencyCode.of("PEN")), domain.tax)
        assertEquals(Money.ofMinor(617L, CurrencyCode.of("PEN")), domain.lineTotal)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `linea exige moneda comun y permite total sin costo resuelto`() {
        val base = InvoiceLineEntity(
            lineId = lineId,
            draftId = draftId,
            businessId = businessId,
            position = 0,
            descriptionRaw = "ARROZ EXTRA COSTEÑO X 50 KG",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            unitCost = "12.34",
            unitCostCurrency = "PEN",
            lineTotalMinorUnits = 1_851L,
        ).toDomain()

        assertThrows(IllegalArgumentException::class.java) {
            // Total en moneda distinta a la del costo unitario.
            base.copy(lineTotal = Money.ofMinor(1_851L, CurrencyCode.of("USD"))).toEntity()
        }
        val totalOnly = base.copy(unitCost = null).toEntity()
        assertNull(totalOnly.unitCost)
        assertEquals("PEN", totalOnly.unitCostCurrency)
        assertEquals(1_851L, totalOnly.lineTotalMinorUnits)
    }
}
