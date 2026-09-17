package com.facturastock.app.data.restore

import com.facturastock.app.data.local.FACTURA_STOCK_DATABASE_SCHEMA_VERSION
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Contrato portable separado de `ACCOUNTING_LEDGER`. Este formato describe una copia integral del
 * estado local; no convierte la exportación contable legible en un mecanismo de restauración.
 *
 * Los SHA-256 detectan corrupción accidental. No autentican un archivo controlado por un atacante:
 * el productor portable deberá envolver el contenedor con AEAD antes de habilitar transferencias
 * entre dispositivos. El coordinador de swap nunca interpreta estos hashes como una firma.
 */
@Serializable
data class FullDeviceSnapshotManifest(
    val format: String = FullDeviceSnapshotContract.FORMAT,
    val formatVersion: Int = FullDeviceSnapshotContract.FORMAT_VERSION,
    val databaseCoverage: FullDeviceSnapshotDatabaseCoverage =
        FullDeviceSnapshotDatabaseCoverage.COMPLETE_ROOM_DATABASE_INCLUDING_SALES,
    val requiredRestoreChecks: List<FullDeviceSnapshotRequiredRestoreCheck> =
        FullDeviceSnapshotContract.REQUIRED_V1_RESTORE_CHECKS,
    val sourceDatabaseSchemaVersion: Int,
    val sourceRoomIdentityHash: String,
    val sourceApplicationVersion: String,
    val createdAtEpochMillis: Long,
    val entries: List<FullDeviceSnapshotEntry>,
    val tableDigests: List<FullDeviceSnapshotTableDigest>,
    val payloadSetSha256: String,
) {
    companion object {
        /** Construye el manifiesto en orden canónico y liga el conjunto completo de payloads. */
        fun create(
            sourceDatabaseSchemaVersion: Int,
            sourceRoomIdentityHash: String,
            sourceApplicationVersion: String,
            createdAtEpochMillis: Long,
            entries: List<FullDeviceSnapshotEntry>,
            tableDigests: List<FullDeviceSnapshotTableDigest>,
        ): FullDeviceSnapshotManifest {
            val canonicalEntries = entries.sortedBy { it.path }
            return FullDeviceSnapshotManifest(
                sourceDatabaseSchemaVersion = sourceDatabaseSchemaVersion,
                sourceRoomIdentityHash = sourceRoomIdentityHash,
                sourceApplicationVersion = sourceApplicationVersion,
                createdAtEpochMillis = createdAtEpochMillis,
                entries = canonicalEntries,
                tableDigests = tableDigests.sortedBy { it.tableName },
                payloadSetSha256 = FullDeviceSnapshotDigests.payloadSet(canonicalEntries),
            )
        }
    }
}

@Serializable
data class FullDeviceSnapshotEntry(
    /** Ruta canónica dentro del ZIP; nunca es una ruta absoluta del dispositivo. */
    val path: String,
    val kind: FullDeviceSnapshotEntryKind,
    val uncompressedSizeBytes: Long,
    val sha256: String,
)

@Serializable
enum class FullDeviceSnapshotEntryKind {
    ROOM_DATABASE,
    PRIVATE_FILE,
    SAFE_APP_SETTINGS,
}

@Serializable
enum class FullDeviceSnapshotDatabaseCoverage {
    /** Imagen completa de Room, incluidas ventas y, desde Room v27, deudas y pagos. */
    COMPLETE_ROOM_DATABASE_INCLUDING_SALES,
}

@Serializable
enum class FullDeviceSnapshotRequiredRestoreCheck {
    ARCHIVE_ENTRY_CHECKSUMS,
    ROOM_SCHEMA_IDENTITY,
    FOREIGN_KEY_CHECK,
    INTEGRITY_CHECK,
    TABLE_COUNTS_AND_DIGESTS,
    ACCOUNTING_GRAPH_INVARIANTS,
    PRIVATE_FILE_CHECKSUMS,
}

@Serializable
data class FullDeviceSnapshotTableDigest(
    val tableName: String,
    val rowCount: Long,
    /** Digest de filas en el orden canónico definido por el futuro productor del snapshot. */
    val canonicalRowsSha256: String,
)

data class FullDeviceSnapshotLimits(
    /**
     * El lector acepta la versión Room actual y desde v24. La cadena v24→v25 agrega un índice,
     * v25→v26 agrega los cursores independientes del stream de inventario y v26→v27 agrega
     * deudas/pagos. Un cambio futuro de esquema avanza automáticamente este techo, pero el gate de
     * restauración seguirá exigiendo una ruta de migración explícita antes de abrir la candidata.
     */
    val maxSupportedDatabaseSchemaVersion: Int = FACTURA_STOCK_DATABASE_SCHEMA_VERSION,
    val maxArchiveBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxExpandedBytes: Long = 4L * 1024L * 1024L * 1024L,
    val maxEntryBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maxManifestBytes: Int = 1024 * 1024,
    /** Incluye `manifest.json`. */
    val maxArchiveEntries: Int = 4_096,
    val maxPathUtf8Bytes: Int = 512,
    val maxTableDigests: Int = 256,
) {
    init {
        require(
            maxSupportedDatabaseSchemaVersion >=
                FullDeviceSnapshotContract.MIN_SOURCE_DATABASE_SCHEMA_VERSION,
        )
        require(maxArchiveBytes > 0L)
        require(maxExpandedBytes > 0L)
        require(maxEntryBytes > 0L)
        require(maxManifestBytes > 0)
        require(maxManifestBytes.toLong() <= maxExpandedBytes)
        require(maxArchiveEntries >= 2)
        require(maxPathUtf8Bytes in 1..4_096)
        require(maxTableDigests >= FullDeviceSnapshotContract.REQUIRED_V1_TABLES.size)
    }
}

object FullDeviceSnapshotContract {
    const val FORMAT = "FULL_DEVICE_SNAPSHOT"
    const val FORMAT_VERSION = 1
    const val MIN_SOURCE_DATABASE_SCHEMA_VERSION = 24
    const val MANIFEST_ENTRY_PATH = "manifest.json"
    const val DATABASE_ENTRY_PATH = "database/facturastock.db"
    const val PRIVATE_FILES_PREFIX = "files/"
    const val SAFE_SETTINGS_PREFIX = "settings/"

    val REQUIRED_V1_RESTORE_CHECKS: List<FullDeviceSnapshotRequiredRestoreCheck> =
        FullDeviceSnapshotRequiredRestoreCheck.entries.toList()

    /** Tablas de la versión Room actual que el formato v1 declara incluso cuando estén vacías. */
    val REQUIRED_V1_TABLES: Set<String> = sortedSetOf(
        "audit_events",
        "businesses",
        "captured_page_publications",
        "catalog_sync_links",
        "cloud_business_bindings",
        "debt_payments",
        "debts",
        "inventory_balances",
        "inventory_locations",
        "invoice_inventory_receipts",
        "invoice_drafts",
        "invoice_header_edits",
        "invoice_images",
        "invoice_line_edits",
        "invoice_lines",
        "invoice_ocr_snapshot_pages",
        "invoice_ocr_snapshots",
        "invoice_parsed_results",
        "outbox_operations",
        "pending_sale_checkouts",
        "prepared_purchases",
        "products",
        "purchase_lines",
        "purchases",
        "remote_catalog_changes",
        "remote_movement_summaries",
        "remote_purchase_changes",
        "remote_sync_states",
        "sale_lines",
        "sale_voids",
        "sales",
        "stock_movements",
        "supplier_product_aliases",
        "suppliers",
        "units",
    )

    /**
     * El manifiesto conserva el conjunto de tablas de su esquema de origen. Las imágenes v24-v26
     * preceden a cuentas por cobrar; exigirles tablas de v27 haría imposible restaurarlas antes de
     * ejecutar la cadena de migraciones no destructivas.
     */
    fun requiredV1TablesForSchema(schemaVersion: Int): Set<String> {
        var tables = REQUIRED_V1_TABLES
        if (schemaVersion < DEBT_TABLES_SCHEMA_VERSION) tables = tables - DEBT_TABLES
        if (schemaVersion < 28) tables = tables - setOf("invoice_inventory_receipts", "pending_sale_checkouts")
        if (schemaVersion < 29) tables = tables - "sale_voids"
        return tables
    }

    private const val DEBT_TABLES_SCHEMA_VERSION = 27
    private val DEBT_TABLES = setOf("debts", "debt_payments")
}

/** Razón cerrada: no incluye rutas privadas, nombres comerciales ni mensajes del parser. */
enum class FullDeviceSnapshotManifestViolation {
    WRONG_FORMAT,
    UNSUPPORTED_FORMAT_VERSION,
    INCOMPLETE_DATABASE_COVERAGE,
    REQUIRED_RESTORE_CHECKS_MISMATCH,
    UNSUPPORTED_DATABASE_SCHEMA,
    INVALID_ROOM_IDENTITY,
    INVALID_APPLICATION_VERSION,
    INVALID_CREATED_AT,
    ENTRY_COUNT_OUT_OF_RANGE,
    ENTRY_ORDER_NOT_CANONICAL,
    DUPLICATE_ENTRY,
    INVALID_ENTRY_PATH,
    INVALID_ENTRY_KIND,
    INVALID_ENTRY_SIZE,
    INVALID_ENTRY_SHA256,
    DATABASE_ENTRY_MISSING,
    DATABASE_ENTRY_DUPLICATED,
    EXPANDED_SIZE_LIMIT_EXCEEDED,
    TABLE_COUNT_OUT_OF_RANGE,
    TABLE_ORDER_NOT_CANONICAL,
    DUPLICATE_TABLE,
    INVALID_TABLE,
    REQUIRED_TABLE_MISSING,
    INVALID_PAYLOAD_SET_SHA256,
}

object FullDeviceSnapshotManifestPolicy {
    private val roomIdentityPattern = Regex("[0-9a-f]{32}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val applicationVersionPattern = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,99}")
    private val tableNamePattern = Regex("[a-z][a-z0-9_]{0,127}")

    fun firstViolation(
        manifest: FullDeviceSnapshotManifest,
        limits: FullDeviceSnapshotLimits = FullDeviceSnapshotLimits(),
    ): FullDeviceSnapshotManifestViolation? {
        if (manifest.format != FullDeviceSnapshotContract.FORMAT) {
            return FullDeviceSnapshotManifestViolation.WRONG_FORMAT
        }
        if (manifest.formatVersion != FullDeviceSnapshotContract.FORMAT_VERSION) {
            return FullDeviceSnapshotManifestViolation.UNSUPPORTED_FORMAT_VERSION
        }
        if (
            manifest.databaseCoverage !=
            FullDeviceSnapshotDatabaseCoverage.COMPLETE_ROOM_DATABASE_INCLUDING_SALES
        ) {
            return FullDeviceSnapshotManifestViolation.INCOMPLETE_DATABASE_COVERAGE
        }
        if (manifest.requiredRestoreChecks != FullDeviceSnapshotContract.REQUIRED_V1_RESTORE_CHECKS) {
            return FullDeviceSnapshotManifestViolation.REQUIRED_RESTORE_CHECKS_MISMATCH
        }
        val supportedSchemas = FullDeviceSnapshotContract.MIN_SOURCE_DATABASE_SCHEMA_VERSION..
            limits.maxSupportedDatabaseSchemaVersion
        if (manifest.sourceDatabaseSchemaVersion !in supportedSchemas) {
            return FullDeviceSnapshotManifestViolation.UNSUPPORTED_DATABASE_SCHEMA
        }
        if (!roomIdentityPattern.matches(manifest.sourceRoomIdentityHash)) {
            return FullDeviceSnapshotManifestViolation.INVALID_ROOM_IDENTITY
        }
        if (!applicationVersionPattern.matches(manifest.sourceApplicationVersion)) {
            return FullDeviceSnapshotManifestViolation.INVALID_APPLICATION_VERSION
        }
        if (manifest.createdAtEpochMillis <= 0L) {
            return FullDeviceSnapshotManifestViolation.INVALID_CREATED_AT
        }
        if (manifest.entries.isEmpty() || manifest.entries.size + 1 > limits.maxArchiveEntries) {
            return FullDeviceSnapshotManifestViolation.ENTRY_COUNT_OUT_OF_RANGE
        }
        if (manifest.entries != manifest.entries.sortedBy { it.path }) {
            return FullDeviceSnapshotManifestViolation.ENTRY_ORDER_NOT_CANONICAL
        }

        val entryKeys = mutableSetOf<String>()
        var databaseEntries = 0
        var expandedBytes = 0L
        for (entry in manifest.entries) {
            if (!FullDeviceSnapshotPathPolicy.isCanonical(entry.path, limits.maxPathUtf8Bytes)) {
                return FullDeviceSnapshotManifestViolation.INVALID_ENTRY_PATH
            }
            if (!entryKeys.add(FullDeviceSnapshotPathPolicy.collisionKey(entry.path))) {
                return FullDeviceSnapshotManifestViolation.DUPLICATE_ENTRY
            }
            when (entry.kind) {
                FullDeviceSnapshotEntryKind.ROOM_DATABASE -> {
                    databaseEntries += 1
                    if (entry.path != FullDeviceSnapshotContract.DATABASE_ENTRY_PATH) {
                        return FullDeviceSnapshotManifestViolation.INVALID_ENTRY_KIND
                    }
                }

                FullDeviceSnapshotEntryKind.PRIVATE_FILE ->
                    if (!entry.path.startsWith(FullDeviceSnapshotContract.PRIVATE_FILES_PREFIX)) {
                        return FullDeviceSnapshotManifestViolation.INVALID_ENTRY_KIND
                    }

                FullDeviceSnapshotEntryKind.SAFE_APP_SETTINGS ->
                    if (!entry.path.startsWith(FullDeviceSnapshotContract.SAFE_SETTINGS_PREFIX)) {
                        return FullDeviceSnapshotManifestViolation.INVALID_ENTRY_KIND
                    }
            }
            if (entry.uncompressedSizeBytes <= 0L || entry.uncompressedSizeBytes > limits.maxEntryBytes) {
                return FullDeviceSnapshotManifestViolation.INVALID_ENTRY_SIZE
            }
            if (!sha256Pattern.matches(entry.sha256)) {
                return FullDeviceSnapshotManifestViolation.INVALID_ENTRY_SHA256
            }
            if (expandedBytes > limits.maxExpandedBytes - entry.uncompressedSizeBytes) {
                return FullDeviceSnapshotManifestViolation.EXPANDED_SIZE_LIMIT_EXCEEDED
            }
            expandedBytes += entry.uncompressedSizeBytes
        }
        if (databaseEntries == 0) {
            return FullDeviceSnapshotManifestViolation.DATABASE_ENTRY_MISSING
        }
        if (databaseEntries != 1) {
            return FullDeviceSnapshotManifestViolation.DATABASE_ENTRY_DUPLICATED
        }

        if (
            manifest.tableDigests.isEmpty() ||
            manifest.tableDigests.size > limits.maxTableDigests
        ) {
            return FullDeviceSnapshotManifestViolation.TABLE_COUNT_OUT_OF_RANGE
        }
        if (manifest.tableDigests != manifest.tableDigests.sortedBy { it.tableName }) {
            return FullDeviceSnapshotManifestViolation.TABLE_ORDER_NOT_CANONICAL
        }
        val tableNames = mutableSetOf<String>()
        for (table in manifest.tableDigests) {
            if (!tableNames.add(table.tableName)) {
                return FullDeviceSnapshotManifestViolation.DUPLICATE_TABLE
            }
            if (
                !tableNamePattern.matches(table.tableName) ||
                table.rowCount < 0L ||
                !sha256Pattern.matches(table.canonicalRowsSha256)
            ) {
                return FullDeviceSnapshotManifestViolation.INVALID_TABLE
            }
        }
        val requiredTables = FullDeviceSnapshotContract.requiredV1TablesForSchema(
            manifest.sourceDatabaseSchemaVersion,
        )
        if (!tableNames.containsAll(requiredTables)) {
            return FullDeviceSnapshotManifestViolation.REQUIRED_TABLE_MISSING
        }
        if (
            manifest.payloadSetSha256 !=
            FullDeviceSnapshotDigests.payloadSet(manifest.entries)
        ) {
            return FullDeviceSnapshotManifestViolation.INVALID_PAYLOAD_SET_SHA256
        }
        return null
    }
}

object FullDeviceSnapshotManifestCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    fun encode(manifest: FullDeviceSnapshotManifest): ByteArray =
        json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

    /**
     * Acepta solo el JSON exacto que produce [encode]. Esto rechaza claves duplicadas, campos
     * desconocidos, defaults omitidos, whitespace alternativo y UTF-8 malformado.
     */
    fun decodeCanonical(bytes: ByteArray): FullDeviceSnapshotManifest {
        val text = decodeUtf8Strict(bytes)
        val manifest = try {
            json.decodeFromString<FullDeviceSnapshotManifest>(text)
        } catch (failure: SerializationException) {
            throw FullDeviceSnapshotManifestDecodingException(failure)
        } catch (failure: IllegalArgumentException) {
            throw FullDeviceSnapshotManifestDecodingException(failure)
        }
        if (!MessageDigest.isEqual(bytes, encode(manifest))) {
            throw FullDeviceSnapshotManifestNotCanonicalException()
        }
        return manifest
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: Exception) {
        throw FullDeviceSnapshotManifestDecodingException(failure)
    }
}

class FullDeviceSnapshotManifestDecodingException internal constructor(cause: Throwable) :
    Exception(cause)

class FullDeviceSnapshotManifestNotCanonicalException internal constructor() : Exception()

object FullDeviceSnapshotDigests {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Liga nombre, rol, longitud y hash de cada payload; el manifiesto exige orden canónico. */
    fun payloadSet(entries: List<FullDeviceSnapshotEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FullDeviceSnapshotContract.FORMAT.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(FullDeviceSnapshotContract.FORMAT_VERSION.toString().toByteArray(Charsets.UTF_8))
        digest.update('\n'.code.toByte())
        entries.sortedBy { it.path }.forEach { entry ->
            digest.update(entry.kind.name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(entry.path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(entry.uncompressedSizeBytes.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(entry.sha256.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
}

internal object FullDeviceSnapshotPathPolicy {
    private val segmentPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun isCanonical(path: String, maxUtf8Bytes: Int): Boolean {
        if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > maxUtf8Bytes) return false
        if (path.startsWith('/') || path.endsWith('/') || '\\' in path) return false
        val segments = path.split('/')
        if (segments.joinToString("/") != path) return false
        return segments.all { segment ->
            segment != "." && segment != ".." && segmentPattern.matches(segment)
        }
    }

    fun collisionKey(path: String): String = path.lowercase(Locale.ROOT)
}
