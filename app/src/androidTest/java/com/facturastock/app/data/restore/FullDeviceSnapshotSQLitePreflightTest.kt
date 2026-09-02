package com.facturastock.app.data.restore

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.data.local.FACTURA_STOCK_DATABASE_SCHEMA_VERSION
import com.facturastock.app.data.local.FACTURA_STOCK_ROOM_IDENTITY_HASH_V24
import com.facturastock.app.data.local.FACTURA_STOCK_ROOM_IDENTITY_HASH_V27
import com.facturastock.app.data.local.FailClosedSQLiteOpenHelperFactory
import com.facturastock.app.data.local.FacturaStockDatabase
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullDeviceSnapshotSQLitePreflightTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val createdDatabaseNames = mutableListOf<String>()

    @After
    fun tearDown() {
        createdDatabaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun currentRoomImagePassesIdentityIntegrityForeignKeysAndEveryDeclaredCount() {
        val fixture = createFixture()

        val result = FullDeviceSnapshotSQLitePreflight().inspect(fixture.snapshot)

        assertTrue(result is FullDeviceSnapshotSQLitePreflightResult.Passed)
        result as FullDeviceSnapshotSQLitePreflightResult.Passed
        assertEquals(
            FACTURA_STOCK_DATABASE_SCHEMA_VERSION,
            result.evidence.sourceDatabaseSchemaVersion,
        )
        assertEquals(FACTURA_STOCK_ROOM_IDENTITY_HASH_V27, result.evidence.sourceRoomIdentityHash)
        assertEquals(listOf("ok"), result.evidence.integrityCheckRows)
        assertEquals(0L, result.evidence.foreignKeyViolationCount)
        assertEquals(fixture.counts, result.evidence.tableRowCounts)
    }

    @Test
    fun version24ImageIsAcceptedAsTheExplicitNonDestructiveMigrationSource() {
        val databaseName = "snapshot-preflight-v24-${UUID.randomUUID()}.db"
        createdDatabaseNames += databaseName
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            FacturaStockDatabase::class.java,
        ).createDatabase(databaseName, 24).close()
        val databasePath = context.getDatabasePath(databaseName).toPath()
        deleteSidecars(databasePath)
        val fixture = fixtureForExistingDatabase(
            databasePath = databasePath,
            schemaVersion = 24,
            identityHash = FACTURA_STOCK_ROOM_IDENTITY_HASH_V24,
        )

        val result = FullDeviceSnapshotSQLitePreflight().inspect(fixture.snapshot)

        assertTrue(result is FullDeviceSnapshotSQLitePreflightResult.Passed)
        result as FullDeviceSnapshotSQLitePreflightResult.Passed
        assertEquals(24, result.evidence.sourceDatabaseSchemaVersion)
        assertEquals(FACTURA_STOCK_ROOM_IDENTITY_HASH_V24, result.evidence.sourceRoomIdentityHash)
    }

    @Test
    fun forgedOrStaleRoomIdentityIsRejectedBeforePromotion() {
        val fixture = createFixture { database ->
            database.execSQL(
                "UPDATE `room_master_table` SET `identity_hash` = ? WHERE `id` = 42",
                arrayOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            )
        }

        assertEquals(
            FullDeviceSnapshotSQLitePreflightResult.Rejected(
                FullDeviceSnapshotSQLitePreflightFailureCode.ROOM_IDENTITY_MISMATCH,
            ),
            FullDeviceSnapshotSQLitePreflight().inspect(fixture.snapshot),
        )
    }

    @Test
    fun foreignKeyViolationIsRejectedEvenWhenManifestCountsAndFingerprintMatch() {
        val fixture = createFixture { database ->
            database.execSQL("PRAGMA foreign_keys = OFF")
            database.execSQL(
                "INSERT INTO `invoice_ocr_snapshot_pages` (" +
                    "`draftId`, `pageIndex`, `sourceImageId`, `widthPx`, `heightPx`, " +
                    "`payloadSha256`, `payload`) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "00000000-0000-4000-8000-000000000991",
                    0,
                    "00000000-0000-4000-8000-000000000992",
                    10,
                    10,
                    "b".repeat(64),
                    byteArrayOf(1),
                ),
            )
        }

        assertEquals(
            FullDeviceSnapshotSQLitePreflightResult.Rejected(
                FullDeviceSnapshotSQLitePreflightFailureCode.FOREIGN_KEY_VIOLATIONS,
            ),
            FullDeviceSnapshotSQLitePreflight().inspect(fixture.snapshot),
        )
    }

    @Test
    fun anyWalShmOrRollbackJournalSidecarFailsClosed() {
        val fixture = createFixture()
        val sidecar = fixture.databasePath.resolveSibling(
            fixture.databasePath.fileName.toString() + "-wal",
        )
        Files.write(sidecar, byteArrayOf(1))

        assertEquals(
            FullDeviceSnapshotSQLitePreflightResult.Rejected(
                FullDeviceSnapshotSQLitePreflightFailureCode.DATABASE_SIDECAR_PRESENT,
            ),
            FullDeviceSnapshotSQLitePreflight().inspect(fixture.snapshot),
        )
        Files.deleteIfExists(sidecar)
    }

    private fun createFixture(
        mutateAfterClose: (SQLiteDatabase) -> Unit = {},
    ): Fixture {
        val databaseName = "snapshot-preflight-${UUID.randomUUID()}.db"
        createdDatabaseNames += databaseName
        val room = FacturaStockDatabase.buildNamed(
            context,
            FailClosedSQLiteOpenHelperFactory(),
            databaseName,
        )
        val supportDatabase = room.openHelper.writableDatabase
        supportDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        room.close()

        val databasePath = context.getDatabasePath(databaseName).toPath()
        SQLiteDatabase.openDatabase(
            databasePath.toString(),
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { database ->
            mutateAfterClose(database)
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
        deleteSidecars(databasePath)

        return fixtureForExistingDatabase(
            databasePath = databasePath,
            schemaVersion = FACTURA_STOCK_DATABASE_SCHEMA_VERSION,
            identityHash = FACTURA_STOCK_ROOM_IDENTITY_HASH_V27,
        )
    }

    private fun fixtureForExistingDatabase(
        databasePath: Path,
        schemaVersion: Int,
        identityHash: String,
    ): Fixture {
        val counts = readCounts(databasePath, schemaVersion)
        val bytes = Files.readAllBytes(databasePath)
        val fingerprint = SnapshotDatabaseFingerprint(
            sizeBytes = bytes.size.toLong(),
            sha256 = FullDeviceSnapshotDigests.sha256(bytes),
        )
        val databaseEntry = FullDeviceSnapshotEntry(
            path = FullDeviceSnapshotContract.DATABASE_ENTRY_PATH,
            kind = FullDeviceSnapshotEntryKind.ROOM_DATABASE,
            uncompressedSizeBytes = fingerprint.sizeBytes,
            sha256 = fingerprint.sha256,
        )
        val manifest = FullDeviceSnapshotManifest.create(
            sourceDatabaseSchemaVersion = schemaVersion,
            sourceRoomIdentityHash = identityHash,
            sourceApplicationVersion = "1.0.0-test",
            createdAtEpochMillis = 1_777_777_777_777L,
            entries = listOf(databaseEntry),
            tableDigests = counts.map { (table, count) ->
                FullDeviceSnapshotTableDigest(
                    tableName = table,
                    rowCount = count,
                    canonicalRowsSha256 = FullDeviceSnapshotDigests.sha256(
                        table.toByteArray(Charsets.UTF_8),
                    ),
                )
            },
        )
        return Fixture(
            databasePath = databasePath,
            counts = counts,
            snapshot = StagedFullDeviceSnapshot(
                manifest = manifest,
                stagingDirectory = databasePath.parent,
                stagedDatabase = databasePath,
                stagedPayloads = mapOf(databaseEntry.path to databasePath),
            ),
        )
    }

    private fun readCounts(databasePath: Path, schemaVersion: Int): Map<String, Long> =
        SQLiteDatabase.openDatabase(
            databasePath.toString(),
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { database ->
            FullDeviceSnapshotContract.requiredV1TablesForSchema(schemaVersion)
                .associateWith { table ->
                    database.rawQuery("SELECT COUNT(*) FROM `${table}`", emptyArray()).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                }
        }

    private fun deleteSidecars(database: Path) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val sidecar = database.resolveSibling(database.fileName.toString() + suffix)
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) Files.delete(sidecar)
        }
    }

    private data class Fixture(
        val databasePath: Path,
        val counts: Map<String, Long>,
        val snapshot: StagedFullDeviceSnapshot,
    )
}
