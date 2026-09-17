package com.facturastock.app.testing

import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRepositoriesTest {
    private var now: Instant = Instant.parse("2026-08-08T12:00:00Z")
    private val clock = AppClock { now }

    @Test
    fun `business create estampa tiempos y update solo toca updatedAt`() = runTest {
        val repository = FakeBusinessRepository(clock)
        val created = repository.create(business(businessId(1)))

        assertEquals(now, created.createdAt)
        assertEquals(now, created.updatedAt)
        assertEquals(created, repository.findById(businessId(1)))
        assertEquals(created, repository.findByRuc("20123456789"))

        val later = now.plusSeconds(60)
        now = later
        assertTrue(repository.update(created.copy(legalName = "Renombrada SAC")))
        val updated = repository.findById(businessId(1))!!
        assertEquals("Renombrada SAC", updated.legalName)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals(later, updated.updatedAt)

        assertFalse(repository.update(business(businessId(99))))
        assertTrue(repository.deleteById(businessId(1)))
        assertFalse(repository.deleteById(businessId(1)))
        assertNull(repository.findById(businessId(1)))
    }

    @Test
    fun `supplier search normaliza y observe emite ordenado tras cada mutacion`() = runTest {
        val repository = FakeSupplierRepository(clock)
        val beta = supplier(supplierId(2), legalName = "Beta SAC", tradeName = "Betita")
        val alfa = supplier(supplierId(1), legalName = "Alfa EIRL", ruc = "20111111111")

        repository.observeForBusiness(businessId(1)).test {
            assertEquals(emptyList<Supplier>(), awaitItem())

            repository.create(beta)
            assertEquals(listOf("Beta SAC"), awaitItem().map { it.legalName })

            repository.create(alfa)
            assertEquals(listOf("Alfa EIRL", "Beta SAC"), awaitItem().map { it.legalName })

            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("Alfa EIRL"), repository.search(businessId(1), "  ALFA ").map { it.legalName })
        assertEquals(listOf("Beta SAC"), repository.search(businessId(1), "betita").map { it.legalName })
        assertEquals(listOf("Beta SAC"), repository.search(businessId(1), "20555555555").map { it.legalName })
        assertEquals(emptyList<Supplier>(), repository.search(businessId(2), "alfa"))

        repository.observeForBusiness(businessId(1)).test {
            assertEquals(listOf("Alfa EIRL", "Beta SAC"), awaitItem().map { it.legalName })

            repository.archive(supplierId(2))
            val archived = awaitItem()
            assertEquals(listOf("Alfa EIRL", "Beta SAC"), archived.map { it.legalName })
            assertEquals(CatalogStatus.ARCHIVED, archived.single { it.supplierId == supplierId(2) }.status)

            repository.restore(supplierId(2))
            assertEquals(CatalogStatus.ACTIVE, awaitItem().single { it.supplierId == supplierId(2) }.status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unit archive en uso conserva la referencia historica`() = runTest {
        val products = FakeProductRepository(clock)
        val units = FakeUnitRepository(clock, productRepository = products)
        units.create(unit(unitId(1)))
        products.create(product(productId(1), unitId = unitId(1)))

        assertTrue(units.archive(unitId(1)))
        assertEquals(CatalogStatus.ARCHIVED, units.findById(unitId(1))?.status)
        assertEquals(unitId(1), products.findById(productId(1))?.unitId)
        assertTrue(units.restore(unitId(1)))
    }

    @Test
    fun `unit findByCode normaliza a mayusculas`() = runTest {
        val units = FakeUnitRepository(clock)
        units.create(unit(unitId(1), code = "KGM"))

        assertEquals("KGM", units.findByCode(businessId(1), " kgm ")?.code)
        assertNull(units.findByCode(businessId(1), "NIU"))
    }

    @Test
    fun `product search es contains insensible a caso y observe ordena por nombre`() = runTest {
        val products = FakeProductRepository(clock)
        products.create(product(productId(1), name = "Arroz Extra Costeño"))
        products.create(product(productId(2), name = "Azúcar Rubia"))
        products.create(product(productId(3), name = "arroz integral"))

        assertEquals(
            listOf("Arroz Extra Costeño", "arroz integral"),
            products.search(businessId(1), "ARROZ").map { it.name },
        )
        assertEquals(
            listOf("Azúcar Rubia"),
            products.search(businessId(1), "  azúcar  ").map { it.name },
        )
        assertEquals(
            listOf("Arroz Extra Costeño", "Azúcar Rubia", "arroz integral"),
            products.observeForBusiness(businessId(1)).first().map { it.name },
        )
    }

    @Test
    fun `alias lookup normaliza y observe ordena por alias`() = runTest {
        val aliases = FakeSupplierProductAliasRepository(clock)
        aliases.create(alias(aliasId(1), alias = "ARROZ X 50KG"))
        aliases.create(alias(aliasId(2), alias = "Arroz x 25kg"))

        assertEquals(
            listOf(aliasId(1)),
            aliases.findByNormalizedAlias(businessId(1), "  arroz x 50kg ").map { it.aliasId },
        )
        assertEquals(
            listOf("ARROZ X 50KG", "Arroz x 25kg"),
            aliases.observeForProduct(productId(1)).first().map { it.alias },
        )
    }

    @Test
    fun `location crud conserva el orden por nombre`() = runTest {
        val locations = FakeInventoryLocationRepository(clock)
        locations.create(location(locationId(1), name = "Mostrador"))
        locations.create(location(locationId(2), name = "Almacén"))

        assertEquals(
            listOf("Almacén", "Mostrador"),
            locations.observeForBusiness(businessId(1)).first().map { it.name },
        )
        assertEquals("Mostrador", locations.findByName(businessId(1), "Mostrador")?.name)
        assertTrue(locations.update(location(locationId(1), name = "Vitrina")))
        assertEquals(
            listOf("Almacén", "Vitrina"),
            locations.observeForBusiness(businessId(1)).first().map { it.name },
        )
        assertFalse(locations.archive(locationId(99)))
        assertTrue(locations.archive(locationId(2)))
        assertEquals(CatalogStatus.ARCHIVED, locations.findById(locationId(2))?.status)
        assertTrue(locations.restore(locationId(2)))
    }

    @Test
    fun `draft ciclo de vida con orden por updatedAt descendente y cascada`() = runTest {
        val drafts = FakeInvoiceDraftRepository(clock)
        val first = drafts.createDraft(draft(draftId(1)))
        now = now.plusSeconds(60)
        val second = drafts.createDraft(draft(draftId(2)))

        assertEquals(listOf(second, first), drafts.observeDrafts(businessId(1), null).first())
        assertEquals(
            emptyList<InvoiceDraft>(),
            drafts.observeDrafts(businessId(1), DraftStatus.READY_TO_POST).first(),
        )

        now = now.plusSeconds(60)
        drafts.updateDraft(first.copy(status = DraftStatus.NEEDS_REVIEW))
        assertEquals(
            DraftStatus.NEEDS_REVIEW,
            drafts.observeDraft(draftId(1)).first()?.status,
        )
        assertEquals(
            listOf(draftId(1), draftId(2)),
            drafts.observeDrafts(businessId(1), null).first().map { it.draftId },
        )
        assertEquals(
            listOf(draftId(1)),
            drafts.observeDrafts(businessId(1), DraftStatus.NEEDS_REVIEW).first().map { it.draftId },
        )

        drafts.seedImage(image(imageId(1), draftId(1), pageIndex = 0))
        drafts.addLine(line(lineId(1), draftId(1), position = 0))
        assertTrue(drafts.deleteDraft(draftId(1)))
        assertEquals(emptyList<InvoiceImage>(), drafts.observeImages(draftId(1)).first())
        assertEquals(emptyList<InvoiceLine>(), drafts.observeLines(draftId(1)).first())
        assertNull(drafts.findDraft(draftId(1)))
        assertFalse(drafts.deleteDraft(draftId(1)))
    }

    @Test
    fun `editar un borrador READY_TO_POST lo devuelve a NEEDS_REVIEW`() = runTest {
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(draft(draftId(1)).copy(status = DraftStatus.NEEDS_REVIEW))
        drafts.updateDraft(
            requireNotNull(drafts.findDraft(draftId(1))).copy(status = DraftStatus.READY_TO_POST),
        )
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId(1))?.status)

        drafts.updateDraft(
            requireNotNull(drafts.findDraft(draftId(1))).copy(supplierLegalNameRaw = "Editado"),
        )
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId(1))?.status)
        assertEquals("Editado", drafts.findDraft(draftId(1))?.supplierLegalNameRaw)

        drafts.updateDraft(
            requireNotNull(drafts.findDraft(draftId(1))).copy(status = DraftStatus.READY_TO_POST),
        )
        drafts.addLine(line(lineId(1), draftId(1), position = 0))
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId(1))?.status)
    }

    @Test
    fun `replaceLines reasigna posiciones, toca el borrador y rechaza ids duplicados`() = runTest {
        val drafts = FakeInvoiceDraftRepository(clock)
        val created = drafts.createDraft(draft(draftId(1)))
        val original = listOf(
            line(lineId(1), draftId(1), position = 10),
            line(lineId(2), draftId(1), position = 20),
        )

        now = now.plusSeconds(60)
        drafts.replaceLines(draftId(1), original)

        val stored = drafts.observeLines(draftId(1)).first()
        assertEquals(listOf(lineId(1), lineId(2)), stored.map { it.lineId })
        assertEquals(listOf(0, 1), stored.map { it.position })
        assertTrue(drafts.findDraft(draftId(1))!!.updatedAt > created.updatedAt)

        val duplicadas = listOf(
            line(lineId(3), draftId(1), position = 0),
            line(lineId(3), draftId(1), position = 1),
        )
        assertThrows(StorageException::class.java) {
            runBlocking { drafts.replaceLines(draftId(1), duplicadas) }
        }
        // El fake mantiene la atomicidad: las líneas originales sobreviven al fallo.
        assertEquals(listOf(lineId(1), lineId(2)), drafts.observeLines(draftId(1)).first().map { it.lineId })
    }

    @Test
    fun `reorderLines conserva el orden pedido y exige el conjunto exacto`() = runTest {
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(draft(draftId(1)))
        drafts.replaceLines(
            draftId(1),
            listOf(
                line(lineId(1), draftId(1), position = 0),
                line(lineId(2), draftId(1), position = 1),
                line(lineId(3), draftId(1), position = 2),
            ),
        )

        drafts.reorderLines(draftId(1), listOf(lineId(3), lineId(1), lineId(2)))

        val reordered = drafts.observeLines(draftId(1)).first()
        assertEquals(listOf(lineId(3), lineId(1), lineId(2)), reordered.map { it.lineId })
        assertEquals(listOf(0, 1, 2), reordered.map { it.position })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { drafts.reorderLines(draftId(1), listOf(lineId(1), lineId(2))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { drafts.reorderLines(draftId(1), listOf(lineId(1), lineId(2), lineId(9))) }
        }
    }

    @Test
    fun `fixture de imagen exige pagina libre y permite reemplazarla`() = runTest {
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(draft(draftId(1)).copy(status = DraftStatus.CAPTURED))
        drafts.seedImage(image(imageId(1), draftId(1), pageIndex = 0))

        assertThrows(StorageException::class.java) {
            runBlocking { drafts.seedImage(image(imageId(2), draftId(1), pageIndex = 0)) }
        }

        val persisted = drafts.replaceSeedImage(image(imageId(3), draftId(1), pageIndex = 0))
        assertEquals(
            listOf(imageId(3)),
            drafts.observeImages(draftId(1)).first().map { it.imageId },
        )
        val deletion = requireNotNull(drafts.deleteImage(imageId(3)))
        assertEquals(persisted, deletion.image)
        assertTrue(deletion.draftIsEmpty)
        assertEquals(DraftStatus.CREATED, drafts.findDraft(draftId(1))?.status)
        assertNull(drafts.deleteImage(imageId(3)))
    }

    @Test
    fun `conteo de rutas incluye aliases legacy exactos`() = runTest {
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(draft(draftId(1)))
        val sharedPath = "draft_images/legacy/shared.jpg"
        drafts.seedImage(image(imageId(1), draftId(1), pageIndex = 0).copy(filePath = sharedPath))
        drafts.seedImage(image(imageId(2), draftId(1), pageIndex = 1).copy(filePath = sharedPath))

        assertTrue(drafts.isImagePathReferenced(sharedPath))
        assertEquals(2, drafts.countImagePathReferences(sharedPath))
        assertEquals(0, drafts.countImagePathReferences("draft_images/legacy/missing.jpg"))
    }

    @Test
    fun `hook de fallos fuerza un StorageException una sola vez`() = runTest {
        val repository = FakeBusinessRepository(clock)
        repository.nextFailure = StorageError.Unavailable

        val exception = assertThrows(StorageException::class.java) {
            runBlocking { repository.findById(businessId(1)) }
        }
        assertEquals(StorageError.Unavailable, exception.error)

        // El hook se consume: la siguiente operación funciona.
        repository.create(business(businessId(1)))
        assertEquals(businessId(1), repository.findById(businessId(1))?.businessId)
    }

    @Test
    fun `config fake arranca con defaults y completeOnboarding fija los campos`() = runTest {
        val config = FakeAppConfigurationRepository()
        assertEquals(AppConfiguration.defaults(), config.current())
        assertEquals(AppConfiguration.defaults(), config.observe().first())

        config.completeOnboarding(businessId(1), TaxRate(BigDecimal("10.5")), CostPolicy.GROSS)

        val current = config.observe().first()
        assertTrue(current.onboardingCompleted)
        assertEquals(businessId(1), current.businessId)
        assertEquals(TaxRate(BigDecimal("10.5")), current.taxRate)
        assertEquals(CostPolicy.GROSS, current.costPolicy)
    }

    @Test
    fun `config fake actualiza claves sueltas y mueve el negocio activo con el demo`() = runTest {
        val config = FakeAppConfigurationRepository()
        config.completeOnboarding(businessId(1), TaxRate(BigDecimal("18")), CostPolicy.NET)

        config.updateTaxRate(TaxRate(BigDecimal("4")))
        assertEquals(TaxRate(BigDecimal("4")), config.current().taxRate)
        assertEquals(CostPolicy.NET, config.current().costPolicy)

        config.updateCostPolicy(CostPolicy.GROSS)
        assertEquals(CostPolicy.GROSS, config.current().costPolicy)
        assertEquals(TaxRate(BigDecimal("4")), config.current().taxRate)

        config.enterDemoMode(businessId(2))
        assertTrue(config.current().isDemoMode)
        assertEquals(businessId(2), config.current().activeBusinessId)

        config.exitDemoMode()
        assertFalse(config.current().isDemoMode)
        assertEquals(businessId(1), config.current().activeBusinessId)
    }

    @Test
    fun `config fake honra el hook de fallos`() = runTest {
        val config = FakeAppConfigurationRepository()
        config.nextFailure = StorageError.Unavailable

        val exception = assertThrows(StorageException::class.java) {
            runBlocking { config.current() }
        }
        assertEquals(StorageError.Unavailable, exception.error)
        assertEquals(AppConfiguration.defaults(), config.current())
    }

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private fun businessId(seed: Int) = BusinessId.from(uuid(seed))
    private fun supplierId(seed: Int) = SupplierId.from(uuid(seed))
    private fun unitId(seed: Int) = UnitId.from(uuid(seed))
    private fun locationId(seed: Int) = LocationId.from(uuid(seed))
    private fun productId(seed: Int) = ProductId.from(uuid(seed))
    private fun aliasId(seed: Int) = AliasId.from(uuid(seed))
    private fun draftId(seed: Int) = DraftId.from(uuid(seed))
    private fun imageId(seed: Int) = ImageId.from(uuid(seed))
    private fun lineId(seed: Int) = LineId.from(uuid(seed))

    private fun business(id: BusinessId, ruc: String = "20123456789") = Business(
        businessId = id,
        legalName = "Negocio $ruc",
        ruc = ruc,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun supplier(
        id: SupplierId,
        legalName: String,
        ruc: String? = "20555555555",
        tradeName: String? = null,
    ) = Supplier(
        supplierId = id,
        businessId = businessId(1),
        legalName = legalName,
        ruc = ruc,
        tradeName = tradeName,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun unit(id: UnitId, code: String = "NIU") = UnitOfMeasure(
        unitId = id,
        businessId = businessId(1),
        code = code,
        name = "Unidad $code",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun location(id: LocationId, name: String) = InventoryLocation(
        locationId = id,
        businessId = businessId(1),
        name = name,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun product(id: ProductId, unitId: UnitId = unitId(1), name: String = "Arroz") = Product(
        productId = id,
        businessId = businessId(1),
        unitId = unitId,
        name = name,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun alias(id: AliasId, alias: String) = SupplierProductAlias(
        aliasId = id,
        businessId = businessId(1),
        supplierId = supplierId(1),
        productId = productId(1),
        alias = alias,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun draft(id: DraftId) = InvoiceDraft(
        draftId = id,
        businessId = businessId(1),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun image(id: ImageId, draftId: DraftId, pageIndex: Int) = InvoiceImage(
        imageId = id,
        draftId = draftId,
        businessId = businessId(1),
        pageIndex = pageIndex,
        filePath = "captures/${draftId.value}/page-$pageIndex.jpg",
        sha256 = "a".repeat(64),
        mimeType = "image/jpeg",
        widthPx = 3_000,
        heightPx = 4_000,
        fileSizeBytes = 1_000L,
        createdAt = Instant.EPOCH,
    )

    private fun line(id: LineId, draftId: DraftId, position: Int) = InvoiceLine(
        lineId = id,
        draftId = draftId,
        businessId = businessId(1),
        position = position,
        descriptionRaw = "ARROZ EXTRA COSTEÑO X 50 KG",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
