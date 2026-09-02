package com.facturastock.app.data.restore

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Fingerprint de un archivo completo observado después de cerrar/checkpointar Room. */
@Serializable
data class SnapshotDatabaseFingerprint(
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
enum class FullDeviceSnapshotSwapState {
    STAGED,
    VALIDATED,
    SWAP_INTENT,
    ORIGINAL_QUARANTINED,
    CANDIDATE_ACTIVATED,
    ACTIVATION_VERIFIED,
    COMPLETE,
    ROLLBACK_INTENT,
    FAILED_CANDIDATE_QUARANTINED,
    ORIGINAL_RESTORED,
    ROLLBACK_VERIFIED,
    ROLLED_BACK,
}

/**
 * Journal externo a Room. El adaptador Android debe persistir cada revisión con temp + fsync +
 * rename atómico + fsync del directorio antes de ejecutar la siguiente acción. Este módulo puro
 * no mueve archivos ni afirma durabilidad que la JVM por sí sola no puede garantizar en Android.
 */
@Serializable
data class FullDeviceSnapshotSwapJournal(
    val journalFormat: String = FullDeviceSnapshotSwapContract.JOURNAL_FORMAT,
    val journalVersion: Int = FullDeviceSnapshotSwapContract.JOURNAL_VERSION,
    val operationId: String,
    val snapshotPayloadSetSha256: String,
    val originalDatabase: SnapshotDatabaseFingerprint,
    val candidateDatabase: SnapshotDatabaseFingerprint,
    val state: FullDeviceSnapshotSwapState,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** Hash del recibo externo de schema/FK/integrity/invariantes; no lo fabrica este coordinador. */
    val databaseValidationReceiptSha256: String? = null,
)

object FullDeviceSnapshotSwapContract {
    const val JOURNAL_FORMAT = "FULL_DEVICE_SNAPSHOT_SWAP_JOURNAL"
    const val JOURNAL_VERSION = 1
}

@Serializable
private data class FullDeviceSnapshotSwapJournalEnvelope(
    val journal: FullDeviceSnapshotSwapJournal,
    val journalSha256: String,
)

enum class FullDeviceSnapshotSwapJournalFailureCode {
    INVALID_JSON,
    NON_CANONICAL,
    CHECKSUM_MISMATCH,
    CONTRACT_VIOLATION,
}

sealed interface FullDeviceSnapshotSwapJournalDecodeResult {
    data class Accepted(val journal: FullDeviceSnapshotSwapJournal) :
        FullDeviceSnapshotSwapJournalDecodeResult

    data class Rejected(val code: FullDeviceSnapshotSwapJournalFailureCode) :
        FullDeviceSnapshotSwapJournalDecodeResult
}

/** Codec canónico con checksum para detectar truncado o corrupción del journal. */
object FullDeviceSnapshotSwapJournalCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    fun encode(journal: FullDeviceSnapshotSwapJournal): ByteArray {
        require(FullDeviceSnapshotSwapJournalPolicy.isValid(journal))
        val journalBytes = encodeJournal(journal)
        val envelope = FullDeviceSnapshotSwapJournalEnvelope(
            journal = journal,
            journalSha256 = FullDeviceSnapshotDigests.sha256(journalBytes),
        )
        return json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): FullDeviceSnapshotSwapJournalDecodeResult {
        val text = try {
            decodeUtf8Strict(bytes)
        } catch (_: Exception) {
            return FullDeviceSnapshotSwapJournalDecodeResult.Rejected(
                FullDeviceSnapshotSwapJournalFailureCode.INVALID_JSON,
            )
        }
        val envelope = try {
            json.decodeFromString<FullDeviceSnapshotSwapJournalEnvelope>(text)
        } catch (_: SerializationException) {
            return FullDeviceSnapshotSwapJournalDecodeResult.Rejected(
                FullDeviceSnapshotSwapJournalFailureCode.INVALID_JSON,
            )
        } catch (_: IllegalArgumentException) {
            return FullDeviceSnapshotSwapJournalDecodeResult.Rejected(
                FullDeviceSnapshotSwapJournalFailureCode.INVALID_JSON,
            )
        }
        val canonicalEnvelope = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(bytes, canonicalEnvelope)) {
            return FullDeviceSnapshotSwapJournalDecodeResult.Rejected(
                FullDeviceSnapshotSwapJournalFailureCode.NON_CANONICAL,
            )
        }
        val actualSha = FullDeviceSnapshotDigests.sha256(encodeJournal(envelope.journal))
        if (!MessageDigest.isEqual(actualSha.toByteArray(), envelope.journalSha256.toByteArray())) {
            return FullDeviceSnapshotSwapJournalDecodeResult.Rejected(
                FullDeviceSnapshotSwapJournalFailureCode.CHECKSUM_MISMATCH,
            )
        }
        if (!FullDeviceSnapshotSwapJournalPolicy.isValid(envelope.journal)) {
            return FullDeviceSnapshotSwapJournalDecodeResult.Rejected(
                FullDeviceSnapshotSwapJournalFailureCode.CONTRACT_VIOLATION,
            )
        }
        return FullDeviceSnapshotSwapJournalDecodeResult.Accepted(envelope.journal)
    }

    private fun encodeJournal(journal: FullDeviceSnapshotSwapJournal): ByteArray =
        json.encodeToString(journal).toByteArray(Charsets.UTF_8)

    private fun decodeUtf8Strict(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
}

object FullDeviceSnapshotSwapJournalPolicy {
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun isValid(journal: FullDeviceSnapshotSwapJournal): Boolean {
        if (journal.journalFormat != FullDeviceSnapshotSwapContract.JOURNAL_FORMAT) return false
        if (journal.journalVersion != FullDeviceSnapshotSwapContract.JOURNAL_VERSION) return false
        if (!isCanonicalUuid(journal.operationId)) return false
        if (!sha256Pattern.matches(journal.snapshotPayloadSetSha256)) return false
        if (!journal.originalDatabase.isValid() || !journal.candidateDatabase.isValid()) return false
        if (journal.originalDatabase == journal.candidateDatabase) return false
        if (journal.revision < 0L) return false
        if (journal.createdAtEpochMillis <= 0L) return false
        if (journal.updatedAtEpochMillis < journal.createdAtEpochMillis) return false
        val receipt = journal.databaseValidationReceiptSha256
        if (journal.state == FullDeviceSnapshotSwapState.STAGED) return receipt == null
        return receipt != null && sha256Pattern.matches(receipt)
    }

    private fun SnapshotDatabaseFingerprint.isValid(): Boolean =
        sizeBytes > 0L && sha256Pattern.matches(sha256)

    private fun isCanonicalUuid(raw: String): Boolean = try {
        UUID.fromString(raw).toString() == raw
    } catch (_: IllegalArgumentException) {
        false
    }
}

/** Observación read-only de los cuatro nombres fijos que usará el adaptador Android. */
data class FullDeviceSnapshotSwapLayout(
    val activeDatabase: SnapshotDatabaseFingerprint?,
    val stagedCandidate: SnapshotDatabaseFingerprint?,
    val quarantinedOriginal: SnapshotDatabaseFingerprint?,
    val quarantinedFailedCandidate: SnapshotDatabaseFingerprint?,
)

enum class FullDeviceSnapshotRecoveryAction {
    VALIDATE_STAGED_DATABASE,
    PERSIST_SWAP_INTENT,
    MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
    RECORD_ORIGINAL_QUARANTINED,
    MOVE_STAGED_CANDIDATE_TO_ACTIVE,
    RECORD_CANDIDATE_ACTIVATED,
    VERIFY_ACTIVE_CANDIDATE,
    RECORD_COMPLETE,
    MOVE_ACTIVE_CANDIDATE_TO_FAILED_QUARANTINE,
    MOVE_STAGED_CANDIDATE_TO_FAILED_QUARANTINE,
    RECORD_FAILED_CANDIDATE_QUARANTINED,
    MOVE_ORIGINAL_QUARANTINE_TO_ACTIVE,
    RECORD_ORIGINAL_RESTORED,
    VERIFY_RESTORED_ORIGINAL,
    RECORD_ROLLED_BACK,
}

sealed interface FullDeviceSnapshotRecoveryDecision {
    data class Execute(val action: FullDeviceSnapshotRecoveryAction) :
        FullDeviceSnapshotRecoveryDecision

    data object NoAction : FullDeviceSnapshotRecoveryDecision

    /** Layout ambiguo, adelantado más de un paso o con fingerprint inesperado: no tocar nada. */
    data object Refuse : FullDeviceSnapshotRecoveryDecision
}

/**
 * Máquina pura que tolera muerte entre cada rename y la persistencia del estado posterior. Para
 * cada estado reconoce tanto el layout previo a la acción como el layout inmediatamente posterior;
 * cualquier mezcla distinta queda cerrada en [FullDeviceSnapshotRecoveryDecision.Refuse].
 */
class FullDeviceSnapshotSwapCoordinator {
    fun begin(
        operationId: String,
        snapshotPayloadSetSha256: String,
        originalDatabase: SnapshotDatabaseFingerprint,
        candidateDatabase: SnapshotDatabaseFingerprint,
        nowEpochMillis: Long,
    ): FullDeviceSnapshotSwapJournal {
        val journal = FullDeviceSnapshotSwapJournal(
            operationId = operationId,
            snapshotPayloadSetSha256 = snapshotPayloadSetSha256,
            originalDatabase = originalDatabase,
            candidateDatabase = candidateDatabase,
            state = FullDeviceSnapshotSwapState.STAGED,
            revision = 0L,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        )
        require(FullDeviceSnapshotSwapJournalPolicy.isValid(journal))
        return journal
    }

    fun transition(
        journal: FullDeviceSnapshotSwapJournal,
        nextState: FullDeviceSnapshotSwapState,
        nowEpochMillis: Long,
        databaseValidationReceipt: FullDeviceSnapshotDatabaseValidationReceipt? = null,
    ): FullDeviceSnapshotSwapJournal {
        require(FullDeviceSnapshotSwapJournalPolicy.isValid(journal))
        require(nextState in journal.state.allowedNextStates()) {
            "Transición de swap no permitida"
        }
        require(nowEpochMillis >= journal.updatedAtEpochMillis)
        val receipt = if (
            journal.state == FullDeviceSnapshotSwapState.STAGED &&
            nextState == FullDeviceSnapshotSwapState.VALIDATED
        ) {
            val validated = requireNotNull(databaseValidationReceipt)
            require(validated.snapshotPayloadSetSha256 == journal.snapshotPayloadSetSha256)
            require(validated.validatedCandidateDatabase == journal.candidateDatabase)
            validated.receiptSha256
        } else {
            require(databaseValidationReceipt == null)
            journal.databaseValidationReceiptSha256
        }
        val updated = journal.copy(
            state = nextState,
            revision = Math.addExact(journal.revision, 1L),
            updatedAtEpochMillis = nowEpochMillis,
            databaseValidationReceiptSha256 = receipt,
        )
        require(FullDeviceSnapshotSwapJournalPolicy.isValid(updated))
        return updated
    }

    fun recoveryDecision(
        journal: FullDeviceSnapshotSwapJournal,
        layout: FullDeviceSnapshotSwapLayout,
    ): FullDeviceSnapshotRecoveryDecision {
        if (!FullDeviceSnapshotSwapJournalPolicy.isValid(journal)) {
            return FullDeviceSnapshotRecoveryDecision.Refuse
        }
        val beforeSwap = layout.matches(
            active = journal.originalDatabase,
            staged = journal.candidateDatabase,
            original = null,
            failed = null,
        )
        val originalMoved = layout.matches(
            active = null,
            staged = journal.candidateDatabase,
            original = journal.originalDatabase,
            failed = null,
        )
        val candidateActive = layout.matches(
            active = journal.candidateDatabase,
            staged = null,
            original = journal.originalDatabase,
            failed = null,
        )
        return when (journal.state) {
            FullDeviceSnapshotSwapState.STAGED ->
                if (beforeSwap) execute(FullDeviceSnapshotRecoveryAction.VALIDATE_STAGED_DATABASE)
                else FullDeviceSnapshotRecoveryDecision.Refuse

            FullDeviceSnapshotSwapState.VALIDATED ->
                if (beforeSwap) execute(FullDeviceSnapshotRecoveryAction.PERSIST_SWAP_INTENT)
                else FullDeviceSnapshotRecoveryDecision.Refuse

            FullDeviceSnapshotSwapState.SWAP_INTENT -> when {
                beforeSwap -> execute(
                    FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
                )
                originalMoved -> execute(
                    FullDeviceSnapshotRecoveryAction.RECORD_ORIGINAL_QUARANTINED,
                )
                else -> FullDeviceSnapshotRecoveryDecision.Refuse
            }

            FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED -> when {
                originalMoved -> execute(
                    FullDeviceSnapshotRecoveryAction.MOVE_STAGED_CANDIDATE_TO_ACTIVE,
                )
                candidateActive -> execute(
                    FullDeviceSnapshotRecoveryAction.RECORD_CANDIDATE_ACTIVATED,
                )
                else -> FullDeviceSnapshotRecoveryDecision.Refuse
            }

            FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED ->
                if (candidateActive) execute(
                    FullDeviceSnapshotRecoveryAction.VERIFY_ACTIVE_CANDIDATE,
                ) else FullDeviceSnapshotRecoveryDecision.Refuse

            FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED ->
                if (candidateActive) execute(FullDeviceSnapshotRecoveryAction.RECORD_COMPLETE)
                else FullDeviceSnapshotRecoveryDecision.Refuse

            FullDeviceSnapshotSwapState.COMPLETE ->
                if (candidateActive) FullDeviceSnapshotRecoveryDecision.NoAction
                else FullDeviceSnapshotRecoveryDecision.Refuse

            FullDeviceSnapshotSwapState.ROLLBACK_INTENT -> rollbackIntentDecision(journal, layout)

            FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED ->
                failedCandidateQuarantinedDecision(journal, layout)

            FullDeviceSnapshotSwapState.ORIGINAL_RESTORED ->
                if (layout.isRestoredOriginal(journal)) {
                    execute(FullDeviceSnapshotRecoveryAction.VERIFY_RESTORED_ORIGINAL)
                } else {
                    FullDeviceSnapshotRecoveryDecision.Refuse
                }

            FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED ->
                if (layout.isRestoredOriginal(journal)) {
                    execute(FullDeviceSnapshotRecoveryAction.RECORD_ROLLED_BACK)
                } else {
                    FullDeviceSnapshotRecoveryDecision.Refuse
                }

            FullDeviceSnapshotSwapState.ROLLED_BACK ->
                if (layout.isRestoredOriginal(journal)) {
                    FullDeviceSnapshotRecoveryDecision.NoAction
                } else {
                    FullDeviceSnapshotRecoveryDecision.Refuse
                }
        }
    }

    private fun rollbackIntentDecision(
        journal: FullDeviceSnapshotSwapJournal,
        layout: FullDeviceSnapshotSwapLayout,
    ): FullDeviceSnapshotRecoveryDecision {
        val originalIsSafe = layout.quarantinedOriginal == journal.originalDatabase
        if (!originalIsSafe) {
            return FullDeviceSnapshotRecoveryDecision.Refuse
        }
        if (
            layout.activeDatabase == null &&
            layout.stagedCandidate == null &&
            layout.quarantinedFailedCandidate != null &&
            layout.quarantinedFailedCandidate != journal.originalDatabase
        ) {
            return execute(
                FullDeviceSnapshotRecoveryAction.RECORD_FAILED_CANDIDATE_QUARANTINED,
            )
        }
        if (layout.quarantinedFailedCandidate != null) {
            return FullDeviceSnapshotRecoveryDecision.Refuse
        }
        return when {
            layout.activeDatabase != null &&
                layout.stagedCandidate == null &&
                layout.activeDatabase != journal.originalDatabase -> execute(
                    FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_CANDIDATE_TO_FAILED_QUARANTINE,
                )
            layout.activeDatabase == null && layout.stagedCandidate != null -> execute(
                FullDeviceSnapshotRecoveryAction.MOVE_STAGED_CANDIDATE_TO_FAILED_QUARANTINE,
            )
            else -> FullDeviceSnapshotRecoveryDecision.Refuse
        }
    }

    private fun failedCandidateQuarantinedDecision(
        journal: FullDeviceSnapshotSwapJournal,
        layout: FullDeviceSnapshotSwapLayout,
    ): FullDeviceSnapshotRecoveryDecision {
        val failedCandidatePresent =
            layout.quarantinedFailedCandidate != null &&
                layout.quarantinedFailedCandidate != journal.originalDatabase
        if (!failedCandidatePresent || layout.stagedCandidate != null) {
            return FullDeviceSnapshotRecoveryDecision.Refuse
        }
        return when {
            layout.activeDatabase == null &&
                layout.quarantinedOriginal == journal.originalDatabase -> execute(
                    FullDeviceSnapshotRecoveryAction.MOVE_ORIGINAL_QUARANTINE_TO_ACTIVE,
                )
            layout.activeDatabase == journal.originalDatabase &&
                layout.quarantinedOriginal == null -> execute(
                    FullDeviceSnapshotRecoveryAction.RECORD_ORIGINAL_RESTORED,
                )
            else -> FullDeviceSnapshotRecoveryDecision.Refuse
        }
    }

    private fun FullDeviceSnapshotSwapLayout.matches(
        active: SnapshotDatabaseFingerprint?,
        staged: SnapshotDatabaseFingerprint?,
        original: SnapshotDatabaseFingerprint?,
        failed: SnapshotDatabaseFingerprint?,
    ): Boolean =
        activeDatabase == active &&
            stagedCandidate == staged &&
            quarantinedOriginal == original &&
            quarantinedFailedCandidate == failed

    private fun FullDeviceSnapshotSwapLayout.isRestoredOriginal(
        journal: FullDeviceSnapshotSwapJournal,
    ): Boolean =
        activeDatabase == journal.originalDatabase &&
            stagedCandidate == null &&
            quarantinedOriginal == null &&
            quarantinedFailedCandidate != null &&
            quarantinedFailedCandidate != journal.originalDatabase

    private fun execute(action: FullDeviceSnapshotRecoveryAction) =
        FullDeviceSnapshotRecoveryDecision.Execute(action)

    private fun FullDeviceSnapshotSwapState.allowedNextStates(): Set<FullDeviceSnapshotSwapState> =
        when (this) {
            FullDeviceSnapshotSwapState.STAGED -> setOf(FullDeviceSnapshotSwapState.VALIDATED)
            FullDeviceSnapshotSwapState.VALIDATED -> setOf(FullDeviceSnapshotSwapState.SWAP_INTENT)
            FullDeviceSnapshotSwapState.SWAP_INTENT ->
                setOf(FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED)
            FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED -> setOf(
                FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED,
                FullDeviceSnapshotSwapState.ROLLBACK_INTENT,
            )
            FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED -> setOf(
                FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED,
                FullDeviceSnapshotSwapState.ROLLBACK_INTENT,
            )
            FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED ->
                setOf(
                    FullDeviceSnapshotSwapState.COMPLETE,
                    FullDeviceSnapshotSwapState.ROLLBACK_INTENT,
                )
            FullDeviceSnapshotSwapState.ROLLBACK_INTENT ->
                setOf(FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED)
            FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED ->
                setOf(FullDeviceSnapshotSwapState.ORIGINAL_RESTORED)
            FullDeviceSnapshotSwapState.ORIGINAL_RESTORED ->
                setOf(FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED)
            FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED ->
                setOf(FullDeviceSnapshotSwapState.ROLLED_BACK)
            FullDeviceSnapshotSwapState.COMPLETE,
            FullDeviceSnapshotSwapState.ROLLED_BACK,
            -> emptySet()
        }
}
