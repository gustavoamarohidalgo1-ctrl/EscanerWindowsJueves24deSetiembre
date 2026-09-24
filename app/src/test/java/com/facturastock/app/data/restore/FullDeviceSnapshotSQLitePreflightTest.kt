package com.facturastock.app.data.restore

import com.facturastock.app.data.local.FACTURA_STOCK_DATABASE_SCHEMA_VERSION
import com.facturastock.app.data.local.FACTURA_STOCK_ROOM_IDENTITY_HASH_V24
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.FacturaStockMigrationTestHelper
import com.facturastock.app.data.local.close
import com.facturastock.app.data.local.expectedFacturaStockRoomIdentityHash
import com.facturastock.app.data.local.openRawDatabase
import com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase
import com.facturastock.app.data.local.use
import com.facturastock.app.data.local.writableSql
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FullDeviceSnapshotSQLitePreflightTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val migrationHelper = FacturaStockMigrationTestHelper()

    /** Identity hash exportado por Room para el esquema vigente (el test Android fijaba v27). */
    private val currentIdentityHash =
        requireNotNull(expectedFacturaStockRoomIdentityHash(FACTURA_STOCK_DATABASE_SCHEMA_VERSION))

    @Test
    fun currentRoomImagePassesIdentityIntegrityForeignKeysAndEveryDeclaredCount() {
        val fixture = createFixture()

        val result = FullDeviceSnapshotSQLitePreflight().inspect(fixture.snapshot)

        assertTrue("$result", result is FullDeviceSnapshotSQLitePreflightResult.Passed)
        result as FullDeviceSnapshotSQLitePreflightResult.Passed
        assertEquals(
            FACTURA_STOCK_DATABASE_SCHEMA_VERSION,
            result.evidence.sourceDatabaseSchemaVersion,
        )
        assertEquals(currentIdentityHash, result.evidence.sourceRoomIdentityHash)
        assertEquals(listOf("ok"), result.evidence.integrityCheckRows)
        assertEquals(0L, result.evidence.foreignKeyViolationCount)
        assertEquals(fixture.counts, result.evidence.tableRowCounts)
    }

    @Test
    fun version24ImageIsAcceptedAsTheExplicitNonDestructiveMigrationSource() {
        val databaseName = "snapshot-preflight-v24-${UUID.randomUUID()}.db"
        migrationHelper.createDatabase(databaseName, 24).close()
        val databasePath = migrationHelper.databaseFile(databaseName).toPath()
        openRawDatabase(databasePath.toFile(), foreignKeys = false).use(::leaveWalMode)
        deleteSidecars(databasePath)
        val fixture = fixtureForExistingDatabase(
            databasePath = databasePath,
            schemaVersion = 24,
            identityHash = FACTURA_STOCK_ROOM_IDENTITY_HASH_V24,
        )

        val result = FullDeviceSnapshotSQLitePreflight().inspect(fixture.snapshot)

        assertTrue("$result", result is FullDeviceSnapshotSQLitePreflightResult.Passed)
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
        mutateAfterClose: (SupportSQLiteDatabase) -> Unit = {},
    ): Fixture {
        val databaseName = "snapshot-preflight-${UUID.randomUUID()}.db"
        val databaseFile = File(tempFolder.root, databaseName)
        val room = FacturaStockDatabase.build(databaseFile)
        val supportDatabase = room.writableSql
        supportDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        room.close()

        val databasePath = databaseFile.toPath()
        // Como SQLiteDatabase.openDatabase de Android: conexión cruda, sin Room ni FKs activadas.
        openRawDatabase(databaseFile, foreignKeys = false).use { database ->
            leaveWalMode(database)
            mutateAfterClose(database)
            database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
        deleteSidecars(databasePath)

        return fixtureForExistingDatabase(
            databasePath = databasePath,
            schemaVersion = FACTURA_STOCK_DATABASE_SCHEMA_VERSION,
            identityHash = currentIdentityHash,
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
        openRawDatabase(databasePath.toFile(), foreignKeys = false).use { database ->
            FullDeviceSnapshotContract.requiredV1TablesForSchema(schemaVersion)
                .associateWith { table ->
                    database.query("SELECT COUNT(*) FROM `${table}`").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                }
        }

    /**
     * `SQLiteDatabase.openDatabase` de Android sin ENABLE_WRITE_AHEAD_LOGGING fija el modo de
     * journal por defecto del sistema (TRUNCATE), lo que saca la imagen de WAL. El driver de
     * escritorio conserva WAL, y abrir en solo lectura una imagen WAL crea -wal/-shm, que la
     * preflight rechaza (con razón) como DATABASE_CHANGED_DURING_PREFLIGHT. Se reproduce aquí el
     * paso que Android hacía implícitamente.
     */
    private fun leaveWalMode(database: SupportSQLiteDatabase) {
        database.query("PRAGMA journal_mode = TRUNCATE").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("truncate", cursor.getString(0)!!.lowercase())
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
