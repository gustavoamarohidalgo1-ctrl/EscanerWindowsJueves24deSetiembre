package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Integración v19 → esquema vigente del enlace durable y una operación legacy. */
@RunWith(AndroidJUnit4::class)
class RoomCloudBusinessBindingMigrationIntegrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FacturaStockDatabase::class.java,
    )

    private lateinit var database: FacturaStockDatabase

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(ELIGIBLE_DATABASE)
        context.deleteDatabase(ATTEMPTED_DATABASE)
    }

    @Test
    fun migrate19ThenFirstLinkClaimsAndCompletesSameNeverAttemptedPurchase() = runBlocking {
        seedV19Database(ELIGIBLE_DATABASE, attemptCount = 0)
        database = migrateToCurrentVersion(ELIGIBLE_DATABASE)
        assertEquals(24, database.openHelper.readableDatabase.version)

        val outboxDao = database.outboxOperationDao()
        val migrated = requireNotNull(outboxDao.findById(OPERATION_ID))
        assertNull(migrated.targetCloudBusinessId)
        assertEquals(OutboxOperationStatus.PENDING.name, migrated.status)
        assertEquals(0, migrated.attemptCount)

        val purchaseBeforeProcessing = requireNotNull(database.purchaseDao().findById(PURCHASE_ID))
        assertEquals(LOCAL_BUSINESS_ID, purchaseBeforeProcessing.businessId)
        assertEquals(DRAFT_ID, purchaseBeforeProcessing.sourceDraftId)
        assertEquals(SUPPLIER_ID, purchaseBeforeProcessing.supplierId)
        assertEquals(SUBTOTAL_MINOR_UNITS, purchaseBeforeProcessing.subtotalMinorUnits)
        assertEquals(TAX_MINOR_UNITS, purchaseBeforeProcessing.taxMinorUnits)
        assertEquals(OTHER_CHARGES_MINOR_UNITS, purchaseBeforeProcessing.otherChargesMinorUnits)
        assertEquals(TOTAL_MINOR_UNITS, purchaseBeforeProcessing.totalMinorUnits)
        assertEquals(PurchaseStatus.POSTED.name, purchaseBeforeProcessing.status)

        val bindings = bindingRepository()
        assertEquals(
            CloudBusinessBindingResult.Bound,
            bindings.bindOnce(LOCAL_BUSINESS, CLOUD_BUSINESS, Instant.ofEpochMilli(BOUND_AT)),
        )
        val binding = requireNotNull(
            database.cloudBusinessBindingDao().findByLocal(LOCAL_BUSINESS_ID),
        )
        assertEquals(CLOUD_BUSINESS_ID, binding.cloudBusinessId)
        assertEquals(1, binding.boundLegacyOperationCount)

        val pinned = requireNotNull(outboxDao.findById(OPERATION_ID))
        assertEquals(CLOUD_BUSINESS_ID, pinned.targetCloudBusinessId)
        assertEquals(LOCAL_BUSINESS_ID, pinned.businessId)
        assertEquals(PURCHASE_ID, pinned.purchaseId)
        assertEquals(PURCHASE_ID, pinned.entityId)
        assertEquals("PURCHASE", pinned.entityType)
        assertEquals(1L, pinned.entityVersion)
        assertEquals(OUTBOX_IDEMPOTENCY_KEY, pinned.idempotencyKey)
        assertEquals(PAYLOAD, pinned.payload)
        assertEquals(OutboxOperationStatus.PENDING.name, pinned.status)

        val outbox = outboxRepository()
        val ready = outbox.listReady(
            now = Instant.ofEpochMilli(CLAIMED_AT),
            limit = 10,
            targetCloudBusinessId = CLOUD_BUSINESS,
        )
        assertEquals(1, ready.size)
        val pending = ready.single()
        assertEquals(0, pending.attemptCount)
        assertEquals(OPERATION_ID, pending.envelope.operationId)
        assertEquals(LOCAL_BUSINESS, pending.envelope.businessId)
        assertEquals(CLOUD_BUSINESS, pending.envelope.targetCloudBusinessId)
        assertEquals(PURCHASE_ID, pending.envelope.purchaseId?.value)
        assertEquals(PURCHASE_ID, pending.envelope.entityId)
        assertEquals(OUTBOX_IDEMPOTENCY_KEY, pending.envelope.idempotencyKey)
        assertEquals(PAYLOAD, pending.envelope.payload)

        assertTrue(
            outbox.claim(
                operationId = OPERATION_ID,
                claimToken = CLAIM_TOKEN,
                claimedAt = Instant.ofEpochMilli(CLAIMED_AT),
                leaseUntil = Instant.ofEpochMilli(LEASE_UNTIL),
                targetCloudBusinessId = CLOUD_BUSINESS,
            ),
        )
        val processing = requireNotNull(outboxDao.findById(OPERATION_ID))
        assertEquals(OutboxOperationStatus.PROCESSING.name, processing.status)
        assertEquals(1, processing.attemptCount)
        assertEquals(CLAIM_TOKEN, processing.claimToken)
        assertEquals(OUTBOX_IDEMPOTENCY_KEY, processing.idempotencyKey)
        assertEquals(CLOUD_BUSINESS_ID, processing.targetCloudBusinessId)

        assertTrue(
            outbox.complete(
                operationId = OPERATION_ID,
                claimToken = CLAIM_TOKEN,
                completedAt = Instant.ofEpochMilli(COMPLETED_AT),
            ),
        )
        val completed = requireNotNull(outboxDao.findById(OPERATION_ID))
        assertEquals(OutboxOperationStatus.COMPLETED.name, completed.status)
        assertEquals(1, completed.attemptCount)
        assertEquals(COMPLETED_AT, completed.completedAt)
        assertNull(completed.claimToken)
        assertNull(completed.claimLeaseUntil)
        assertEquals(CLOUD_BUSINESS_ID, completed.targetCloudBusinessId)
        assertEquals(OUTBOX_IDEMPOTENCY_KEY, completed.idempotencyKey)
        assertEquals(
            OPERATION_ID,
            outboxDao.findByIdempotencyKey(OUTBOX_IDEMPOTENCY_KEY)?.operationId,
        )
        assertFalse(
            outbox.complete(
                operationId = OPERATION_ID,
                claimToken = CLAIM_TOKEN,
                completedAt = Instant.ofEpochMilli(COMPLETED_AT + 1),
            ),
        )
        assertEquals(purchaseBeforeProcessing, database.purchaseDao().findById(PURCHASE_ID))
    }

    @Test
    fun migratedAttemptedLegacyRowStillRejectsBindingAsUnknown() = runBlocking {
        seedV19Database(ATTEMPTED_DATABASE, attemptCount = 1)
        database = migrateToCurrentVersion(ATTEMPTED_DATABASE)

        assertEquals(
            CloudBusinessBindingResult.LegacyDestinationUnknown,
            bindingRepository().bindOnce(
                LOCAL_BUSINESS,
                CLOUD_BUSINESS,
                Instant.ofEpochMilli(BOUND_AT),
            ),
        )
        assertNull(database.cloudBusinessBindingDao().findByLocal(LOCAL_BUSINESS_ID))
        val legacy = requireNotNull(database.outboxOperationDao().findById(OPERATION_ID))
        assertNull(legacy.targetCloudBusinessId)
        assertEquals(OutboxOperationStatus.PENDING.name, legacy.status)
        assertEquals(1, legacy.attemptCount)
        assertEquals(OUTBOX_IDEMPOTENCY_KEY, legacy.idempotencyKey)
    }

    private fun migrateToCurrentVersion(databaseName: String): FacturaStockDatabase =
        Room.databaseBuilder(context, FacturaStockDatabase::class.java, databaseName)
            .addMigrations(
                FacturaStockDatabase.MIGRATION_19_20,
                FacturaStockDatabase.MIGRATION_20_21,
                FacturaStockDatabase.MIGRATION_21_22,
                FacturaStockDatabase.MIGRATION_22_23,
                FacturaStockDatabase.MIGRATION_23_24,
                FacturaStockDatabase.MIGRATION_24_25,
            )
            .addCallback(postingPersistenceCallback)
            .build()
            .also { it.openHelper.writableDatabase }

    private fun bindingRepository() = RoomCloudBusinessBindingRepository(
        database = database,
        bindings = database.cloudBusinessBindingDao(),
        outbox = database.outboxOperationDao(),
        catalogLinks = database.catalogSyncLinkDao(),
        dispatchers = TEST_DISPATCHERS,
    )

    private fun outboxRepository() = RoomPurchaseBackupOutboxRepository(
        database = database,
        outbox = database.outboxOperationDao(),
        auditEvents = database.auditEventDao(),
        uuids = UuidGenerator { UUID.fromString(CLAIM_TOKEN) },
        dispatchers = TEST_DISPATCHERS,
    )

    private fun seedV19Database(databaseName: String, attemptCount: Int) {
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 19).apply {
            execSQL("PRAGMA foreign_keys=ON")
            insertLegacyBusiness()
            insertLegacyPurchase()
            insertLegacyOutbox(attemptCount)
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertLegacyBusiness() {
        execSQL(
            "INSERT INTO businesses " +
                "(businessId,legalName,createdAt,updatedAt,status) VALUES (?,?,?,?,?)",
            arrayOf<Any>(
                LOCAL_BUSINESS_ID,
                "Negocio legacy",
                CREATED_AT,
                POSTED_AT,
                "ACTIVE",
            ),
        )
        execSQL(
            "INSERT INTO suppliers " +
                "(supplierId,businessId,legalName,createdAt,updatedAt,status,version) " +
                "VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any>(
                SUPPLIER_ID,
                LOCAL_BUSINESS_ID,
                "Proveedor legacy",
                CREATED_AT,
                POSTED_AT,
                "ACTIVE",
                1,
            ),
        )
        execSQL(
            "INSERT INTO invoice_drafts " +
                "(draftId,businessId,createdAt,updatedAt,status,supplierId," +
                "supplierLegalNameNormalized,documentType,documentNumberNormalized," +
                "issueDateNormalized,currencyCode,subtotalMinorUnits,taxMinorUnits," +
                "otherChargesMinorUnits,totalMinorUnits) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                DRAFT_ID,
                LOCAL_BUSINESS_ID,
                CREATED_AT,
                POSTED_AT,
                "READY_TO_POST",
                SUPPLIER_ID,
                "Proveedor legacy",
                "INVOICE",
                "F001-42",
                "2026-08-20",
                "PEN",
                SUBTOTAL_MINOR_UNITS,
                TAX_MINOR_UNITS,
                OTHER_CHARGES_MINOR_UNITS,
                TOTAL_MINOR_UNITS,
            ),
        )
    }

    private fun SupportSQLiteDatabase.insertLegacyPurchase() {
        execSQL(
            "INSERT INTO purchases " +
                "(purchaseId,businessId,sourceDraftId,supplierId,documentType,documentSeries," +
                "documentNumber,issueDate,currencyCode,subtotalMinorUnits,taxMinorUnits," +
                "otherChargesMinorUnits,totalMinorUnits,status,idempotencyKey,createdAt," +
                "updatedAt,postedAt,documentIdentitySlot) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                PURCHASE_ID,
                LOCAL_BUSINESS_ID,
                DRAFT_ID,
                SUPPLIER_ID,
                "INVOICE",
                "F001",
                "42",
                "2026-08-20",
                "PEN",
                SUBTOTAL_MINOR_UNITS,
                TAX_MINOR_UNITS,
                OTHER_CHARGES_MINOR_UNITS,
                TOTAL_MINOR_UNITS,
                PurchaseStatus.POSTED.name,
                PURCHASE_IDEMPOTENCY_KEY,
                CREATED_AT,
                POSTED_AT,
                POSTED_AT,
                "PRIMARY",
            ),
        )
    }

    private fun SupportSQLiteDatabase.insertLegacyOutbox(attemptCount: Int) {
        execSQL(
            "INSERT INTO outbox_operations " +
                "(operationId,businessId,purchaseId,idempotencyKey,operationType,payload,status," +
                "attemptCount,createdAt,updatedAt,nextAttemptAt,lastError,payloadVersion," +
                "entityType,entityId,entityVersion) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                OPERATION_ID,
                LOCAL_BUSINESS_ID,
                PURCHASE_ID,
                OUTBOX_IDEMPOTENCY_KEY,
                "SYNC_PURCHASE",
                PAYLOAD,
                OutboxOperationStatus.PENDING.name,
                attemptCount,
                OUTBOX_CREATED_AT,
                OUTBOX_CREATED_AT,
                OUTBOX_CREATED_AT,
                if (attemptCount == 0) null else "NETWORK_UNAVAILABLE",
                3,
                "PURCHASE",
                PURCHASE_ID,
                1L,
            ),
        )
    }

    private companion object {
        const val ELIGIBLE_DATABASE = "tenant-binding-migration-eligible.db"
        const val ATTEMPTED_DATABASE = "tenant-binding-migration-attempted.db"

        const val CREATED_AT = 1_000L
        const val POSTED_AT = 1_500L
        const val OUTBOX_CREATED_AT = 1_600L
        const val BOUND_AT = 2_000L
        const val CLAIMED_AT = 3_000L
        const val LEASE_UNTIL = 8_000L
        const val COMPLETED_AT = 4_000L

        const val SUBTOTAL_MINOR_UNITS = 10_005L
        const val TAX_MINOR_UNITS = 1_801L
        const val OTHER_CHARGES_MINOR_UNITS = 99L
        const val TOTAL_MINOR_UNITS = 11_905L

        const val LOCAL_BUSINESS_ID = "11111111-1111-4111-8111-111111111111"
        const val CLOUD_BUSINESS_ID = "22222222-2222-4222-8222-222222222222"
        const val SUPPLIER_ID = "33333333-3333-4333-8333-333333333333"
        const val DRAFT_ID = "44444444-4444-4444-8444-444444444444"
        const val PURCHASE_ID = "55555555-5555-4555-8555-555555555555"
        const val OPERATION_ID = "66666666-6666-4666-8666-666666666666"
        const val CLAIM_TOKEN = "77777777-7777-4777-8777-777777777777"

        const val PURCHASE_IDEMPOTENCY_KEY = "purchase:v1:legacy-v19"
        const val OUTBOX_IDEMPOTENCY_KEY = "sync-purchase:v1:legacy-v19"
        const val PAYLOAD =
            "{\"version\":3,\"purchaseId\":\"$PURCHASE_ID\",\"draftId\":\"$DRAFT_ID\"," +
                "\"preparedHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaa\",\"reconciliationAdjustment\":null," +
                "\"aliasesCreated\":0,\"aliasesSkipped\":[]}"

        val LOCAL_BUSINESS = requireNotNull(BusinessId.parse(LOCAL_BUSINESS_ID))
        val CLOUD_BUSINESS = requireNotNull(BusinessId.parse(CLOUD_BUSINESS_ID))

        val TEST_DISPATCHERS = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }
    }
}
