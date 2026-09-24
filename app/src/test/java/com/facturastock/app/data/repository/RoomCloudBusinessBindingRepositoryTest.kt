package com.facturastock.app.data.repository

import com.facturastock.app.data.local.writableSql
import com.facturastock.app.data.local.newFacturaStockDatabase
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CatalogSyncLinkEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RoomCloudBusinessBindingRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomCloudBusinessBindingRepository

    @Before
    fun setUp() = runBlocking {
        database = tempFolder.newFacturaStockDatabase()
        database.businessDao().insert(
            BusinessEntity(
                businessId = LOCAL.value,
                legalName = "Negocio tenant pin",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        repository = RoomCloudBusinessBindingRepository(
            database = database,
            bindings = database.cloudBusinessBindingDao(),
            outbox = database.outboxOperationDao(),
            catalogLinks = database.catalogSyncLinkDao(),
            dispatchers = TEST_DISPATCHERS,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun firstLinkBackfillsNeverAttemptedRowsAndPinsEveryFutureOperation() = runBlocking {
        database.outboxOperationDao().insert(operation(1, "SYNC_PRODUCT", "PRODUCT"))

        assertEquals(
            CloudBusinessBindingResult.Bound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )
        assertEquals(
            CLOUD_X.value,
            database.outboxOperationDao().findById(operationId(1))?.targetCloudBusinessId,
        )

        database.outboxOperationDao().insert(operation(2, "SYNC_SUPPLIER", "SUPPLIER"))
        assertEquals(
            CLOUD_X.value,
            database.outboxOperationDao().findById(operationId(2))?.targetCloudBusinessId,
        )
        assertEquals(
            CloudBusinessBindingResult.AlreadyBound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(20L)),
        )
    }

    @Test
    fun completedHistoryAndFutureEditsCannotBeRetargeted() = runBlocking {
        database.outboxOperationDao().insert(operation(1, "SYNC_PRODUCT", "PRODUCT"))
        assertEquals(
            CloudBusinessBindingResult.Bound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )
        val dao = database.outboxOperationDao()
        val token = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertEquals(
            1,
            dao.claim(
                operationId = operationId(1),
                targetCloudBusinessId = CLOUD_X.value,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = 11L,
                claimToken = token,
                claimLeaseUntil = 100L,
            ),
        )
        assertEquals(
            1,
            dao.complete(
                operationId = operationId(1),
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                completedStatus = OutboxOperationStatus.COMPLETED.name,
                completedAt = 12L,
                claimToken = token,
            ),
        )

        assertEquals(
            CloudBusinessBindingResult.LocalBusinessAlreadyBound,
            repository.bindOnce(LOCAL, CLOUD_Y, Instant.ofEpochMilli(20L)),
        )
        database.outboxOperationDao().insert(operation(2, "SYNC_PRODUCT", "PRODUCT"))
        assertEquals(
            CLOUD_X.value,
            dao.findById(operationId(2))?.targetCloudBusinessId,
        )
    }

    @Test
    fun attemptedOrProcessingLegacyRowBlocksInferenceAndRemainsUnpinned() = runBlocking {
        database.outboxOperationDao().insert(operation(1, "SYNC_PRODUCT", "PRODUCT"))
        database.writableSql.execSQL(
            "UPDATE outbox_operations SET status='PROCESSING', attemptCount=1, " +
                "claimToken='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', claimLeaseUntil=999 " +
                "WHERE operationId='${operationId(1)}'",
        )

        assertEquals(
            CloudBusinessBindingResult.LegacyDestinationUnknown,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )
        assertNull(database.cloudBusinessBindingDao().findByLocal(LOCAL.value))
        assertNull(database.outboxOperationDao().findById(operationId(1))?.targetCloudBusinessId)
    }

    @Test
    fun ambiguousLegacyLocationNamesBlockFirstBinding() = runBlocking {
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = operationId(1),
                businessId = LOCAL.value,
                name = "Depósito norte",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = operationId(2),
                businessId = LOCAL.value,
                name = "  DEPÓSITO   norte  ",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertEquals(
            CloudBusinessBindingResult.AmbiguousInventoryLocations,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )
        assertNull(database.cloudBusinessBindingDao().findByLocal(LOCAL.value))
    }

    @Test
    fun ambiguousLegacyLocationNamesAlsoBlockExistingBinding() = runBlocking {
        assertEquals(
            CloudBusinessBindingResult.Bound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = operationId(1),
                businessId = LOCAL.value,
                name = "Principal 1",
                createdAt = 11L,
                updatedAt = 11L,
            ),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(
                locationId = operationId(2),
                businessId = LOCAL.value,
                name = "Principal  1",
                createdAt = 12L,
                updatedAt = 12L,
            ),
        )

        assertEquals(
            CloudBusinessBindingResult.AmbiguousInventoryLocations,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(20L)),
        )
        assertFalse(repository.matches(LOCAL, CLOUD_X))
    }

    @Test
    fun legacyCatalogLinkAllowsOnlyItsExistingTenant() = runBlocking {
        database.catalogSyncLinkDao().insert(
            CatalogSyncLinkEntity(
                localBusinessId = LOCAL.value,
                cloudBusinessId = CLOUD_X.value,
                entityType = "PRODUCT",
                localEntityId = operationId(7),
                remoteEntityId = operationId(8),
                remoteVersion = 1L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertEquals(
            CloudBusinessBindingResult.LocalBusinessAlreadyBound,
            repository.bindOnce(LOCAL, CLOUD_Y, Instant.ofEpochMilli(10L)),
        )
        assertEquals(
            CloudBusinessBindingResult.Bound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(11L)),
        )
    }

    @Test
    fun sqlGuardRejectsExplicitTargetDifferentFromBinding() = runBlocking {
        assertEquals(
            CloudBusinessBindingResult.Bound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                database.outboxOperationDao().insert(
                    operation(1, "SYNC_PRODUCT", "PRODUCT").copy(
                        targetCloudBusinessId = CLOUD_Y.value,
                    ),
                )
            }
        }
        Unit
    }

    @Test
    fun directBindingDeleteIsRejectedWhileLocalBusinessExists() = runBlocking {
        assertEquals(
            CloudBusinessBindingResult.Bound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )

        assertThrows(Exception::class.java) {
            database.writableSql.execSQL(
                "DELETE FROM cloud_business_bindings WHERE localBusinessId='${LOCAL.value}'",
            )
        }
        assertEquals(
            CLOUD_X.value,
            database.cloudBusinessBindingDao().findByLocal(LOCAL.value)?.cloudBusinessId,
        )
    }

    @Test
    fun deletingParentBusinessCascadesBindingAndOutbox() = runBlocking {
        database.outboxOperationDao().insert(operation(1, "SYNC_PRODUCT", "PRODUCT"))
        assertEquals(
            CloudBusinessBindingResult.Bound,
            repository.bindOnce(LOCAL, CLOUD_X, Instant.ofEpochMilli(10L)),
        )

        assertEquals(1, database.businessDao().deleteById(LOCAL.value))

        assertNull(database.cloudBusinessBindingDao().findByLocal(LOCAL.value))
        assertNull(database.outboxOperationDao().findById(operationId(1)))
    }

    private fun operation(index: Int, type: String, entityType: String) = OutboxOperationEntity(
        operationId = operationId(index),
        businessId = LOCAL.value,
        idempotencyKey = "tenant-pin-$index",
        operationType = type,
        payload = "{\"version\":1}",
        status = OutboxOperationStatus.PENDING.name,
        createdAt = 1L,
        updatedAt = 1L,
        payloadVersion = 1,
        entityType = entityType,
        entityId = operationId(index),
        entityVersion = 1L,
    )

    private companion object {
        val LOCAL = requireNotNull(BusinessId.parse("11111111-1111-4111-8111-111111111111"))
        val CLOUD_X = requireNotNull(BusinessId.parse("22222222-2222-4222-8222-222222222222"))
        val CLOUD_Y = requireNotNull(BusinessId.parse("33333333-3333-4333-8333-333333333333"))
        val TEST_DISPATCHERS = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

        fun operationId(index: Int): String =
            "44444444-4444-4444-8444-%012d".format(index)
    }
}
