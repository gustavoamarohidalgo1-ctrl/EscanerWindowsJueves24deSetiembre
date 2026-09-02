package com.facturastock.app.data.restore

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class FullDeviceSnapshotAccountingInvariant {
    TABLE_COUNTS_AND_DIGESTS_MATCH,
    TENANT_OWNERSHIP_VALID,
    PURCHASE_GRAPHS_COMPLETE,
    SALE_GRAPHS_COMPLETE,
    BALANCES_MATCH_MOVEMENT_REPLAY,
    OUTBOX_PAYLOADS_AND_IDEMPOTENCY_VALID,
    DRAFT_DERIVED_PAYLOADS_VALID,
    PRIVATE_FILES_MATCH_ROWS,
}

object FullDeviceSnapshotDatabaseValidationContract {
    val REQUIRED_INVARIANTS: List<FullDeviceSnapshotAccountingInvariant> =
        FullDeviceSnapshotAccountingInvariant.entries.toList()
}

/**
 * Evidencia producida por un futuro validador SQLite aislado. Un booleano no basta: FK e integrity
 * conservan sus resultados cerrados y las invariantes exigidas se enumeran de forma exhaustiva.
 */
data class FullDeviceSnapshotDatabaseValidationEvidence(
    val snapshotPayloadSetSha256: String,
    /** Fingerprint del DB exactamente como venía en el ZIP, antes de una migración. */
    val archiveDatabase: SnapshotDatabaseFingerprint,
    /** Fingerprint de la candidata ya migrada y validada que eventualmente se activaría. */
    val validatedCandidateDatabase: SnapshotDatabaseFingerprint,
    val archiveEntryChecksumsVerified: Boolean,
    val roomSchemaIdentityValidated: Boolean,
    val foreignKeyViolationCount: Long,
    /** SQLite debe devolver exactamente una fila `ok`; cualquier otra forma se rechaza. */
    val integrityCheckRows: List<String>,
    val passedInvariants: List<FullDeviceSnapshotAccountingInvariant>,
    val privateFileChecksumsVerified: Boolean,
)

class FullDeviceSnapshotDatabaseValidationReceipt private constructor(
    val snapshotPayloadSetSha256: String,
    val validatedCandidateDatabase: SnapshotDatabaseFingerprint,
    val receiptSha256: String,
) {
    companion object {
        internal fun verified(
            snapshotPayloadSetSha256: String,
            validatedCandidateDatabase: SnapshotDatabaseFingerprint,
            receiptSha256: String,
        ) = FullDeviceSnapshotDatabaseValidationReceipt(
            snapshotPayloadSetSha256 = snapshotPayloadSetSha256,
            validatedCandidateDatabase = validatedCandidateDatabase,
            receiptSha256 = receiptSha256,
        )
    }
}

enum class FullDeviceSnapshotDatabaseValidationFailureCode {
    MANIFEST_INVALID,
    SNAPSHOT_MISMATCH,
    ARCHIVE_DATABASE_MISMATCH,
    ARCHIVE_CHECKSUMS_NOT_VERIFIED,
    ROOM_SCHEMA_NOT_VALIDATED,
    FOREIGN_KEY_VIOLATIONS,
    INTEGRITY_CHECK_FAILED,
    INVARIANTS_INCOMPLETE,
    PRIVATE_FILE_CHECKSUMS_NOT_VERIFIED,
    INVALID_CANDIDATE_FINGERPRINT,
}

sealed interface FullDeviceSnapshotDatabaseValidationReceiptResult {
    data class Issued(val receipt: FullDeviceSnapshotDatabaseValidationReceipt) :
        FullDeviceSnapshotDatabaseValidationReceiptResult

    data class Rejected(val code: FullDeviceSnapshotDatabaseValidationFailureCode) :
        FullDeviceSnapshotDatabaseValidationReceiptResult
}

/** Solo emite un recibo si todas las capas requeridas pasaron; no abre ni modifica SQLite. */
object FullDeviceSnapshotDatabaseValidationReceiptIssuer {
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun issue(
        manifest: FullDeviceSnapshotManifest,
        evidence: FullDeviceSnapshotDatabaseValidationEvidence,
    ): FullDeviceSnapshotDatabaseValidationReceiptResult {
        if (FullDeviceSnapshotManifestPolicy.firstViolation(manifest) != null) {
            return rejected(FullDeviceSnapshotDatabaseValidationFailureCode.MANIFEST_INVALID)
        }
        if (evidence.snapshotPayloadSetSha256 != manifest.payloadSetSha256) {
            return rejected(FullDeviceSnapshotDatabaseValidationFailureCode.SNAPSHOT_MISMATCH)
        }
        val databaseEntry = manifest.entries.singleOrNull {
            it.kind == FullDeviceSnapshotEntryKind.ROOM_DATABASE
        } ?: return rejected(FullDeviceSnapshotDatabaseValidationFailureCode.MANIFEST_INVALID)
        if (
            evidence.archiveDatabase.sizeBytes != databaseEntry.uncompressedSizeBytes ||
            evidence.archiveDatabase.sha256 != databaseEntry.sha256
        ) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.ARCHIVE_DATABASE_MISMATCH,
            )
        }
        if (!evidence.archiveEntryChecksumsVerified) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.ARCHIVE_CHECKSUMS_NOT_VERIFIED,
            )
        }
        if (!evidence.roomSchemaIdentityValidated) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.ROOM_SCHEMA_NOT_VALIDATED,
            )
        }
        if (evidence.foreignKeyViolationCount != 0L) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.FOREIGN_KEY_VIOLATIONS,
            )
        }
        if (evidence.integrityCheckRows != listOf("ok")) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.INTEGRITY_CHECK_FAILED,
            )
        }
        if (
            evidence.passedInvariants !=
            FullDeviceSnapshotDatabaseValidationContract.REQUIRED_INVARIANTS
        ) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.INVARIANTS_INCOMPLETE,
            )
        }
        if (!evidence.privateFileChecksumsVerified) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.PRIVATE_FILE_CHECKSUMS_NOT_VERIFIED,
            )
        }
        if (!evidence.validatedCandidateDatabase.isValid()) {
            return rejected(
                FullDeviceSnapshotDatabaseValidationFailureCode.INVALID_CANDIDATE_FINGERPRINT,
            )
        }
        val receiptHash = FullDeviceSnapshotDigests.sha256(
            buildString {
                append(FullDeviceSnapshotContract.FORMAT)
                append('\u0000')
                append(manifest.payloadSetSha256)
                append('\u0000')
                append(evidence.validatedCandidateDatabase.sizeBytes)
                append('\u0000')
                append(evidence.validatedCandidateDatabase.sha256)
                append('\u0000')
                append(evidence.passedInvariants.joinToString(",") { it.name })
            }.toByteArray(Charsets.UTF_8),
        )
        return FullDeviceSnapshotDatabaseValidationReceiptResult.Issued(
            FullDeviceSnapshotDatabaseValidationReceipt.verified(
                snapshotPayloadSetSha256 = manifest.payloadSetSha256,
                validatedCandidateDatabase = evidence.validatedCandidateDatabase,
                receiptSha256 = receiptHash,
            ),
        )
    }

    private fun SnapshotDatabaseFingerprint.isValid(): Boolean =
        sizeBytes > 0L && sha256Pattern.matches(sha256)

    private fun rejected(code: FullDeviceSnapshotDatabaseValidationFailureCode) =
        FullDeviceSnapshotDatabaseValidationReceiptResult.Rejected(code)
}

enum class FullDeviceSnapshotRestoreStatus {
    NOT_READY,
}

enum class FullDeviceSnapshotRestoreNotReadyReason {
    ACTIVE_DATABASE_UNAVAILABLE,
    VALIDATION_RECEIPT_REQUIRED,
    VALIDATION_RECEIPT_MISMATCH,
    CANDIDATE_DATABASE_CHANGED,
    PROCESS_WIDE_ROOM_QUIESCE_NOT_IMPLEMENTED,
}

data class FullDeviceSnapshotRestoreAttempt(
    val status: FullDeviceSnapshotRestoreStatus,
    val reason: FullDeviceSnapshotRestoreNotReadyReason,
)

/**
 * Compuerta productiva deliberadamente read-only. Aunque el archivo y el recibo sean válidos,
 * devuelve `NOT_READY` hasta que exista una compuerta de mantenimiento capaz de detener todos los
 * productores de escrituras y reiniciar el proceso después de cerrar el singleton Room. El journal
 * durable, el preflight SQLite y los renames atómicos existen como primitivas internas, pero esta
 * compuerta no simula su coordinación y nunca reemplaza [activeDatabase].
 */
class FullDeviceSnapshotRestoreCoordinator {
    fun requestActivation(
        activeDatabase: Path,
        stagedSnapshot: StagedFullDeviceSnapshot,
        validationReceipt: FullDeviceSnapshotDatabaseValidationReceipt?,
    ): FullDeviceSnapshotRestoreAttempt {
        if (!Files.isRegularFile(activeDatabase, LinkOption.NOFOLLOW_LINKS)) {
            return notReady(FullDeviceSnapshotRestoreNotReadyReason.ACTIVE_DATABASE_UNAVAILABLE)
        }
        if (validationReceipt == null) {
            return notReady(FullDeviceSnapshotRestoreNotReadyReason.VALIDATION_RECEIPT_REQUIRED)
        }
        if (
            validationReceipt.snapshotPayloadSetSha256 !=
            stagedSnapshot.manifest.payloadSetSha256
        ) {
            return notReady(FullDeviceSnapshotRestoreNotReadyReason.VALIDATION_RECEIPT_MISMATCH)
        }
        val observedCandidate = fingerprint(stagedSnapshot.stagedDatabase)
            ?: return notReady(FullDeviceSnapshotRestoreNotReadyReason.CANDIDATE_DATABASE_CHANGED)
        if (observedCandidate != validationReceipt.validatedCandidateDatabase) {
            return notReady(FullDeviceSnapshotRestoreNotReadyReason.CANDIDATE_DATABASE_CHANGED)
        }
        return notReady(
            FullDeviceSnapshotRestoreNotReadyReason.PROCESS_WIDE_ROOM_QUIESCE_NOT_IMPLEMENTED,
        )
    }

    private fun fingerprint(path: Path): SnapshotDatabaseFingerprint? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(FINGERPRINT_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size = Math.addExact(size, read.toLong())
                    digest.update(buffer, 0, read)
                }
            }
            SnapshotDatabaseFingerprint(
                sizeBytes = size,
                sha256 = digest.digest().toHex(),
            )
        } catch (_: IOException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
            HEX_DIGITS[byte.toInt() and 0x0f]
    }

    private fun notReady(reason: FullDeviceSnapshotRestoreNotReadyReason) =
        FullDeviceSnapshotRestoreAttempt(
            status = FullDeviceSnapshotRestoreStatus.NOT_READY,
            reason = reason,
        )

    private companion object {
        const val FINGERPRINT_BUFFER_BYTES = 64 * 1024
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
