package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.RemoteCatalogChangeEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.CatalogOutboxConflictResolution
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.RemoteConflictMetadata
import com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCase
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRemoteCatalogApplicationRepositoryTest {
    private lateinit var database: FacturaStockDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java)
            .addCallback(postingPersistenceCallback)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun crossDeviceSemanticReferencesUseLocalUuidsAndPersistIdentityLink() = runBlocking {
        seedBusiness()
        database.unitDao().insert(unit(LOCAL_UNIT_ID, "NIU", "Unidad", "un"))
        database.inventoryLocationDao().insert(location(LOCAL_LOCATION_ID, "Almacén central"))
        val snapshot = productSnapshot(
            inventoryUnit = CatalogUnitSnapshot("NIU", "Unidad", "un", ACTIVE),
            location = CatalogLocationSnapshot("Almacén central", ACTIVE),
        )
        database.remoteSyncDao().insertCatalogChange(remoteChange(1, snapshot))

        val outcome = repository(UuidGenerator { UUID.randomUUID() }).applyPending(
            localBusinessId = localBusinessId,
            cloudBusinessId = cloudBusinessId,
            appliedAt = APPLIED_AT,
        ) as DomainResult.Success

        assertEquals(1, outcome.value.applied)
        val product = database.productDao().findById(REMOTE_PRODUCT_ID)
        assertNotNull(product)
        assertEquals(LOCAL_UNIT_ID, product?.unitId)
        assertEquals(LOCAL_LOCATION_ID, product?.locationId)
        val link = database.catalogSyncLinkDao().findByRemote(
            CLOUD_BUSINESS_ID,
            PRODUCT_ENTITY_TYPE,
            REMOTE_PRODUCT_ID,
        )
        assertEquals(REMOTE_PRODUCT_ID, link?.localEntityId)
        assertEquals(1L, link?.remoteVersion)
    }

    @Test
    fun catalogPullReusesLocationWithEquivalentCanonicalWhitespace() = runBlocking {
        seedBusiness()
        database.unitDao().insert(unit(LOCAL_UNIT_ID, "NIU", "Unidad", "un"))
        database.inventoryLocationDao().insert(location(LOCAL_LOCATION_ID, "Principal  1"))
        val snapshot = productSnapshot(
            inventoryUnit = CatalogUnitSnapshot("NIU", "Unidad", "un", ACTIVE),
            location = CatalogLocationSnapshot("Principal 1", ACTIVE),
        )
        database.remoteSyncDao().insertCatalogChange(remoteChange(1, snapshot))

        val outcome = repository(UuidGenerator { canonicalUuid(999) }).applyPending(
            localBusinessId = localBusinessId,
            cloudBusinessId = cloudBusinessId,
            appliedAt = APPLIED_AT,
        ) as DomainResult.Success

        assertEquals(1, outcome.value.applied)
        assertEquals(0, outcome.value.conflicts)
        assertEquals(1, database.inventoryLocationDao().countForBusiness(LOCAL_BUSINESS_ID))
        assertEquals(
            LOCAL_LOCATION_ID,
            database.productDao().findById(REMOTE_PRODUCT_ID)?.locationId,
        )
    }

    @Test
    fun legacyV1ProductSnapshotPreservesAnExistingLocalSalePrice() = runBlocking {
        seedBusiness()
        database.unitDao().insert(unit(LOCAL_UNIT_ID, "NIU", "Unidad", "un"))
        database.productDao().insert(
            product(
                id = REMOTE_PRODUCT_ID,
                unitId = LOCAL_UNIT_ID,
                version = 1L,
                sku = "SKU-1",
            ).copy(
                name = "Producto cloud",
                normalizedName = "producto cloud",
                createdAt = 2_000L,
                updatedAt = 3_000L,
                salePriceMinorUnits = 650L,
                salePriceCurrencyCode = "PEN",
            ),
        )
        val v2 = productSnapshot(
            inventoryUnit = CatalogUnitSnapshot("NIU", "Unidad", "un", ACTIVE),
        )
        val legacyV1 = v2.replace(
            ",\"salePriceMinorUnits\":null,\"salePriceCurrencyCode\":null",
            "",
        )
        database.remoteSyncDao().insertCatalogChange(remoteChange(1, legacyV1))

        val outcome = repository(UuidGenerator { UUID.randomUUID() }).applyPending(
            localBusinessId = localBusinessId,
            cloudBusinessId = cloudBusinessId,
            appliedAt = APPLIED_AT,
        ) as DomainResult.Success

        assertEquals(1, outcome.value.applied)
        val stored = requireNotNull(database.productDao().findById(REMOTE_PRODUCT_ID))
        assertEquals(650L, stored.salePriceMinorUnits)
        assertEquals("PEN", stored.salePriceCurrencyCode)
    }

    @Test
    fun sameUnitCodeWithDifferentMetadataIsExplicitConflict() = runBlocking {
        seedBusiness()
        database.unitDao().insert(unit(LOCAL_UNIT_ID, "NIU", "Unidad local", "u"))
        val snapshot = productSnapshot(
            inventoryUnit = CatalogUnitSnapshot("NIU", "Unidad cloud", "un", ACTIVE),
        )
        database.remoteSyncDao().insertCatalogChange(remoteChange(1, snapshot))

        val outcome = repository(UuidGenerator { UUID.randomUUID() }).applyPending(
            localBusinessId = localBusinessId,
            cloudBusinessId = cloudBusinessId,
            appliedAt = APPLIED_AT,
        ) as DomainResult.Success

        assertEquals(1, outcome.value.conflicts)
        assertNull(database.productDao().findById(REMOTE_PRODUCT_ID))
        val change = database.remoteSyncDao().findCatalogChange(CLOUD_BUSINESS_ID, 1)
        assertEquals("CONFLICT", change?.applicationStatus)
        assertEquals("SEMANTIC_KEY_COLLISION", change?.conflictCode)
        assertNull(
            database.catalogSyncLinkDao().findByRemote(
                CLOUD_BUSINESS_ID,
                PRODUCT_ENTITY_TYPE,
                REMOTE_PRODUCT_ID,
            ),
        )
    }

    @Test
    fun lateReferenceConstraintRollsBackEveryCreatedReferenceBeforeConflictIsStored() =
        runBlocking {
            seedBusiness()
            val repeatedUuid = UUID.fromString(LOCAL_UNIT_ID)
            val snapshot = productSnapshot(
                inventoryUnit = CatalogUnitSnapshot("NIU", "Unidad", "un", ACTIVE),
                purchaseUnit = CatalogUnitSnapshot("CJA", "Caja", "cja", ACTIVE),
                purchaseFactor = "12",
            )
            database.remoteSyncDao().insertCatalogChange(remoteChange(1, snapshot))

            val outcome = repository(UuidGenerator { repeatedUuid }).applyPending(
                localBusinessId = localBusinessId,
                cloudBusinessId = cloudBusinessId,
                appliedAt = APPLIED_AT,
            ) as DomainResult.Success

            assertEquals(1, outcome.value.conflicts)
            assertEquals(0, database.unitDao().countForBusiness(LOCAL_BUSINESS_ID))
            assertEquals(0, database.inventoryLocationDao().countForBusiness(LOCAL_BUSINESS_ID))
            assertNull(database.productDao().findById(REMOTE_PRODUCT_ID))
            assertEquals(
                "SEMANTIC_KEY_COLLISION",
                database.remoteSyncDao().findCatalogChange(CLOUD_BUSINESS_ID, 1)?.conflictCode,
            )
        }

    @Test
    fun bootstrapSeedsLegacyCatalogOnceAndKeepsRemoteVersionCoherent() = runBlocking {
        seedBusiness()
        database.unitDao().insert(unit(LOCAL_UNIT_ID, "NIU", "Unidad", "un"))
        database.productDao().insert(
            product(
                id = LOCAL_PRODUCT_ID,
                unitId = LOCAL_UNIT_ID,
                version = 7,
                sku = "LEGACY-1",
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = LOCAL_SUPPLIER_ID,
                businessId = LOCAL_BUSINESS_ID,
                legalName = "Proveedor legado",
                ruc = "20123456789",
                createdAt = 1_000,
                updatedAt = 2_000,
                version = 4,
            ),
        )
        val firstProcess = RoomCatalogSyncBootstrapRepository(database, testDispatchers)
        assertEquals(2, firstProcess.ensurePendingSnapshots(localBusinessId))

        // Una instancia nueva representa el siguiente arranque: Room, no memoria del objeto,
        // decide que la semilla ya existe.
        val afterRestart = RoomCatalogSyncBootstrapRepository(database, testDispatchers)
        assertEquals(0, afterRestart.ensurePendingSnapshots(localBusinessId))
        assertEquals(1L, database.productDao().findById(LOCAL_PRODUCT_ID)?.version)
        assertEquals(1L, database.supplierDao().findById(LOCAL_SUPPLIER_ID)?.version)
        val operations = database.outboxOperationDao().observeForBusiness(LOCAL_BUSINESS_ID).first()
        assertEquals(2, operations.size)
        operations.forEach { operation ->
            assertEquals(1L, operation.entityVersion)
            assertEquals(1, operation.payload.countOccurrences("\"expectedVersion\":0"))
            assertEquals(1, operation.payload.countOccurrences("\"targetVersion\":1"))
        }
    }

    @Test
    fun applyRemoteMapsDifferentUuidAndEveryLaterEditUsesRemoteIdentityOnce() = runBlocking {
        exerciseOutboxResolution(CatalogOutboxConflictResolution.APPLY_REMOTE)
    }

    @Test
    fun keepLocalRebindsWholeSuccessorChainAndEveryLaterEditUsesRemoteIdentityOnce() =
        runBlocking {
            exerciseOutboxResolution(CatalogOutboxConflictResolution.KEEP_LOCAL)
        }

    @Test
    fun applyRemoteRejectsDialogV2AfterStoredConflictAdvancedToV3() = runBlocking {
        exerciseStaleOutboxResolution(CatalogOutboxConflictResolution.APPLY_REMOTE)
    }

    @Test
    fun keepLocalRejectsDialogV2AfterStoredConflictAdvancedToV3() = runBlocking {
        exerciseStaleOutboxResolution(CatalogOutboxConflictResolution.KEEP_LOCAL)
    }

    private suspend fun exerciseStaleOutboxResolution(
        resolution: CatalogOutboxConflictResolution,
    ) {
        seedBusiness()
        database.unitDao().insert(unit(LOCAL_UNIT_ID, "NIU", "Unidad", "un"))
        val local = product(
            id = LOCAL_PRODUCT_ID,
            unitId = LOCAL_UNIT_ID,
            version = 1,
            sku = "SHARED-SKU",
        ).copy(name = "Elección local", normalizedName = "elección local", updatedAt = 2_000)
        database.productDao().insert(local)
        val unit = requireNotNull(database.unitDao().findById(LOCAL_UNIT_ID))
        val storedV3Snapshot = conflictSnapshot(
            name = "Elección cloud v3",
            updatedAt = 6_000,
        )
        val stored = CatalogSyncOutbox.product(
            entity = local,
            expectedVersion = 0,
            inventoryUnit = unit,
            purchaseUnit = null,
            location = null,
        ).copy(
            status = OutboxOperationStatus.CONFLICT.name,
            attemptCount = 3,
            lastError = "VERSION_MISMATCH",
            updatedAt = 7_000,
            conflictRemoteEntityId = REMOTE_PRODUCT_ID,
            conflictRemoteVersion = 3,
            conflictRemoteSnapshotPayload = storedV3Snapshot,
            conflictRemoteSyncedAt = 6_000,
            conflictRemoteOrigin = "CLOUD",
            conflictCloudBusinessId = CLOUD_BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
        )
        database.outboxOperationDao().insert(stored)

        // El diálogo se abrió con v2. Mientras permanecía visible, un retry persistió v3 en la
        // misma fila. La acción conserva la revisión vieja y no puede consumir el snapshot nuevo.
        val staleV2 = OutboxOperationView(
            operationId = stored.operationId,
            operationType = SYNC_PRODUCT,
            purchaseId = null,
            status = OutboxOperationStatus.CONFLICT,
            attemptCount = 2,
            lastError = "VERSION_MISMATCH",
            nextAttemptAt = null,
            updatedAt = Instant.ofEpochMilli(5_000),
            conflictRemotePurchaseId = null,
            conflictReceiptId = null,
            conflictRemoteEntity = RemoteConflictMetadata(
                entityId = REMOTE_PRODUCT_ID,
                version = 2,
                snapshotPayload = conflictSnapshot(
                    name = "Elección cloud v2",
                    updatedAt = 4_000,
                ),
                syncedAtMillis = 4_000,
                cloudBusinessId = CLOUD_BUSINESS_ID,
            ),
            entityType = PRODUCT_ENTITY_TYPE,
            entityId = LOCAL_PRODUCT_ID,
            entityVersion = 1,
            businessId = localBusinessId,
            targetCloudBusinessId = cloudBusinessId,
        )

        val result = repository(UuidGenerator { canonicalUuid(999) }).resolveOutboxConflict(
            activeBusinessId = localBusinessId,
            operation = staleV2,
            resolution = resolution,
            actorId = "owner",
            resolvedAt = Instant.ofEpochMilli(8_000),
        )

        assertEquals(AccountError.Conflict, (result as DomainResult.Failure).error)
        assertEquals("Elección local", database.productDao().findById(LOCAL_PRODUCT_ID)?.name)
        val unchanged = requireNotNull(database.outboxOperationDao().findById(stored.operationId))
        assertEquals(OutboxOperationStatus.CONFLICT.name, unchanged.status)
        assertEquals(3L, unchanged.conflictRemoteVersion)
        assertEquals(storedV3Snapshot, unchanged.conflictRemoteSnapshotPayload)
        assertNull(
            database.catalogSyncLinkDao().findByLocal(
                LOCAL_BUSINESS_ID,
                PRODUCT_ENTITY_TYPE,
                LOCAL_PRODUCT_ID,
            ),
        )
        assertEquals(
            0,
            database.auditEventDao().listForEntity(
                LOCAL_BUSINESS_ID,
                PRODUCT_ENTITY_TYPE,
                LOCAL_PRODUCT_ID,
            ).size,
        )
    }

    private suspend fun exerciseOutboxResolution(resolution: CatalogOutboxConflictResolution) {
        seedBusiness()
        database.unitDao().insert(unit(LOCAL_UNIT_ID, "NIU", "Unidad", "un"))
        val localV2 = product(
            id = LOCAL_PRODUCT_ID,
            unitId = LOCAL_UNIT_ID,
            version = 2,
            sku = "SHARED-SKU",
        ).copy(name = "Elección local", normalizedName = "elección local", updatedAt = 4_000)
        database.productDao().insert(localV2)
        val unit = requireNotNull(database.unitDao().findById(LOCAL_UNIT_ID))
        val localV1Operation = CatalogSyncOutbox.product(
            entity = localV2.copy(version = 1, updatedAt = 2_000),
            expectedVersion = 0,
            inventoryUnit = unit,
            purchaseUnit = null,
            location = null,
        ).copy(
            status = OutboxOperationStatus.CONFLICT.name,
            attemptCount = 1,
            lastError = "CONFLICT",
            conflictRemoteEntityId = REMOTE_PRODUCT_ID,
            conflictRemoteVersion = 1,
            conflictRemoteSnapshotPayload = conflictSnapshot(),
            conflictRemoteSyncedAt = 3_000,
            conflictRemoteOrigin = "CLOUD",
            conflictCloudBusinessId = CLOUD_BUSINESS_ID,
            targetCloudBusinessId = CLOUD_BUSINESS_ID,
        )
        val localV2Operation = CatalogSyncOutbox.product(
            entity = localV2,
            expectedVersion = 1,
            inventoryUnit = unit,
            purchaseUnit = null,
            location = null,
        ).copy(targetCloudBusinessId = CLOUD_BUSINESS_ID)
        database.outboxOperationDao().insert(localV1Operation)
        database.outboxOperationDao().insert(localV2Operation)
        val remote = RemoteConflictMetadata(
            entityId = REMOTE_PRODUCT_ID,
            version = 1,
            snapshotPayload = conflictSnapshot(),
            syncedAtMillis = 3_000,
            cloudBusinessId = CLOUD_BUSINESS_ID,
        )
        val view = OutboxOperationView(
            operationId = localV1Operation.operationId,
            operationType = SYNC_PRODUCT,
            purchaseId = null,
            status = OutboxOperationStatus.CONFLICT,
            attemptCount = 1,
            lastError = "CONFLICT",
            nextAttemptAt = null,
            updatedAt = Instant.ofEpochMilli(localV1Operation.updatedAt),
            conflictRemotePurchaseId = null,
            conflictReceiptId = null,
            conflictRemoteEntity = remote,
            entityType = PRODUCT_ENTITY_TYPE,
            entityId = LOCAL_PRODUCT_ID,
            entityVersion = 1,
            businessId = localBusinessId,
            targetCloudBusinessId = cloudBusinessId,
        )
        val sequence = generateSequence(100L) { it + 1 }.iterator()
        val app = repository(UuidGenerator { canonicalUuid(sequence.next()) })
        val persisted = requireNotNull(
            database.outboxOperationDao().findById(localV1Operation.operationId),
        )
        assertEquals(view.status.name, persisted.status)
        assertEquals(view.businessId?.value, persisted.businessId)
        assertEquals(view.operationType, persisted.operationType)
        assertEquals(view.entityType, persisted.entityType)
        assertEquals(view.entityId, persisted.entityId)
        assertEquals(view.entityVersion, persisted.entityVersion)
        assertEquals(view.attemptCount, persisted.attemptCount)
        assertEquals(view.updatedAt.toEpochMilli(), persisted.updatedAt)
        assertEquals(view.targetCloudBusinessId?.value, persisted.targetCloudBusinessId)
        assertEquals(remote.entityId, persisted.conflictRemoteEntityId)
        assertEquals(remote.version, persisted.conflictRemoteVersion)
        assertEquals(remote.snapshotPayload, persisted.conflictRemoteSnapshotPayload)
        assertEquals(remote.syncedAtMillis, persisted.conflictRemoteSyncedAt)
        assertEquals(remote.origin, persisted.conflictRemoteOrigin)
        assertEquals(remote.cloudBusinessId, persisted.conflictCloudBusinessId)

        val resolutionResult = app.resolveOutboxConflict(
            activeBusinessId = localBusinessId,
            operation = view,
            resolution = resolution,
            actorId = "owner",
            resolvedAt = Instant.ofEpochMilli(10_000),
        )
        val resolved = resolutionResult as? DomainResult.Success
            ?: error("La resolución $resolution falló: $resolutionResult")
        assertEquals(true, resolved.value)
        val link = database.catalogSyncLinkDao().findByLocal(
            LOCAL_BUSINESS_ID,
            PRODUCT_ENTITY_TYPE,
            LOCAL_PRODUCT_ID,
        )
        assertEquals(REMOTE_PRODUCT_ID, link?.remoteEntityId)

        val transport = RecordingAcknowledgingTransport()
        val clock = TestClock(Instant.ofEpochMilli(20_000))
        val outboxRepository = RoomPurchaseBackupOutboxRepository(
            database = database,
            outbox = database.outboxOperationDao(),
            auditEvents = database.auditEventDao(),
            uuids = UuidGenerator { canonicalUuid(sequence.next()) },
            dispatchers = testDispatchers,
        )
        val processor = ProcessPurchaseBackupOutboxUseCase(
            outbox = outboxRepository,
            transport = transport,
            clock = clock,
            uuidGenerator = UuidGenerator { canonicalUuid(sequence.next()) },
            observability = DisabledProductionObservability,
        )

        if (resolution == CatalogOutboxConflictResolution.KEEP_LOCAL) {
            processor(targetCloudBusinessId = cloudBusinessId)
            val rebound = database.outboxOperationDao().findById(localV2Operation.operationId)
            assertEquals(OutboxOperationStatus.COMPLETED.name, rebound?.status)
            assertEquals(REMOTE_PRODUCT_ID, rebound?.remoteEntityId)
        }

        val products = RoomProductRepository(
            database = database,
            productDao = database.productDao(),
            dispatchers = testDispatchers,
            clock = clock,
        )
        clock.advanceSeconds(1)
        val beforeEdit = requireNotNull(products.findById(ProductId.parse(LOCAL_PRODUCT_ID)!!))
        assertEquals(true, products.update(beforeEdit.copy(name = "Edición posterior")))
        processor(targetCloudBusinessId = cloudBusinessId)
        processor( // reintento/reinicio sin filas pendientes: no vuelve a enviar.
            targetCloudBusinessId = cloudBusinessId,
        )

        val expectedSends = if (resolution == CatalogOutboxConflictResolution.KEEP_LOCAL) 2 else 1
        assertEquals(expectedSends, transport.envelopes.size)
        transport.envelopes.forEach { envelope ->
            assertEquals(LOCAL_PRODUCT_ID, envelope.entityId)
            assertEquals(REMOTE_PRODUCT_ID, envelope.remoteEntityId)
            assertEquals(
                "sync-product:v2:$REMOTE_PRODUCT_ID:${envelope.entityVersion}",
                envelope.idempotencyKey,
            )
            assertEquals(1, envelope.payload.countOccurrences("\"entityId\":\"$REMOTE_PRODUCT_ID\""))
        }
        assertEquals(
            transport.envelopes.last().entityVersion,
            database.catalogSyncLinkDao().findByLocal(
                LOCAL_BUSINESS_ID,
                PRODUCT_ENTITY_TYPE,
                LOCAL_PRODUCT_ID,
            )?.remoteVersion,
        )
    }

    private fun repository(uuids: UuidGenerator) = RoomRemoteCatalogApplicationRepository(
        database = database,
        uuids = uuids,
        dispatchers = testDispatchers,
    )

    private suspend fun seedBusiness() {
        database.businessDao().insert(
            BusinessEntity(
                businessId = LOCAL_BUSINESS_ID,
                legalName = "Negocio local",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                createdAt = 1_000,
                boundLegacyOperationCount = 0,
            ),
        )
    }

    private fun unit(id: String, code: String, name: String, symbol: String) = UnitEntity(
        unitId = id,
        businessId = LOCAL_BUSINESS_ID,
        code = code,
        name = name,
        symbol = symbol,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private fun location(id: String, name: String) = InventoryLocationEntity(
        locationId = id,
        businessId = LOCAL_BUSINESS_ID,
        name = name,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private fun product(
        id: String,
        unitId: String,
        version: Long,
        sku: String? = "SKU-1",
    ) = ProductEntity(
        productId = id,
        businessId = LOCAL_BUSINESS_ID,
        unitId = unitId,
        name = "Producto local",
        sku = sku,
        normalizedName = "producto local",
        createdAt = 1_000,
        updatedAt = 2_000,
        version = version,
    )

    private fun productSnapshot(
        inventoryUnit: CatalogUnitSnapshot,
        purchaseUnit: CatalogUnitSnapshot? = null,
        purchaseFactor: String? = null,
        location: CatalogLocationSnapshot? = null,
    ): String = CatalogSnapshotCodec.encode(
        CatalogProductSnapshot(
            name = "Producto cloud",
            sku = "SKU-1",
            barcode = null,
            inventoryUnit = inventoryUnit,
            purchaseUnit = purchaseUnit,
            purchaseFactor = purchaseFactor,
            location = location,
            status = ACTIVE,
            createdAt = 2_000,
            updatedAt = 3_000,
        ),
    )

    private fun conflictSnapshot(
        name: String = "Elección cloud",
        updatedAt: Long = 3_000,
    ): String = CatalogSnapshotCodec.encode(
        CatalogProductSnapshot(
            name = name,
            sku = "SHARED-SKU",
            barcode = null,
            inventoryUnit = CatalogUnitSnapshot("NIU", "Unidad", "un", ACTIVE),
            purchaseUnit = null,
            purchaseFactor = null,
            location = null,
            status = ACTIVE,
            createdAt = 2_000,
            updatedAt = updatedAt,
        ),
    )

    private fun remoteChange(seq: Long, snapshot: String) = RemoteCatalogChangeEntity(
        cloudBusinessId = CLOUD_BUSINESS_ID,
        seq = seq,
        entityType = PRODUCT_ENTITY_TYPE,
        remoteEntityId = REMOTE_PRODUCT_ID,
        remoteVersion = 1,
        mutation = "UPSERT",
        snapshotPayload = snapshot,
        snapshotSha256 = snapshot.sha256(),
        receiptId = "receipt-$seq",
        syncedAtMillis = 3_000,
        receivedAt = 4_000,
    )

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun String.countOccurrences(value: String): Int = windowed(value.length)
        .count { it == value }

    private fun canonicalUuid(seed: Long): UUID = UUID.fromString(
        "00000000-0000-4000-8000-%012d".format(seed),
    )

    private val localBusinessId = BusinessId.parse(LOCAL_BUSINESS_ID)!!
    private val cloudBusinessId = BusinessId.parse(CLOUD_BUSINESS_ID)!!

    private companion object {
        val ACTIVE = CatalogStatus.ACTIVE.name
        const val LOCAL_BUSINESS_ID = "00000000-0000-4000-8000-000000000001"
        const val CLOUD_BUSINESS_ID = "00000000-0000-4000-8000-000000000002"
        const val LOCAL_UNIT_ID = "00000000-0000-4000-8000-000000000003"
        const val LOCAL_LOCATION_ID = "00000000-0000-4000-8000-000000000004"
        const val REMOTE_PRODUCT_ID = "00000000-0000-4000-8000-000000000005"
        const val LOCAL_PRODUCT_ID = "00000000-0000-4000-8000-000000000006"
        const val LOCAL_SUPPLIER_ID = "00000000-0000-4000-8000-000000000007"
        val APPLIED_AT: Instant = Instant.ofEpochMilli(5_000)
    }
}

private class RecordingAcknowledgingTransport : PurchaseBackupTransport {
    val envelopes = mutableListOf<BackupEnvelope>()
    override val configured: Boolean = true

    override suspend fun send(envelope: BackupEnvelope): BackupTransportResult {
        envelopes += envelope
        return BackupTransportResult.Acknowledged(
            receiptId = "receipt-${envelope.entityVersion}",
            echoedIdempotencyKey = envelope.idempotencyKey,
        )
    }
}
