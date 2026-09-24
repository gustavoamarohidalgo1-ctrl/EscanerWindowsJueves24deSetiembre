package com.facturastock.app.data.restore

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.facturastock.app.data.local.expectedFacturaStockRoomIdentityHash
import com.facturastock.app.data.local.sqlite.SQLiteException
import com.facturastock.app.data.local.sqlite.translatingSQLite
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class FullDeviceSnapshotSQLitePreflightEvidence(
    val archiveDatabase: SnapshotDatabaseFingerprint,
    val sourceDatabaseSchemaVersion: Int,
    val sourceRoomIdentityHash: String,
    val integrityCheckRows: List<String>,
    val foreignKeyViolationCount: Long,
    val tableRowCounts: Map<String, Long>,
)

sealed interface FullDeviceSnapshotSQLitePreflightResult {
    data class Passed(val evidence: FullDeviceSnapshotSQLitePreflightEvidence) :
        FullDeviceSnapshotSQLitePreflightResult

    data class Rejected(val code: FullDeviceSnapshotSQLitePreflightFailureCode) :
        FullDeviceSnapshotSQLitePreflightResult
}

enum class FullDeviceSnapshotSQLitePreflightFailureCode {
    MANIFEST_INVALID,
    DATABASE_NOT_REGULAR_FILE,
    DATABASE_SIDECAR_PRESENT,
    DATABASE_FINGERPRINT_MISMATCH,
    DATABASE_CHANGED_DURING_PREFLIGHT,
    SOURCE_SCHEMA_VERSION_MISMATCH,
    ROOM_IDENTITY_MISMATCH,
    INTEGRITY_CHECK_FAILED,
    FOREIGN_KEY_VIOLATIONS,
    TABLE_MISSING_OR_UNREADABLE,
    TABLE_COUNT_MISMATCH,
    RESULT_LIMIT_EXCEEDED,
    SQLITE_OPEN_FAILED,
    IO_FAILURE,
}

/**
 * Primera capa local de validación SQLite, aislada y de solo lectura. La base se abre con el
 * driver SQLite embebido (`SQLITE_OPEN_READONLY`) en una conexión propia, fuera de Room; todo
 * error del driver se traduce a la jerarquía tipada [SQLiteException].
 *
 * Vuelve a ligar el archivo extraído con el manifiesto, exige una imagen sin WAL/SHM/journal,
 * comprueba `user_version` y el identity hash exportado por Room, y ejecuta `integrity_check`,
 * `foreign_key_check` y los conteos declarados. Nunca abre la base activa ni emite un recibo de
 * activación: todavía faltan los digests canónicos, las invariantes semánticas completas y la
 * correspondencia filas↔archivos privados.
 */
class FullDeviceSnapshotSQLitePreflight {
    fun inspect(
        stagedSnapshot: StagedFullDeviceSnapshot,
    ): FullDeviceSnapshotSQLitePreflightResult {
        val manifest = stagedSnapshot.manifest
        if (FullDeviceSnapshotManifestPolicy.firstViolation(manifest) != null) {
            return rejected(FullDeviceSnapshotSQLitePreflightFailureCode.MANIFEST_INVALID)
        }
        val path = stagedSnapshot.stagedDatabase.toAbsolutePath().normalize()
        return try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return rejected(
                    FullDeviceSnapshotSQLitePreflightFailureCode.DATABASE_NOT_REGULAR_FILE,
                )
            }
            if (hasSidecar(path)) {
                return rejected(
                    FullDeviceSnapshotSQLitePreflightFailureCode.DATABASE_SIDECAR_PRESENT,
                )
            }
            val databaseEntry = manifest.entries.singleOrNull {
                it.kind == FullDeviceSnapshotEntryKind.ROOM_DATABASE
            } ?: return rejected(FullDeviceSnapshotSQLitePreflightFailureCode.MANIFEST_INVALID)
            val archiveFingerprint = fingerprint(path)
            if (
                archiveFingerprint.sizeBytes != databaseEntry.uncompressedSizeBytes ||
                archiveFingerprint.sha256 != databaseEntry.sha256
            ) {
                return rejected(
                    FullDeviceSnapshotSQLitePreflightFailureCode.DATABASE_FINGERPRINT_MISMATCH,
                )
            }

            val database = try {
                // Sin equivalente de NO_LOCALIZED_COLLATORS: el driver embebido nunca registra
                // el collator LOCALIZED de Android.
                translatingSQLite {
                    BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY)
                }
            } catch (_: SQLiteException) {
                return rejected(FullDeviceSnapshotSQLitePreflightFailureCode.SQLITE_OPEN_FAILED)
            }
            database.use { sqlite ->
                val userVersion = singleLong(sqlite, "PRAGMA user_version")
                    ?: return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.SOURCE_SCHEMA_VERSION_MISMATCH,
                    )
                if (userVersion != manifest.sourceDatabaseSchemaVersion.toLong()) {
                    return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.SOURCE_SCHEMA_VERSION_MISMATCH,
                    )
                }
                val expectedIdentity = expectedFacturaStockRoomIdentityHash(userVersion.toInt())
                    ?: return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.SOURCE_SCHEMA_VERSION_MISMATCH,
                    )
                val observedIdentity = singleText(
                    sqlite,
                    "SELECT `identity_hash` FROM `room_master_table` WHERE `id` = 42",
                )
                if (
                    observedIdentity != expectedIdentity ||
                    manifest.sourceRoomIdentityHash != expectedIdentity
                ) {
                    return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.ROOM_IDENTITY_MISMATCH,
                    )
                }

                val integrityRows = textRows(sqlite, "PRAGMA integrity_check")
                    ?: return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.RESULT_LIMIT_EXCEEDED,
                    )
                if (integrityRows != listOf("ok")) {
                    return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.INTEGRITY_CHECK_FAILED,
                    )
                }
                val foreignKeyViolations = countRowsBounded(sqlite, "PRAGMA foreign_key_check")
                    ?: return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.RESULT_LIMIT_EXCEEDED,
                    )
                if (foreignKeyViolations != 0L) {
                    return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.FOREIGN_KEY_VIOLATIONS,
                    )
                }

                val declaredCounts = manifest.tableDigests.associate {
                    it.tableName to it.rowCount
                }
                val observedCounts = linkedMapOf<String, Long>()
                for ((table, expectedCount) in declaredCounts) {
                    val observedCount = try {
                        singleLong(sqlite, "SELECT COUNT(*) FROM `${table}`")
                    } catch (_: SQLiteException) {
                        null
                    } ?: return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode.TABLE_MISSING_OR_UNREADABLE,
                    )
                    if (observedCount != expectedCount) {
                        return rejected(
                            FullDeviceSnapshotSQLitePreflightFailureCode.TABLE_COUNT_MISMATCH,
                        )
                    }
                    observedCounts[table] = observedCount
                }
                if (hasSidecar(path) || fingerprint(path) != archiveFingerprint) {
                    return rejected(
                        FullDeviceSnapshotSQLitePreflightFailureCode
                            .DATABASE_CHANGED_DURING_PREFLIGHT,
                    )
                }
                FullDeviceSnapshotSQLitePreflightResult.Passed(
                    FullDeviceSnapshotSQLitePreflightEvidence(
                        archiveDatabase = archiveFingerprint,
                        sourceDatabaseSchemaVersion = userVersion.toInt(),
                        sourceRoomIdentityHash = observedIdentity,
                        integrityCheckRows = integrityRows,
                        foreignKeyViolationCount = foreignKeyViolations,
                        tableRowCounts = observedCounts.toMap(),
                    ),
                )
            }
        } catch (_: SQLiteException) {
            rejected(FullDeviceSnapshotSQLitePreflightFailureCode.SQLITE_OPEN_FAILED)
        } catch (_: IOException) {
            rejected(FullDeviceSnapshotSQLitePreflightFailureCode.IO_FAILURE)
        } catch (_: ArithmeticException) {
            rejected(FullDeviceSnapshotSQLitePreflightFailureCode.RESULT_LIMIT_EXCEEDED)
        } catch (_: RuntimeException) {
            rejected(FullDeviceSnapshotSQLitePreflightFailureCode.SQLITE_OPEN_FAILED)
        }
    }

    private fun hasSidecar(database: Path): Boolean = SIDECAR_SUFFIXES.any { suffix ->
        val sidecar = database.resolveSibling(database.fileName.toString() + suffix)
        Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
    }

    private fun fingerprint(path: Path): SnapshotDatabaseFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(FINGERPRINT_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size = Math.addExact(size, read.toLong())
                digest.update(buffer, 0, read)
            }
        }
        return SnapshotDatabaseFingerprint(size, digest.digest().toHex())
    }

    private fun singleLong(database: SQLiteConnection, query: String): Long? =
        translatingSQLite {
            database.prepare(query).use { statement ->
                if (!statement.step() || statement.getColumnCount() != 1) {
                    null
                } else {
                    statement.getLong(0)
                }
            }
        }

    private fun singleText(database: SQLiteConnection, query: String): String? = try {
        translatingSQLite {
            database.prepare(query).use { statement ->
                if (!statement.step() || statement.getColumnCount() != 1 || statement.isNull(0)) {
                    null
                } else {
                    statement.getText(0)
                }
            }
        }
    } catch (_: SQLiteException) {
        null
    }

    private fun textRows(database: SQLiteConnection, query: String): List<String>? =
        translatingSQLite {
            database.prepare(query).use { statement ->
                buildList {
                    while (statement.step()) {
                        if (size >= MAX_DIAGNOSTIC_ROWS || statement.getColumnCount() != 1 ||
                            statement.isNull(0)
                        ) {
                            return@translatingSQLite null
                        }
                        add(statement.getText(0))
                    }
                }
            }
        }

    private fun countRowsBounded(database: SQLiteConnection, query: String): Long? =
        translatingSQLite {
            database.prepare(query).use { statement ->
                var count = 0L
                while (statement.step()) {
                    if (count >= MAX_DIAGNOSTIC_ROWS) return@translatingSQLite null
                    count++
                }
                count
            }
        }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
            HEX_DIGITS[byte.toInt() and 0x0f]
    }

    private fun rejected(code: FullDeviceSnapshotSQLitePreflightFailureCode) =
        FullDeviceSnapshotSQLitePreflightResult.Rejected(code)

    private companion object {
        val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
        const val MAX_DIAGNOSTIC_ROWS = 10_000
        const val FINGERPRINT_BUFFER_BYTES = 64 * 1024
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
