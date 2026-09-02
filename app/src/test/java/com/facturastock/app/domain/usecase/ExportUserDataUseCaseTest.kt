package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.BusinessAuditEventRead
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeInventoryReadRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.FakeUserDataExportWriter
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportUserDataUseCaseTest {
    private val clock = AppClock { NOW }
    private val configuration = FakeAppConfigurationRepository()
    private val businesses = FakeBusinessRepository(clock)
    private val products = FakeProductRepository(clock)
    private val suppliers = FakeSupplierRepository(clock)
    private val units = FakeUnitRepository(clock)
    private val locations = FakeInventoryLocationRepository(clock)
    private val aliases = FakeSupplierProductAliasRepository(clock)
    private val inventory = FakeInventoryReadRepository()
    private val purchases = FakePurchaseReadRepository()
    private val export = ExportUserDataUseCase(
        appConfigurationRepository = configuration,
        businessRepository = businesses,
        productRepository = products,
        supplierRepository = suppliers,
        unitRepository = units,
        inventoryLocationRepository = locations,
        supplierProductAliasRepository = aliases,
        inventoryReadRepository = inventory,
        purchaseReadRepository = purchases,
        appClock = clock,
    )

    @Test
    fun `solo entrega el libro tras dos snapshots completos iguales`() = runTest {
        prepareBusiness()
        purchases.setAuditEvents(
            BUSINESS_ID,
            listOf(
                BusinessAuditEventRead(
                    auditEventId = AUDIT_ID,
                    purchaseId = null,
                    eventType = AuditEventType.SYNC_RECONCILED,
                    entityType = "business",
                    entityId = BUSINESS_ID.value,
                    occurredAt = NOW,
                ),
            ),
        )

        val result = export()

        assertNotNull(result)
        assertEquals(BUSINESS_ID.value, result?.business?.businessId)
        assertEquals("Bodega estable", result?.business?.legalName)
        assertTrue(result?.purchases.orEmpty().isEmpty())
        assertTrue(result?.stockMovements.orEmpty().isEmpty())
        assertEquals(1, result?.auditEvents?.size)
        assertNull(result?.auditEvents?.single()?.purchaseId)
    }

    @Test
    fun `mutacion continua falla cerrado y no escribe el destino SAF`() = runTest {
        val stored = prepareBusiness()
        var revision = 0
        businesses.findByIdHandler = {
            revision++
            stored.copy(legalName = "Revisión $revision")
        }
        val writer = FakeUserDataExportWriter()
        val write = WriteUserDataExportUseCase(export, writer)

        val result = write("content://exports/ledger.json")

        assertEquals(UserDataExportWriteStatus.SOURCE_UNAVAILABLE, result.status)
        assertNull(result.export)
        assertEquals(4, revision)
        assertTrue(writer.writes.isEmpty())
    }

    private suspend fun prepareBusiness(): Business {
        val business = businesses.create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Bodega estable",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            CostPolicy.NET,
        )
        return business
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-22T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        )
        const val AUDIT_ID = "223e4567-e89b-42d3-a456-426614174000"
    }
}
