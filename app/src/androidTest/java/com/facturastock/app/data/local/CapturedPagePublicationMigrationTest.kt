package com.facturastock.app.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturedPagePublicationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FacturaStockDatabase::class.java,
    )

    @Test
    fun migration23To24AddsEmptyDurableReceiptTableWithCascadeOwnership() {
        helper.createDatabase(DATABASE_NAME, 23).close()

        val database = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            24,
            true,
            FacturaStockDatabase.MIGRATION_23_24,
        )

        database.query("SELECT COUNT(*) FROM captured_page_publications").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
        database.query("PRAGMA table_info(`captured_page_publications`)").use { cursor ->
            var foundReplacedFilePath = false
            while (cursor.moveToNext()) {
                val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (columnName == "replacedFilePath") {
                    foundReplacedFilePath = true
                    assertEquals(
                        "La ruta sustituida es nullable para recibos APPEND",
                        0,
                        cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                    )
                }
            }
            assertTrue("Falta replacedFilePath en el recibo v24", foundReplacedFilePath)
        }
        database.query("PRAGMA foreign_key_list(`captured_page_publications`)").use { cursor ->
            val parents = mutableSetOf<String>()
            val deleteActions = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                parents += cursor.getString(cursor.getColumnIndexOrThrow("table"))
                deleteActions += cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
            }
            assertEquals(setOf("invoice_drafts", "businesses"), parents)
            assertEquals(setOf("CASCADE"), deleteActions)
        }
        database.query("PRAGMA index_list(`captured_page_publications`)").use { cursor ->
            val indices = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indices += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("index_captured_page_publications_draftId" in indices)
            assertTrue("index_captured_page_publications_businessId" in indices)
        }
        database.query("PRAGMA index_list(`invoice_images`)").use { cursor ->
            val indices = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indices += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("index_invoice_images_filePath" in indices)
        }

        helper.closeWhenFinished(database)
    }

    @Test
    fun migration23To24InstallsCapturedPublicationTenantGuards() {
        helper.createDatabase(TENANT_DATABASE_NAME, 23).close()
        val database = helper.runMigrationsAndValidate(
            TENANT_DATABASE_NAME,
            24,
            true,
            FacturaStockDatabase.MIGRATION_23_24,
        )
        database.execSQL(
            "INSERT INTO `businesses` " +
                "(`businessId`,`legalName`,`createdAt`,`updatedAt`,`status`) VALUES " +
                "('$BUSINESS_A','Negocio A',1,1,'ACTIVE')," +
                "('$BUSINESS_B','Negocio B',1,1,'ACTIVE')",
        )
        database.execSQL(
            "INSERT INTO `invoice_drafts` " +
                "(`draftId`,`businessId`,`createdAt`,`updatedAt`,`status`) VALUES " +
                "('$DRAFT_A','$BUSINESS_A',1,1,'CREATED')",
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL(capturedPublicationInsert(IMAGE_CROSS_TENANT, BUSINESS_B))
        }
        database.execSQL(capturedPublicationInsert(IMAGE_VALID, BUSINESS_A))
        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL(
                "UPDATE `captured_page_publications` SET `businessId` = '$BUSINESS_B' " +
                    "WHERE `imageId` = '$IMAGE_VALID'",
            )
        }
        database.query(
            "SELECT `businessId` FROM `captured_page_publications` " +
                "WHERE `imageId` = '$IMAGE_VALID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(BUSINESS_A, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }

        helper.closeWhenFinished(database)
    }

    private fun capturedPublicationInsert(imageId: String, businessId: String): String =
        "INSERT INTO `captured_page_publications` " +
            "(`imageId`,`draftId`,`businessId`,`intentKind`,`replaceTargetImageId`," +
            "`replacedFilePath`,`filePath`,`sha256`,`mimeType`,`widthPx`,`heightPx`," +
            "`fileSizeBytes`,`rotationDegrees`,`publishedPageIndex`,`publishedAt`) VALUES " +
            "('$imageId','$DRAFT_A','$businessId','APPEND',NULL,NULL," +
            "'draft_images/$DRAFT_A/$imageId.jpg','${"a".repeat(64)}'," +
            "'image/jpeg',100,100,1000,0,0,1)"

    private companion object {
        const val DATABASE_NAME = "captured-page-publication-migration.db"
        const val TENANT_DATABASE_NAME = "captured-page-publication-tenant-migration.db"
        const val BUSINESS_A = "10000000-0000-4000-8000-000000000001"
        const val BUSINESS_B = "10000000-0000-4000-8000-000000000002"
        const val DRAFT_A = "20000000-0000-4000-8000-000000000001"
        const val IMAGE_VALID = "30000000-0000-4000-8000-000000000001"
        const val IMAGE_CROSS_TENANT = "30000000-0000-4000-8000-000000000002"
    }
}
