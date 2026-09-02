package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.OnboardingProvision
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOnboardingProvisioningRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomOnboardingProvisioningRepository
    private lateinit var businesses: RoomBusinessRepository
    private lateinit var units: RoomUnitRepository

    private val clock = TestClock(Instant.parse("2026-08-08T12:00:00Z"))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java).build()
        repository = RoomOnboardingProvisioningRepository(database, testDispatchers)
        businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
        units = RoomUnitRepository(database.unitDao(), testDispatchers, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedProvisionRecognizesTheSameCompleteGraph() = runBlocking {
        val requested = provision(seed = 1, ruc = "20123456786")

        val first = repository.provision(requested)
        val second = repository.provision(requested)

        assertEquals(requested.business.businessId, first.businessId)
        assertEquals(first, second)
        assertEquals(1, database.businessDao().count())
        assertEquals(
            1,
            database.inventoryLocationDao().countForBusiness(first.businessId.value),
        )
        assertEquals(1, database.unitDao().countForBusiness(first.businessId.value))
    }

    @Test
    fun lateChildConflictRollsBackBusinessAndWarehouse() = runBlocking {
        val requested = provision(seed = 10, ruc = "20123456786")
        val foreignBusiness = business(seed = 90, ruc = "20999999999")
        businesses.create(foreignBusiness)
        units.create(
            requested.defaultUnit.copy(businessId = foreignBusiness.businessId),
        )

        val failure = assertThrows(StorageException::class.java) {
            runBlocking { repository.provision(requested) }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertNull(database.businessDao().findById(requested.business.businessId.value))
        assertNull(
            database.inventoryLocationDao().findById(requested.warehouse.locationId.value),
        )
        assertEquals(1, database.businessDao().count())
        assertEquals(
            foreignBusiness.businessId.value,
            database.unitDao().findById(requested.defaultUnit.unitId.value)?.businessId,
        )
    }

    @Test
    fun reservedIdsRejectSemanticallyDifferentRows() = runBlocking {
        val requested = provision(seed = 40, ruc = "20123456786")
        businesses.create(
            requested.business.copy(
                legalName = "Negocio ajeno",
                ruc = "20999999999",
            ),
        )

        val failure = assertThrows(StorageException::class.java) {
            runBlocking { repository.provision(requested) }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertEquals(1, database.businessDao().count())
        assertEquals(
            0,
            database.inventoryLocationDao().countForBusiness(
                requested.business.businessId.value,
            ),
        )
        assertEquals(0, database.unitDao().countForBusiness(requested.business.businessId.value))
    }

    @Test
    fun legacyBusinessByRucIsRepairedAndRetryDoesNotDuplicateChildren() = runBlocking {
        val legacy = businesses.create(business(seed = 100, ruc = "20123456786"))
        val requested = provision(seed = 20, ruc = legacy.ruc)

        val first = repository.provision(requested)
        val second = repository.provision(requested)

        assertEquals(legacy.businessId, first.businessId)
        assertEquals(first, second)
        assertNull(database.businessDao().findById(requested.business.businessId.value))
        assertEquals(1, database.businessDao().count())
        assertEquals(
            legacy.businessId.value,
            database.inventoryLocationDao().findById(requested.warehouse.locationId.value)
                ?.businessId,
        )
        assertEquals(
            legacy.businessId.value,
            database.unitDao().findById(requested.defaultUnit.unitId.value)?.businessId,
        )
        assertEquals(1, database.inventoryLocationDao().countForBusiness(legacy.businessId.value))
        assertEquals(1, database.unitDao().countForBusiness(legacy.businessId.value))
    }

    @Test
    fun legacyWarehouseWithEquivalentCanonicalWhitespaceIsReused() = runBlocking {
        val legacy = businesses.create(business(seed = 100, ruc = "20123456786"))
        val legacyLocationId = uuid(999).toString()
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = legacyLocationId,
                businessId = legacy.businessId.value,
                name = "Almacén  central",
                createdAt = clock.now().toEpochMilli(),
                updatedAt = clock.now().toEpochMilli(),
            ),
        )
        val requested = provision(seed = 20, ruc = legacy.ruc)

        val result = repository.provision(requested)

        assertEquals(legacy.businessId, result.businessId)
        assertEquals(1, database.inventoryLocationDao().countForBusiness(legacy.businessId.value))
        assertEquals(
            legacyLocationId,
            database.inventoryLocationDao().listForBusiness(legacy.businessId.value).single().locationId,
        )
        assertNull(database.inventoryLocationDao().findById(requested.warehouse.locationId.value))
    }

    private fun provision(seed: Int, ruc: String?) = OnboardingProvision(
        business = business(seed, ruc),
        warehouse = InventoryLocation(
            locationId = LocationId.from(uuid(seed + 1)),
            businessId = BusinessId.from(uuid(seed)),
            name = "Almacén central",
            createdAt = clock.now(),
            updatedAt = clock.now(),
        ),
        defaultUnit = UnitOfMeasure(
            unitId = UnitId.from(uuid(seed + 2)),
            businessId = BusinessId.from(uuid(seed)),
            code = "NIU",
            name = "Unidad",
            symbol = "un",
            createdAt = clock.now(),
            updatedAt = clock.now(),
        ),
    )

    private fun business(seed: Int, ruc: String?) = Business(
        businessId = BusinessId.from(uuid(seed)),
        legalName = "Bodega Lola",
        ruc = ruc,
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
