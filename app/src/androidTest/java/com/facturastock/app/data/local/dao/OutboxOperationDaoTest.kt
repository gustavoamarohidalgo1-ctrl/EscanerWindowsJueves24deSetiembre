package com.facturastock.app.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.domain.model.OutboxOperationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contrato CAS de la outbox contra Room real: el claim es único, completar/fallar exige el
 * token del intento y la recuperación solo toca claims sin lease o con lease vencido.
 */
@RunWith(AndroidJUnit4::class)
class OutboxOperationDaoTest {
    private lateinit var database: FacturaStockDatabase

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java)
            .addCallback(postingPersistenceCallback)
            .build()
        database.businessDao().insert(
            BusinessEntity(
                businessId = BUSINESS_ID,
                legalName = "Negocio outbox de prueba",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun secondConcurrentClaimLosesAndOnlyTheOwnerTokenCompletes() = runBlocking {
        val dao = database.outboxOperationDao()
        dao.insert(operation(OPERATION_ID))
        val leaseUntil = NOW + LEASE_MILLIS

        assertEquals(1, dao.claim(OPERATION_ID, PENDING, PROCESSING, NOW, TOKEN_A, leaseUntil))
        // Un segundo worker llega tarde: la operación ya no es reclamable.
        assertEquals(0, dao.claim(OPERATION_ID, PENDING, PROCESSING, NOW, TOKEN_B, leaseUntil))

        // Un token ajeno no cierra el intento: cero filas en complete y en fail.
        assertEquals(0, dao.complete(OPERATION_ID, PROCESSING, COMPLETED, NOW + 1, TOKEN_B))
        assertEquals(
            0,
            dao.fail(OPERATION_ID, PROCESSING, FAILED, "HTTP_500", null, NOW + 1, TOKEN_B),
        )

        assertEquals(1, dao.complete(OPERATION_ID, PROCESSING, COMPLETED, NOW + 2, TOKEN_A))
        val completed = requireNotNull(dao.findById(OPERATION_ID))
        assertEquals(COMPLETED, completed.status)
        assertEquals(NOW + 2, completed.completedAt)
        assertNull(completed.claimToken)
        assertNull(completed.claimLeaseUntil)
        assertEquals(1, completed.attemptCount)
    }

    @Test
    fun releaseReturnsPendingWithoutConsumingTheAttempt() = runBlocking {
        val dao = database.outboxOperationDao()
        dao.insert(operation(OPERATION_ID))
        assertEquals(1, dao.claim(OPERATION_ID, PENDING, PROCESSING, NOW, TOKEN_A, NOW + LEASE_MILLIS))

        assertEquals(0, dao.release(OPERATION_ID, PROCESSING, PENDING, TOKEN_B, NOW + 1))
        assertEquals(1, dao.release(OPERATION_ID, PROCESSING, PENDING, TOKEN_A, NOW + 1))

        val row = requireNotNull(dao.findById(OPERATION_ID))
        assertEquals(PENDING, row.status)
        assertEquals(0, row.attemptCount)
        assertNull(row.claimToken)
    }

    @Test
    fun recoveryReclaimsExpiredOrLegacyClaimsButRespectsALiveLease() = runBlocking {
        val dao = database.outboxOperationDao()
        // Claim con lease vigente: la recuperación no puede robarlo.
        dao.insert(operation(OPERATION_ID))
        assertEquals(1, dao.claim(OPERATION_ID, PENDING, PROCESSING, NOW, TOKEN_A, NOW + LEASE_MILLIS))
        // Claim legado anterior a v15: PROCESSING sin token ni lease, siempre recuperable.
        dao.insert(operation(OPERATION_ID_2))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE outbox_operations SET status = 'PROCESSING', " +
                "attemptCount = attemptCount + 1, claimToken = NULL, claimLeaseUntil = NULL " +
                "WHERE operationId = ?",
            arrayOf(OPERATION_ID_2),
        )

        // Solo el claim legado (lease NULL) se recupera de inmediato; el lease vigente no.
        assertEquals(1, dao.recoverInterrupted(PROCESSING, PENDING, NOW + 1))
        assertEquals(PROCESSING, dao.findById(OPERATION_ID)?.status)
        assertEquals(PENDING, dao.findById(OPERATION_ID_2)?.status)

        // Con el lease vencido, el claim restante pasa a PENDING conservando su intento.
        assertEquals(1, dao.recoverInterrupted(PROCESSING, PENDING, NOW + LEASE_MILLIS + 1))
        val recovered = requireNotNull(dao.findById(OPERATION_ID))
        assertEquals(PENDING, recovered.status)
        assertEquals(1, recovered.attemptCount)
        assertNull(recovered.claimToken)
        assertNull(recovered.claimLeaseUntil)
    }

    @Test
    fun nextAttemptAndLeaseQueriesFeedTheFollowUpScheduling() = runBlocking {
        val dao = database.outboxOperationDao()
        dao.insert(operation(OPERATION_ID, nextAttemptAt = NOW + 30_000L))
        dao.insert(operation(OPERATION_ID_2, nextAttemptAt = NOW))
        assertEquals(NOW + 30_000L, dao.findNextAttemptAt(PENDING, NOW))

        assertEquals(1, dao.claim(OPERATION_ID_2, PENDING, PROCESSING, NOW, TOKEN_A, NOW + LEASE_MILLIS))
        assertEquals(NOW + 30_000L, dao.findNextAttemptAt(PENDING, NOW))
        assertEquals(NOW + LEASE_MILLIS, dao.findNextClaimLeaseExpiry(PROCESSING, NOW))

        dao.complete(OPERATION_ID_2, PROCESSING, COMPLETED, NOW + 1, TOKEN_A)
        assertNull(dao.findNextClaimLeaseExpiry(PROCESSING, NOW))
    }

    @Test
    fun oldestOutstandingQueryIncludesManualFailuresAndExcludesTerminalRows() = runBlocking {
        database.businessDao().insert(
            BusinessEntity(
                businessId = BUSINESS_ID_2,
                legalName = "Segundo negocio outbox",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(
                localBusinessId = BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_X,
                createdAt = NOW,
                boundLegacyOperationCount = 0,
            ),
        )
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(
                localBusinessId = BUSINESS_ID_2,
                cloudBusinessId = CLOUD_BUSINESS_Y,
                createdAt = NOW,
                boundLegacyOperationCount = 0,
            ),
        )
        val dao = database.outboxOperationDao()
        val openStatuses = listOf(PENDING, PROCESSING, FAILED, CONFLICT)
        dao.insert(
            operation(OPERATION_ID, createdAt = NOW - 100L).copy(
                targetCloudBusinessId = CLOUD_BUSINESS_X,
            ),
        )
        dao.insert(
            operation(
                OPERATION_ID_2,
                businessId = BUSINESS_ID_2,
                createdAt = NOW - 200L,
            ).copy(
                status = CONFLICT,
                targetCloudBusinessId = CLOUD_BUSINESS_Y,
            ),
        )
        dao.insert(
            operation(OPERATION_ID_3, createdAt = NOW - 300L).copy(
                status = COMPLETED,
                completedAt = NOW - 250L,
                updatedAt = NOW - 250L,
            ),
        )

        assertEquals(
            NOW - 200L,
            dao.findOldestOutstandingCreatedAt(
                openStatuses = openStatuses,
                targetCloudBusinessId = CLOUD_BUSINESS_Y,
            ),
        )
        assertEquals(
            NOW - 100L,
            dao.findOldestOutstandingCreatedAt(
                openStatuses = openStatuses,
                targetCloudBusinessId = CLOUD_BUSINESS_X,
            ),
        )
    }

    @Test
    fun targetPinnedByRoomScopesSelectionAndClaimCas() = runBlocking {
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(
                localBusinessId = BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_X,
                createdAt = NOW,
                boundLegacyOperationCount = 0,
            ),
        )
        val dao = database.outboxOperationDao()
        dao.insert(operation(OPERATION_ID))

        assertEquals(CLOUD_BUSINESS_X, dao.findById(OPERATION_ID)?.targetCloudBusinessId)
        assertTrue(
            dao.listCausallyReady(
                pendingStatus = PENDING,
                targetCloudBusinessId = CLOUD_BUSINESS_Y,
                completedStatus = COMPLETED,
                purchaseOperationType = SYNC_PURCHASE,
                voidOperationType = SYNC_PURCHASE_VOID,
                now = NOW,
                limit = 10,
            ).isEmpty(),
        )
        assertEquals(
            listOf(OPERATION_ID),
            dao.listCausallyReady(
                pendingStatus = PENDING,
                targetCloudBusinessId = CLOUD_BUSINESS_X,
                completedStatus = COMPLETED,
                purchaseOperationType = SYNC_PURCHASE,
                voidOperationType = SYNC_PURCHASE_VOID,
                now = NOW,
                limit = 10,
            ).map { it.operationId },
        )
        assertEquals(
            0,
            dao.claim(
                operationId = OPERATION_ID,
                pendingStatus = PENDING,
                claimedStatus = PROCESSING,
                claimedAt = NOW,
                claimToken = TOKEN_A,
                claimLeaseUntil = NOW + LEASE_MILLIS,
                targetCloudBusinessId = CLOUD_BUSINESS_Y,
            ),
        )
        assertEquals(
            1,
            dao.claim(
                operationId = OPERATION_ID,
                pendingStatus = PENDING,
                claimedStatus = PROCESSING,
                claimedAt = NOW,
                claimToken = TOKEN_A,
                claimLeaseUntil = NOW + LEASE_MILLIS,
                targetCloudBusinessId = CLOUD_BUSINESS_X,
            ),
        )
    }

    @Test
    fun causallyReadyVoidRequiresDurablyCompletedPurchasePost() = runBlocking {
        insertPurchaseFixture()
        val dao = database.outboxOperationDao()
        dao.insert(
            operation(
                operationId = OPERATION_ID,
                purchaseId = PURCHASE_ID,
                operationType = SYNC_PURCHASE,
            ),
        )
        dao.insert(
            operation(
                operationId = OPERATION_ID_2,
                purchaseId = PURCHASE_ID,
                operationType = SYNC_PURCHASE_VOID,
                createdAt = NOW + 1,
            ),
        )
        dao.insert(
            operation(
                operationId = OPERATION_ID_3,
                purchaseId = PURCHASE_ID_2,
                operationType = SYNC_PURCHASE,
                createdAt = NOW + 2,
            ),
        )

        assertEquals(
            false,
            dao.isPurchasePostCompleted(
                businessId = BUSINESS_ID,
                purchaseId = PURCHASE_ID,
                purchaseOperationType = SYNC_PURCHASE,
                completedStatus = COMPLETED,
            ),
        )
        assertEquals(
            listOf(OPERATION_ID, OPERATION_ID_3),
            dao.listCausallyReady(
                pendingStatus = PENDING,
                completedStatus = COMPLETED,
                purchaseOperationType = SYNC_PURCHASE,
                voidOperationType = SYNC_PURCHASE_VOID,
                now = NOW + 2,
                limit = 2,
            ).map { it.operationId },
        )

        assertEquals(1, dao.claim(OPERATION_ID, PENDING, PROCESSING, NOW + 2, TOKEN_A, NOW + LEASE_MILLIS))
        assertEquals(1, dao.complete(OPERATION_ID, PROCESSING, COMPLETED, NOW + 3, TOKEN_A))

        assertTrue(
            dao.isPurchasePostCompleted(
                businessId = BUSINESS_ID,
                purchaseId = PURCHASE_ID,
                purchaseOperationType = SYNC_PURCHASE,
                completedStatus = COMPLETED,
            ),
        )
        assertEquals(
            listOf(OPERATION_ID_2, OPERATION_ID_3),
            dao.listCausallyReady(
                pendingStatus = PENDING,
                completedStatus = COMPLETED,
                purchaseOperationType = SYNC_PURCHASE,
                voidOperationType = SYNC_PURCHASE_VOID,
                now = NOW + 3,
                limit = 10,
            ).map { it.operationId },
        )
    }

    @Test
    fun catalogVersionCannotSkipAnEarlierOpenVersionEvenDuringClaimRace() = runBlocking {
        val dao = database.outboxOperationDao()
        dao.insert(
            operation(
                operationId = OPERATION_ID,
                operationType = SYNC_PRODUCT,
                entityType = "PRODUCT",
                entityId = PRODUCT_ID,
                entityVersion = 1,
                nextAttemptAt = NOW + 10,
            ),
        )
        dao.insert(
            operation(
                operationId = OPERATION_ID_2,
                operationType = SYNC_PRODUCT,
                entityType = "PRODUCT",
                entityId = PRODUCT_ID,
                entityVersion = 2,
                createdAt = NOW + 1,
            ),
        )

        assertTrue(
            dao.listCausallyReady(
                pendingStatus = PENDING,
                completedStatus = COMPLETED,
                purchaseOperationType = SYNC_PURCHASE,
                voidOperationType = SYNC_PURCHASE_VOID,
                now = NOW + 1,
                limit = 10,
            ).isEmpty(),
        )
        assertEquals(
            0,
            dao.claim(OPERATION_ID_2, PENDING, PROCESSING, NOW + 1, TOKEN_A, NOW + LEASE_MILLIS),
        )

        assertEquals(
            1,
            dao.claim(OPERATION_ID, PENDING, PROCESSING, NOW + 10, TOKEN_A, NOW + LEASE_MILLIS),
        )
        assertEquals(1, dao.complete(OPERATION_ID, PROCESSING, COMPLETED, NOW + 11, TOKEN_A))
        assertEquals(
            listOf(OPERATION_ID_2),
            dao.listCausallyReady(
                pendingStatus = PENDING,
                completedStatus = COMPLETED,
                purchaseOperationType = SYNC_PURCHASE,
                voidOperationType = SYNC_PURCHASE_VOID,
                now = NOW + 11,
                limit = 10,
            ).map { it.operationId },
        )
    }

    @Test
    fun conflictResolutionKeepsRemoteIdentityAndIsATerminalCas() = runBlocking {
        insertPurchaseFixture()
        val dao = database.outboxOperationDao()
        dao.insert(
            operation(
                OPERATION_ID,
                purchaseId = PURCHASE_ID,
                operationType = SYNC_PURCHASE,
            ),
        )
        dao.insert(
            operation(
                OPERATION_ID_2,
                purchaseId = PURCHASE_ID,
                operationType = SYNC_PURCHASE_VOID,
                createdAt = NOW + 1,
            ),
        )
        assertEquals(1, dao.claim(OPERATION_ID, PENDING, PROCESSING, NOW, TOKEN_A, NOW + LEASE_MILLIS))
        assertEquals(
            1,
            dao.fail(
                OPERATION_ID, PROCESSING, CONFLICT, "ALREADY_EXISTS", null, NOW + 1, TOKEN_A,
                conflictRemotePurchaseId = REMOTE_PURCHASE_ID,
                conflictReceiptId = "receipt-remoto-1",
            ),
        )

        // Solo una operación en CONFLICT puede resolverse; la fila queda terminal y limpia
        // de claim, conservando la identidad remota como contexto de la decisión.
        assertEquals(
            0,
            dao.resolveConflictKeepRemote(
                OPERATION_ID,
                CLOUD_BUSINESS_X,
                CONFLICT,
                RESOLVED,
                NOW + 2,
            ),
        )
        assertEquals(CONFLICT, dao.findById(OPERATION_ID)?.status)
        assertEquals(
            1,
            dao.resolveConflictKeepRemote(
                OPERATION_ID,
                BUSINESS_ID,
                CONFLICT,
                RESOLVED,
                NOW + 2,
            ),
        )
        val resolved = requireNotNull(dao.findById(OPERATION_ID))
        assertEquals(RESOLVED, resolved.status)
        assertNull(resolved.completedAt)
        assertNull(resolved.claimToken)
        assertNull(resolved.claimLeaseUntil)
        assertNull(resolved.nextAttemptAt)
        assertEquals(NOW + 2, resolved.updatedAt)
        assertEquals(REMOTE_PURCHASE_ID, resolved.conflictRemotePurchaseId)
        assertEquals("receipt-remoto-1", resolved.conflictReceiptId)
        assertEquals(
            1,
            dao.resolveDependentVoidsAfterKeepRemote(
                businessId = BUSINESS_ID,
                purchaseId = PURCHASE_ID,
                voidOperationType = SYNC_PURCHASE_VOID,
                pendingStatus = PENDING,
                failedStatus = FAILED,
                conflictStatus = CONFLICT,
                resolvedStatus = RESOLVED,
                resolvedAt = NOW + 2,
            ),
        )
        assertEquals(RESOLVED, dao.findById(OPERATION_ID_2)?.status)

        // La resolución es terminal: un segundo intento no mueve la fila.
        assertEquals(
            0,
            dao.resolveConflictKeepRemote(
                OPERATION_ID,
                BUSINESS_ID,
                CONFLICT,
                RESOLVED,
                NOW + 3,
            ),
        )
        assertEquals(RESOLVED, dao.findById(OPERATION_ID)?.status)
    }

    @Test
    fun observeForBusinessEmitsTheQueueInInsertionOrder() = runBlocking {
        val dao = database.outboxOperationDao()
        dao.insert(operation(OPERATION_ID, nextAttemptAt = NOW + 60_000L))
        dao.insert(operation(OPERATION_ID_2))

        val first = dao.observeForBusiness(BUSINESS_ID).first()
        assertEquals(listOf(OPERATION_ID, OPERATION_ID_2), first.map { it.operationId })
        Unit
    }

    @Test
    fun observeViewsForBusinessOmitsTransportPayloadOutsideConflicts() = runBlocking {
        val dao = database.outboxOperationDao()
        dao.insert(operation(OPERATION_ID))
        dao.insert(
            operation(OPERATION_ID_2).copy(
                status = CONFLICT,
                payload = "{\"snapshot\":{\"name\":\"visible\"}}",
            ),
        )
        dao.insert(
            operation(OPERATION_ID_3).copy(
                status = RESOLVED,
                payload = "{\"snapshot\":{\"name\":\"terminal\"}}",
            ),
        )

        val rows = dao.observeViewsForBusiness(BUSINESS_ID).first()

        assertNull(rows.first { it.operationId == OPERATION_ID }.localConflictPayload)
        assertEquals(
            "{\"snapshot\":{\"name\":\"visible\"}}",
            rows.first { it.operationId == OPERATION_ID_2 }.localConflictPayload,
        )
        assertNull(rows.first { it.operationId == OPERATION_ID_3 }.localConflictPayload)
    }

    private suspend fun insertPurchaseFixture() {
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = SUPPLIER_ID,
                businessId = BUSINESS_ID,
                legalName = "Proveedor causal",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        insertDraftAndPurchase(
            draftId = DRAFT_ID,
            purchaseId = PURCHASE_ID,
            documentNumber = "00000001",
        )
        insertDraftAndPurchase(
            draftId = DRAFT_ID_2,
            purchaseId = PURCHASE_ID_2,
            documentNumber = "00000002",
        )
    }

    private suspend fun insertDraftAndPurchase(
        draftId: String,
        purchaseId: String,
        documentNumber: String,
    ) {
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = draftId,
                businessId = BUSINESS_ID,
                supplierId = SUPPLIER_ID,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        database.purchaseDao().insert(
            PurchaseEntity(
                purchaseId = purchaseId,
                businessId = BUSINESS_ID,
                sourceDraftId = draftId,
                supplierId = SUPPLIER_ID,
                documentType = "INVOICE",
                documentSeries = "F001",
                documentNumber = documentNumber,
                issueDate = "2026-08-08",
                currencyCode = "PEN",
                subtotalMinorUnits = 1_000L,
                taxMinorUnits = 180L,
                otherChargesMinorUnits = 0L,
                totalMinorUnits = 1_180L,
                idempotencyKey = "purchase-causal-$purchaseId",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
    }

    private fun operation(
        operationId: String,
        businessId: String = BUSINESS_ID,
        nextAttemptAt: Long? = null,
        purchaseId: String? = null,
        operationType: String = SYNC_PRODUCT,
        createdAt: Long = NOW,
        entityType: String = when (operationType) {
            SYNC_PURCHASE, SYNC_PURCHASE_VOID -> "PURCHASE"
            else -> "PRODUCT"
        },
        entityId: String = purchaseId ?: operationId,
        entityVersion: Long = if (operationType == SYNC_PURCHASE_VOID) 2 else 1,
    ) = OutboxOperationEntity(
        operationId = operationId,
        businessId = businessId,
        purchaseId = purchaseId,
        idempotencyKey = "key-$operationId",
        operationType = operationType,
        payload = if (operationType == SYNC_PURCHASE_VOID) "{\"version\":1}" else "{\"version\":2}",
        status = OutboxOperationStatus.PENDING.name,
        attemptCount = 0,
        createdAt = createdAt,
        updatedAt = createdAt,
        nextAttemptAt = nextAttemptAt,
        payloadVersion = when (operationType) {
            SYNC_PURCHASE -> 2
            else -> 1
        },
        entityType = entityType,
        entityId = entityId,
        entityVersion = entityVersion,
    )

    private companion object {
        const val BUSINESS_ID = "00000000-0000-4000-8000-000000004101"
        const val BUSINESS_ID_2 = "00000000-0000-4000-8000-000000004111"
        const val CLOUD_BUSINESS_X = "00000000-0000-4000-8000-0000000041c1"
        const val CLOUD_BUSINESS_Y = "00000000-0000-4000-8000-0000000041c2"
        const val OPERATION_ID = "00000000-0000-4000-8000-000000004102"
        const val OPERATION_ID_2 = "00000000-0000-4000-8000-000000004103"
        const val OPERATION_ID_3 = "00000000-0000-4000-8000-000000004107"
        const val PURCHASE_ID = "00000000-0000-4000-8000-000000004104"
        const val PURCHASE_ID_2 = "00000000-0000-4000-8000-000000004108"
        const val DRAFT_ID = "00000000-0000-4000-8000-000000004105"
        const val DRAFT_ID_2 = "00000000-0000-4000-8000-000000004109"
        const val SUPPLIER_ID = "00000000-0000-4000-8000-000000004106"
        const val TOKEN_A = "00000000-0000-4000-8000-0000000041a1"
        const val TOKEN_B = "00000000-0000-4000-8000-0000000041b1"
        const val NOW = 1_700_000_000_000L
        const val LEASE_MILLIS = 300_000L
        const val PENDING = "PENDING"
        const val PROCESSING = "PROCESSING"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        const val CONFLICT = "CONFLICT"
        const val RESOLVED = "RESOLVED"
        const val REMOTE_PURCHASE_ID = "00000000-0000-4000-8000-0000000042c1"
        const val PRODUCT_ID = "00000000-0000-4000-8000-0000000042d1"
        const val SYNC_PURCHASE = "SYNC_PURCHASE"
        const val SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
        const val SYNC_PRODUCT = "SYNC_PRODUCT"
    }
}
