package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogInvalidField
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeUnitRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogManagementUseCasesTest {
    private val now = Instant.parse("2026-08-13T12:00:00Z")
    private val clock = AppClock { now }

    @Test
    fun `save supplier canonicalizes persistence and reports duplicate deterministically`() = runTest {
        val repository = FakeSupplierRepository(clock)
        val save = SaveSupplierCatalogUseCase(repository)

        val first = save(supplier(1, ruc = " 20987654321 ")) as CatalogMutationResult.Saved
        assertEquals("20987654321", first.value.ruc)
        assertEquals(
            CatalogMutationResult.Duplicate(CatalogDuplicateField.RUC),
            save(supplier(2, ruc = "20987654321")),
        )
    }

    @Test
    fun `save supplier rejects malformed RUC without claiming an external lookup`() = runTest {
        val save = SaveSupplierCatalogUseCase(FakeSupplierRepository(clock))
        val exception = assertThrows(DomainRuleViolation::class.java) {
            kotlinx.coroutines.runBlocking { save(supplier(1, ruc = "123")) }
        }
        assertEquals(ValidationError.InvalidRuc("123"), exception.error)
    }

    @Test
    fun `save product rejects inactive new references and cross-business takeover`() = runTest {
        val products = FakeProductRepository(clock)
        val units = FakeUnitRepository(clock)
        val locations = FakeInventoryLocationRepository(clock)
        units.create(unit(1, status = CatalogStatus.ARCHIVED))
        locations.create(location(1))
        val save = SaveProductCatalogUseCase(products, units, locations)

        assertEquals(
            CatalogMutationResult.Invalid(CatalogInvalidField.UNIT),
            save(product(1, unitId = unitId(1))),
        )
        units.restore(unitId(1))
        val created = save(product(1, unitId = unitId(1))) as CatalogMutationResult.Saved
        assertEquals(businessId(1), created.value.businessId)
        assertEquals(
            CatalogMutationResult.Invalid(CatalogInvalidField.OWNERSHIP),
            save(product(1, businessId = businessId(2), unitId = unitId(1))),
        )
    }

    @Test
    fun `save product canonicalizes valid barcode and rejects invalid create or update`() = runTest {
        val products = FakeProductRepository(clock)
        val units = FakeUnitRepository(clock)
        units.create(unit(1, status = CatalogStatus.ACTIVE))
        val save = SaveProductCatalogUseCase(
            products,
            units,
            FakeInventoryLocationRepository(clock),
        )

        val invalidCreate = assertThrows(DomainRuleViolation::class.java) {
            kotlinx.coroutines.runBlocking {
                save(product(1, unitId = unitId(1), barcode = "café"))
            }
        }
        assertEquals(ValidationError.InvalidBarcode, invalidCreate.error)

        val created = save(
            product(2, unitId = unitId(1), barcode = "  0012aB-Z  "),
        ) as CatalogMutationResult.Saved
        assertEquals("0012aB-Z", created.value.barcode)

        val invalidUpdate = assertThrows(DomainRuleViolation::class.java) {
            kotlinx.coroutines.runBlocking {
                save(created.value.copy(barcode = "ABC\u202E123"))
            }
        }
        assertEquals(ValidationError.InvalidBarcode, invalidUpdate.error)
        assertEquals("0012aB-Z", products.findById(created.value.productId)?.barcode)
    }

    private fun supplier(seed: Int, ruc: String?) = Supplier(
        supplierId = SupplierId.from(uuid(100 + seed)),
        businessId = businessId(1),
        legalName = " Proveedor $seed ",
        ruc = ruc,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun unit(seed: Int, status: CatalogStatus) = UnitOfMeasure(
        unitId = unitId(seed),
        businessId = businessId(1),
        code = "NIU$seed",
        name = "Unidad $seed",
        status = status,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun location(seed: Int) = InventoryLocation(
        locationId = LocationId.from(uuid(300 + seed)),
        businessId = businessId(1),
        name = "Almacén $seed",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun product(
        seed: Int,
        businessId: BusinessId = businessId(1),
        unitId: UnitId,
        barcode: String? = null,
    ) = Product(
        productId = ProductId.from(uuid(400 + seed)),
        businessId = businessId,
        unitId = unitId,
        name = "Producto $seed",
        barcode = barcode,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun businessId(seed: Int) = BusinessId.from(uuid(seed))
    private fun unitId(seed: Int) = UnitId.from(uuid(200 + seed))
    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
