package com.facturastock.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Regresión focal del índice que alimenta la muestra global de salud de la outbox. */
class OutboxHealthIndexMigrationTest {
    @get:Rule
    val helper = FacturaStockMigrationTestHelper()

    @Test
    fun migration24To25PreservesOldestOutstandingSemanticsAndUsesCoveringIndex() {
        helper.createDatabase(DATABASE_NAME, 24).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`,`legalName`,`createdAt`,`updatedAt`,`status`) VALUES " +
                    "('$BUSINESS_ID','Negocio outbox',1,1,'ACTIVE')," +
                    "('$INACTIVE_BUSINESS_ID','Negocio inactivo',1,1,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `cloud_business_bindings` " +
                    "(`localBusinessId`,`cloudBusinessId`,`createdAt`,`boundLegacyOperationCount`) " +
                    "VALUES ('$BUSINESS_ID','$CLOUD_BUSINESS_ID',1,0)," +
                    "('$INACTIVE_BUSINESS_ID','$INACTIVE_CLOUD_BUSINESS_ID',1,0)",
            )
            insertOutbox(
                operationId = PENDING_OPERATION_ID,
                idempotencyKey = "health:pending",
                status = "PENDING",
                createdAt = 300L,
            )
            insertOutbox(
                operationId = CONFLICT_OPERATION_ID,
                idempotencyKey = "health:conflict",
                status = "CONFLICT",
                createdAt = 100L,
            )
            insertOutbox(
                operationId = COMPLETED_OPERATION_ID,
                idempotencyKey = "health:completed",
                status = "COMPLETED",
                createdAt = 50L,
                completedAt = 60L,
            )
            insertOutbox(
                operationId = INACTIVE_OPERATION_ID,
                idempotencyKey = "health:inactive",
                status = "PENDING",
                createdAt = 25L,
                businessId = INACTIVE_BUSINESS_ID,
                targetCloudBusinessId = INACTIVE_CLOUD_BUSINESS_ID,
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            25,
            true,
            FacturaStockDatabase.MIGRATION_24_25,
        )

        database.query("PRAGMA index_xinfo(`$INDEX_NAME`)").use { cursor ->
            val columns = buildList {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val keyColumn = cursor.getColumnIndexOrThrow("key")
                while (cursor.moveToNext()) {
                    if (cursor.getInt(keyColumn) == 1) add(cursor.getString(nameColumn))
                }
            }
            assertEquals(
                listOf("targetCloudBusinessId", "status", "completedAt", "createdAt"),
                columns,
            )
        }

        val healthQuery =
            "SELECT MIN(createdAt) FROM outbox_operations " +
                "WHERE targetCloudBusinessId = '$CLOUD_BUSINESS_ID' " +
                "AND status IN ('PENDING','PROCESSING','FAILED','CONFLICT') " +
                "AND completedAt IS NULL"
        database.query("EXPLAIN QUERY PLAN $healthQuery").use { cursor ->
            val details = buildList {
                val detailColumn = cursor.getColumnIndexOrThrow("detail")
                while (cursor.moveToNext()) add(cursor.getString(detailColumn)!!)
            }
            assertTrue("Plan no usa $INDEX_NAME: $details", details.any { it.contains(INDEX_NAME) })
            assertTrue(
                "La consulta debe quedar cubierta por el índice: $details",
                details.any { it.contains("COVERING INDEX") },
            )
            assertFalse(
                "La consulta no debe escanear la tabla: $details",
                details.any { it.contains("SCAN outbox_operations") },
            )
        }
        database.query(healthQuery).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(100L, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }

        helper.closeWhenFinished(database)
    }

    private fun com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase.insertOutbox(
        operationId: String,
        idempotencyKey: String,
        status: String,
        createdAt: Long,
        completedAt: Long? = null,
        businessId: String = BUSINESS_ID,
        targetCloudBusinessId: String = CLOUD_BUSINESS_ID,
    ) {
        val completedValue = completedAt?.toString() ?: "NULL"
        val updatedAt = completedAt ?: createdAt
        execSQL(
            "INSERT INTO `outbox_operations` " +
                "(`operationId`,`businessId`,`idempotencyKey`,`operationType`,`payload`,`status`," +
                "`attemptCount`,`createdAt`,`updatedAt`,`completedAt`,`payloadVersion`,`entityType`," +
                "`entityId`,`entityVersion`,`targetCloudBusinessId`) VALUES " +
                "('$operationId','$businessId','$idempotencyKey','SYNC_PRODUCT','{}','$status'," +
                "0,$createdAt,$updatedAt,$completedValue,1,'PRODUCT','$operationId',1," +
                "'$targetCloudBusinessId')",
        )
    }

    private companion object {
        const val DATABASE_NAME = "outbox-health-index.db"
        const val INDEX_NAME =
            "index_outbox_operations_targetCloudBusinessId_status_completedAt_createdAt"
        const val BUSINESS_ID = "10000000-0000-4000-8000-000000000001"
        const val INACTIVE_BUSINESS_ID = "10000000-0000-4000-8000-000000000002"
        const val CLOUD_BUSINESS_ID = "30000000-0000-4000-8000-000000000001"
        const val INACTIVE_CLOUD_BUSINESS_ID = "30000000-0000-4000-8000-000000000002"
        const val PENDING_OPERATION_ID = "20000000-0000-4000-8000-000000000001"
        const val CONFLICT_OPERATION_ID = "20000000-0000-4000-8000-000000000002"
        const val COMPLETED_OPERATION_ID = "20000000-0000-4000-8000-000000000003"
        const val INACTIVE_OPERATION_ID = "20000000-0000-4000-8000-000000000004"
    }
}
