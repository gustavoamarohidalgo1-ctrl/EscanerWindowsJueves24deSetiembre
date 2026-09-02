package com.facturastock.app.data.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDeviceSnapshotSwapJournalTest {
    private val coordinator = FullDeviceSnapshotSwapCoordinator()

    @Test
    fun `validation receipt is mandatory and every legal transition advances revision`() {
        val staged = newJournal()
        assertEquals(FullDeviceSnapshotSwapState.STAGED, staged.state)
        assertEquals(0L, staged.revision)
        assertEquals(null, staged.databaseValidationReceiptSha256)

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.transition(staged, FullDeviceSnapshotSwapState.VALIDATED, NOW + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.transition(staged, FullDeviceSnapshotSwapState.SWAP_INTENT, NOW + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.transition(
                staged,
                FullDeviceSnapshotSwapState.VALIDATED,
                NOW + 1L,
                OTHER_VALIDATION_RECEIPT,
            )
        }

        val validated = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            VALIDATION_RECEIPT,
        )
        val intent = coordinator.transition(
            validated,
            FullDeviceSnapshotSwapState.SWAP_INTENT,
            NOW + 2L,
        )
        val originalMoved = coordinator.transition(
            intent,
            FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED,
            NOW + 3L,
        )
        val candidateActive = coordinator.transition(
            originalMoved,
            FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED,
            NOW + 4L,
        )
        val verified = coordinator.transition(
            candidateActive,
            FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED,
            NOW + 5L,
        )
        val complete = coordinator.transition(
            verified,
            FullDeviceSnapshotSwapState.COMPLETE,
            NOW + 6L,
        )

        assertEquals(6L, complete.revision)
        assertEquals(VALIDATION_RECEIPT.receiptSha256, complete.databaseValidationReceiptSha256)
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.transition(complete, FullDeviceSnapshotSwapState.ROLLED_BACK, NOW + 7L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.transition(
                verified,
                FullDeviceSnapshotSwapState.COMPLETE,
                NOW - 1L,
            )
        }
    }

    @Test
    fun `canonical journal round trips and rejects truncation noncanonical and tampering`() {
        val journal = journalAt(FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED)
        val encoded = FullDeviceSnapshotSwapJournalCodec.encode(journal)

        assertEquals(
            FullDeviceSnapshotSwapJournalDecodeResult.Accepted(journal),
            FullDeviceSnapshotSwapJournalCodec.decode(encoded),
        )
        assertRejectedJournal(
            FullDeviceSnapshotSwapJournalCodec.decode(encoded.copyOf(encoded.size - 3)),
            FullDeviceSnapshotSwapJournalFailureCode.INVALID_JSON,
        )
        assertRejectedJournal(
            FullDeviceSnapshotSwapJournalCodec.decode(encoded + '\n'.code.toByte()),
            FullDeviceSnapshotSwapJournalFailureCode.NON_CANONICAL,
        )

        val encodedText = encoded.toString(Charsets.UTF_8)
        val tamperedSha = "f" + ORIGINAL.sha256.drop(1)
        val tampered = encodedText.replaceFirst(ORIGINAL.sha256, tamperedSha).toByteArray()
        assertRejectedJournal(
            FullDeviceSnapshotSwapJournalCodec.decode(tampered),
            FullDeviceSnapshotSwapJournalFailureCode.CHECKSUM_MISMATCH,
        )
    }

    @Test
    fun `forward recovery recognizes both sides of every kill window`() {
        val before = layout(active = ORIGINAL, staged = CANDIDATE)
        val originalMoved = layout(staged = CANDIDATE, original = ORIGINAL)
        val candidateActive = layout(active = CANDIDATE, original = ORIGINAL)

        assertAction(
            journalAt(FullDeviceSnapshotSwapState.STAGED),
            before,
            FullDeviceSnapshotRecoveryAction.VALIDATE_STAGED_DATABASE,
        )
        assertAction(
            journalAt(FullDeviceSnapshotSwapState.VALIDATED),
            before,
            FullDeviceSnapshotRecoveryAction.PERSIST_SWAP_INTENT,
        )
        assertAction(
            journalAt(FullDeviceSnapshotSwapState.SWAP_INTENT),
            before,
            FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
        )
        assertAction(
            journalAt(FullDeviceSnapshotSwapState.SWAP_INTENT),
            originalMoved,
            FullDeviceSnapshotRecoveryAction.RECORD_ORIGINAL_QUARANTINED,
        )
        assertAction(
            journalAt(FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED),
            originalMoved,
            FullDeviceSnapshotRecoveryAction.MOVE_STAGED_CANDIDATE_TO_ACTIVE,
        )
        assertAction(
            journalAt(FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED),
            candidateActive,
            FullDeviceSnapshotRecoveryAction.RECORD_CANDIDATE_ACTIVATED,
        )
        assertAction(
            journalAt(FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED),
            candidateActive,
            FullDeviceSnapshotRecoveryAction.VERIFY_ACTIVE_CANDIDATE,
        )
        assertAction(
            journalAt(FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED),
            candidateActive,
            FullDeviceSnapshotRecoveryAction.RECORD_COMPLETE,
        )
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.NoAction,
            coordinator.recoveryDecision(
                journalAt(FullDeviceSnapshotSwapState.COMPLETE),
                candidateActive,
            ),
        )
    }

    @Test
    fun `rollback recovery recognizes kills before and after both quarantine renames`() {
        val rollbackFromActive = journalAt(FullDeviceSnapshotSwapState.ROLLBACK_INTENT)
        val activeCandidate = layout(active = CANDIDATE, original = ORIGINAL)
        assertAction(
            rollbackFromActive,
            activeCandidate,
            FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_CANDIDATE_TO_FAILED_QUARANTINE,
        )

        val rollbackBeforeActivation = rollbackBeforeCandidateActivation()
        val stagedCandidate = layout(staged = CANDIDATE, original = ORIGINAL)
        assertAction(
            rollbackBeforeActivation,
            stagedCandidate,
            FullDeviceSnapshotRecoveryAction.MOVE_STAGED_CANDIDATE_TO_FAILED_QUARANTINE,
        )

        val failedQuarantined = layout(original = ORIGINAL, failed = CANDIDATE)
        assertAction(
            rollbackFromActive,
            failedQuarantined,
            FullDeviceSnapshotRecoveryAction.RECORD_FAILED_CANDIDATE_QUARANTINED,
        )
        val failedRecorded = coordinator.transition(
            rollbackFromActive,
            FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED,
            rollbackFromActive.updatedAtEpochMillis + 1L,
        )
        assertAction(
            failedRecorded,
            failedQuarantined,
            FullDeviceSnapshotRecoveryAction.MOVE_ORIGINAL_QUARANTINE_TO_ACTIVE,
        )

        val originalRestoredLayout = layout(active = ORIGINAL, failed = CANDIDATE)
        assertAction(
            failedRecorded,
            originalRestoredLayout,
            FullDeviceSnapshotRecoveryAction.RECORD_ORIGINAL_RESTORED,
        )
        val originalRestored = coordinator.transition(
            failedRecorded,
            FullDeviceSnapshotSwapState.ORIGINAL_RESTORED,
            failedRecorded.updatedAtEpochMillis + 1L,
        )
        assertAction(
            originalRestored,
            originalRestoredLayout,
            FullDeviceSnapshotRecoveryAction.VERIFY_RESTORED_ORIGINAL,
        )
        val rollbackVerified = coordinator.transition(
            originalRestored,
            FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED,
            originalRestored.updatedAtEpochMillis + 1L,
        )
        assertAction(
            rollbackVerified,
            originalRestoredLayout,
            FullDeviceSnapshotRecoveryAction.RECORD_ROLLED_BACK,
        )
        val rolledBack = coordinator.transition(
            rollbackVerified,
            FullDeviceSnapshotSwapState.ROLLED_BACK,
            rollbackVerified.updatedAtEpochMillis + 1L,
        )
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.NoAction,
            coordinator.recoveryDecision(rolledBack, originalRestoredLayout),
        )
    }

    @Test
    fun `ambiguous mixed and two-steps-ahead layouts always fail closed`() {
        val candidateActive = layout(active = CANDIDATE, original = ORIGINAL)
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Refuse,
            coordinator.recoveryDecision(
                journalAt(FullDeviceSnapshotSwapState.SWAP_INTENT),
                candidateActive,
            ),
        )
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Refuse,
            coordinator.recoveryDecision(
                journalAt(FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED),
                layout(active = UNKNOWN, original = ORIGINAL),
            ),
        )
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Refuse,
            coordinator.recoveryDecision(
                journalAt(FullDeviceSnapshotSwapState.ROLLBACK_INTENT),
                layout(active = CANDIDATE, original = null),
            ),
        )
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Refuse,
            coordinator.recoveryDecision(
                journalAt(FullDeviceSnapshotSwapState.COMPLETE),
                layout(active = ORIGINAL, original = ORIGINAL),
            ),
        )
    }

    private fun newJournal(): FullDeviceSnapshotSwapJournal = coordinator.begin(
        operationId = OPERATION_ID,
        snapshotPayloadSetSha256 = SNAPSHOT_SHA,
        originalDatabase = ORIGINAL,
        candidateDatabase = CANDIDATE,
        nowEpochMillis = NOW,
    )

    private fun journalAt(target: FullDeviceSnapshotSwapState): FullDeviceSnapshotSwapJournal {
        var journal = newJournal()
        if (target == FullDeviceSnapshotSwapState.STAGED) return journal
        journal = coordinator.transition(
            journal,
            FullDeviceSnapshotSwapState.VALIDATED,
            journal.updatedAtEpochMillis + 1L,
            VALIDATION_RECEIPT,
        )
        if (target == FullDeviceSnapshotSwapState.VALIDATED) return journal
        journal = coordinator.transition(
            journal,
            FullDeviceSnapshotSwapState.SWAP_INTENT,
            journal.updatedAtEpochMillis + 1L,
        )
        if (target == FullDeviceSnapshotSwapState.SWAP_INTENT) return journal
        journal = coordinator.transition(
            journal,
            FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED,
            journal.updatedAtEpochMillis + 1L,
        )
        if (target == FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED) return journal
        journal = coordinator.transition(
            journal,
            FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED,
            journal.updatedAtEpochMillis + 1L,
        )
        if (target == FullDeviceSnapshotSwapState.CANDIDATE_ACTIVATED) return journal
        if (
            target == FullDeviceSnapshotSwapState.ROLLBACK_INTENT ||
            target == FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED ||
            target == FullDeviceSnapshotSwapState.ORIGINAL_RESTORED ||
            target == FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED ||
            target == FullDeviceSnapshotSwapState.ROLLED_BACK
        ) {
            journal = coordinator.transition(
                journal,
                FullDeviceSnapshotSwapState.ROLLBACK_INTENT,
                journal.updatedAtEpochMillis + 1L,
            )
            if (target == FullDeviceSnapshotSwapState.ROLLBACK_INTENT) return journal
            journal = coordinator.transition(
                journal,
                FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED,
                journal.updatedAtEpochMillis + 1L,
            )
            if (target == FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED) return journal
            journal = coordinator.transition(
                journal,
                FullDeviceSnapshotSwapState.ORIGINAL_RESTORED,
                journal.updatedAtEpochMillis + 1L,
            )
            if (target == FullDeviceSnapshotSwapState.ORIGINAL_RESTORED) return journal
            journal = coordinator.transition(
                journal,
                FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED,
                journal.updatedAtEpochMillis + 1L,
            )
            if (target == FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED) return journal
            return coordinator.transition(
                journal,
                FullDeviceSnapshotSwapState.ROLLED_BACK,
                journal.updatedAtEpochMillis + 1L,
            )
        }
        journal = coordinator.transition(
            journal,
            FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED,
            journal.updatedAtEpochMillis + 1L,
        )
        if (target == FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED) return journal
        return coordinator.transition(
            journal,
            FullDeviceSnapshotSwapState.COMPLETE,
            journal.updatedAtEpochMillis + 1L,
        )
    }

    private fun rollbackBeforeCandidateActivation(): FullDeviceSnapshotSwapJournal {
        val originalMoved = journalAt(FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED)
        return coordinator.transition(
            originalMoved,
            FullDeviceSnapshotSwapState.ROLLBACK_INTENT,
            originalMoved.updatedAtEpochMillis + 1L,
        )
    }

    private fun layout(
        active: SnapshotDatabaseFingerprint? = null,
        staged: SnapshotDatabaseFingerprint? = null,
        original: SnapshotDatabaseFingerprint? = null,
        failed: SnapshotDatabaseFingerprint? = null,
    ) = FullDeviceSnapshotSwapLayout(
        activeDatabase = active,
        stagedCandidate = staged,
        quarantinedOriginal = original,
        quarantinedFailedCandidate = failed,
    )

    private fun assertAction(
        journal: FullDeviceSnapshotSwapJournal,
        layout: FullDeviceSnapshotSwapLayout,
        expected: FullDeviceSnapshotRecoveryAction,
    ) {
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Execute(expected),
            coordinator.recoveryDecision(journal, layout),
        )
    }

    private fun assertRejectedJournal(
        result: FullDeviceSnapshotSwapJournalDecodeResult,
        expected: FullDeviceSnapshotSwapJournalFailureCode,
    ) {
        assertTrue(result is FullDeviceSnapshotSwapJournalDecodeResult.Rejected)
        result as FullDeviceSnapshotSwapJournalDecodeResult.Rejected
        assertEquals(expected, result.code)
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000123"
        const val NOW = 1_777_777_777_777L
        val ORIGINAL = SnapshotDatabaseFingerprint(1_024L, "a".repeat(64))
        val CANDIDATE = SnapshotDatabaseFingerprint(2_048L, "b".repeat(64))
        val UNKNOWN = SnapshotDatabaseFingerprint(3_072L, "c".repeat(64))
        val MANIFEST = FullDeviceSnapshotManifest.create(
            sourceDatabaseSchemaVersion = 24,
            sourceRoomIdentityHash = "d".repeat(32),
            sourceApplicationVersion = "1.0.0-test",
            createdAtEpochMillis = NOW,
            entries = listOf(
                FullDeviceSnapshotEntry(
                    path = FullDeviceSnapshotContract.DATABASE_ENTRY_PATH,
                    kind = FullDeviceSnapshotEntryKind.ROOM_DATABASE,
                    uncompressedSizeBytes = CANDIDATE.sizeBytes,
                    sha256 = CANDIDATE.sha256,
                ),
            ),
            tableDigests = FullDeviceSnapshotContract.requiredV1TablesForSchema(24).map { table ->
                FullDeviceSnapshotTableDigest(
                    tableName = table,
                    rowCount = 0L,
                    canonicalRowsSha256 = FullDeviceSnapshotDigests.sha256(table.toByteArray()),
                )
            },
        )
        val SNAPSHOT_SHA = MANIFEST.payloadSetSha256
        val VALIDATION_RECEIPT = (
            FullDeviceSnapshotDatabaseValidationReceiptIssuer.issue(
                MANIFEST,
                FullDeviceSnapshotDatabaseValidationEvidence(
                    snapshotPayloadSetSha256 = MANIFEST.payloadSetSha256,
                    archiveDatabase = CANDIDATE,
                    validatedCandidateDatabase = CANDIDATE,
                    archiveEntryChecksumsVerified = true,
                    roomSchemaIdentityValidated = true,
                    foreignKeyViolationCount = 0L,
                    integrityCheckRows = listOf("ok"),
                    passedInvariants =
                        FullDeviceSnapshotDatabaseValidationContract.REQUIRED_INVARIANTS,
                    privateFileChecksumsVerified = true,
                ),
            ) as FullDeviceSnapshotDatabaseValidationReceiptResult.Issued
            ).receipt
        val OTHER_CANDIDATE = SnapshotDatabaseFingerprint(4_096L, "e".repeat(64))
        val OTHER_MANIFEST = FullDeviceSnapshotManifest.create(
            sourceDatabaseSchemaVersion = 24,
            sourceRoomIdentityHash = "d".repeat(32),
            sourceApplicationVersion = "1.0.0-test",
            createdAtEpochMillis = NOW,
            entries = listOf(
                FullDeviceSnapshotEntry(
                    path = FullDeviceSnapshotContract.DATABASE_ENTRY_PATH,
                    kind = FullDeviceSnapshotEntryKind.ROOM_DATABASE,
                    uncompressedSizeBytes = OTHER_CANDIDATE.sizeBytes,
                    sha256 = OTHER_CANDIDATE.sha256,
                ),
            ),
            tableDigests = MANIFEST.tableDigests,
        )
        val OTHER_VALIDATION_RECEIPT = (
            FullDeviceSnapshotDatabaseValidationReceiptIssuer.issue(
                OTHER_MANIFEST,
                FullDeviceSnapshotDatabaseValidationEvidence(
                    snapshotPayloadSetSha256 = OTHER_MANIFEST.payloadSetSha256,
                    archiveDatabase = OTHER_CANDIDATE,
                    validatedCandidateDatabase = OTHER_CANDIDATE,
                    archiveEntryChecksumsVerified = true,
                    roomSchemaIdentityValidated = true,
                    foreignKeyViolationCount = 0L,
                    integrityCheckRows = listOf("ok"),
                    passedInvariants =
                        FullDeviceSnapshotDatabaseValidationContract.REQUIRED_INVARIANTS,
                    privateFileChecksumsVerified = true,
                ),
            ) as FullDeviceSnapshotDatabaseValidationReceiptResult.Issued
            ).receipt
    }
}
