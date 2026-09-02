package com.facturastock.app.data.restore

import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDeviceSnapshotAtomicDatabaseFilesTest {
    private val root = Files.createTempDirectory("snapshot-database-files")
    private val paths = FullDeviceSnapshotDatabaseFilePaths(
        active = root.resolve("facturastock.db"),
        stagedCandidate = root.resolve(".facturastock.restore-candidate.db"),
        quarantinedOriginal = root.resolve(".facturastock.restore-original.db"),
        quarantinedFailedCandidate = root.resolve(".facturastock.restore-failed.db"),
    )
    private val adapter = FullDeviceSnapshotAtomicDatabaseFiles(paths)
    private val coordinator = FullDeviceSnapshotSwapCoordinator()

    @After
    fun tearDown() {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(root).use { files ->
                files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `forward atomic renames expose exactly the layouts accepted after process death`() {
        Files.write(paths.active, ORIGINAL_BYTES)
        Files.write(paths.stagedCandidate, CANDIDATE_BYTES)
        val journal = swapIntentJournal()

        assertEquals(
            FullDeviceSnapshotDatabaseFilesResult.Moved,
            adapter.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
                journal,
            ),
        )
        val afterOriginalMove = observedLayout()
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Execute(
                FullDeviceSnapshotRecoveryAction.RECORD_ORIGINAL_QUARANTINED,
            ),
            coordinator.recoveryDecision(journal, afterOriginalMove),
        )

        val originalRecorded = coordinator.transition(
            journal,
            FullDeviceSnapshotSwapState.ORIGINAL_QUARANTINED,
            journal.updatedAtEpochMillis + 1L,
        )
        assertEquals(
            FullDeviceSnapshotDatabaseFilesResult.Moved,
            adapter.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_STAGED_CANDIDATE_TO_ACTIVE,
                originalRecorded,
            ),
        )
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Execute(
                FullDeviceSnapshotRecoveryAction.RECORD_CANDIDATE_ACTIVATED,
            ),
            coordinator.recoveryDecision(originalRecorded, observedLayout()),
        )
    }

    @Test
    fun `destination collision and fingerprint mismatch never overwrite either database`() {
        Files.write(paths.active, ORIGINAL_BYTES)
        Files.write(paths.stagedCandidate, CANDIDATE_BYTES)
        Files.write(paths.quarantinedOriginal, "must-survive".toByteArray())
        val journal = swapIntentJournal()

        assertRefused(
            adapter.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
                journal,
            ),
            FullDeviceSnapshotDatabaseFilesFailureCode.LAYOUT_UNSAFE,
        )
        assertEquals(ORIGINAL_BYTES.toList(), Files.readAllBytes(paths.active).toList())
        assertEquals(
            "must-survive".toByteArray().toList(),
            Files.readAllBytes(paths.quarantinedOriginal).toList(),
        )

        Files.delete(paths.quarantinedOriginal)
        Files.write(paths.active, "changed".toByteArray())
        assertRefused(
            adapter.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
                journal,
            ),
            FullDeviceSnapshotDatabaseFilesFailureCode.LAYOUT_UNSAFE,
        )
        assertFalse(Files.exists(paths.quarantinedOriginal, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `wal shm or rollback journal sidecar blocks observation and every move`() {
        Files.write(paths.active, ORIGINAL_BYTES)
        Files.write(paths.stagedCandidate, CANDIDATE_BYTES)
        val wal = paths.active.resolveSibling(paths.active.fileName.toString() + "-wal")
        Files.write(wal, byteArrayOf(1))

        assertRefused(
            adapter.observe(),
            FullDeviceSnapshotDatabaseFilesFailureCode.SIDECAR_PRESENT,
        )
        assertRefused(
            adapter.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
                newJournal(),
            ),
            FullDeviceSnapshotDatabaseFilesFailureCode.SIDECAR_PRESENT,
        )
        assertTrue(Files.exists(paths.active, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(paths.quarantinedOriginal, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a valid fingerprint cannot move before the journal reaches swap intent`() {
        Files.write(paths.active, ORIGINAL_BYTES)
        Files.write(paths.stagedCandidate, CANDIDATE_BYTES)

        assertRefused(
            adapter.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
                newJournal(),
            ),
            FullDeviceSnapshotDatabaseFilesFailureCode.LAYOUT_UNSAFE,
        )
        assertTrue(Files.exists(paths.active, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(paths.quarantinedOriginal, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `rollback restores the original without replacing an existing active file`() {
        Files.write(paths.quarantinedOriginal, ORIGINAL_BYTES)
        Files.write(paths.quarantinedFailedCandidate, CANDIDATE_BYTES)
        val rollback = newJournal().copy(
            state = FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED,
            revision = 6L,
            databaseValidationReceiptSha256 = RECEIPT_SHA,
        )

        assertEquals(
            FullDeviceSnapshotDatabaseFilesResult.Moved,
            adapter.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_ORIGINAL_QUARANTINE_TO_ACTIVE,
                rollback,
            ),
        )
        assertEquals(ORIGINAL_BYTES.toList(), Files.readAllBytes(paths.active).toList())
        assertEquals(CANDIDATE_BYTES.toList(), Files.readAllBytes(paths.quarantinedFailedCandidate).toList())
    }

    private fun observedLayout(): FullDeviceSnapshotSwapLayout {
        val result = adapter.observe()
        assertTrue(result is FullDeviceSnapshotDatabaseFilesResult.Observed)
        return (result as FullDeviceSnapshotDatabaseFilesResult.Observed).layout
    }

    private fun newJournal() = coordinator.begin(
        operationId = OPERATION_ID,
        snapshotPayloadSetSha256 = SNAPSHOT_SHA,
        originalDatabase = fingerprint(ORIGINAL_BYTES),
        candidateDatabase = fingerprint(CANDIDATE_BYTES),
        nowEpochMillis = NOW,
    )

    private fun swapIntentJournal(): FullDeviceSnapshotSwapJournal {
        val staged = newJournal()
        val receipt = FullDeviceSnapshotDatabaseValidationReceipt.verified(
            snapshotPayloadSetSha256 = SNAPSHOT_SHA,
            validatedCandidateDatabase = staged.candidateDatabase,
            receiptSha256 = RECEIPT_SHA,
        )
        val validated = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            receipt,
        )
        return coordinator.transition(
            validated,
            FullDeviceSnapshotSwapState.SWAP_INTENT,
            NOW + 2L,
        )
    }

    private fun fingerprint(bytes: ByteArray) = SnapshotDatabaseFingerprint(
        sizeBytes = bytes.size.toLong(),
        sha256 = FullDeviceSnapshotDigests.sha256(bytes),
    )

    private fun assertRefused(
        result: FullDeviceSnapshotDatabaseFilesResult,
        code: FullDeviceSnapshotDatabaseFilesFailureCode,
    ) {
        assertEquals(FullDeviceSnapshotDatabaseFilesResult.Refused(code), result)
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000123"
        const val NOW = 1_777_777_777_777L
        const val SNAPSHOT_SHA =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val RECEIPT_SHA =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        val ORIGINAL_BYTES = "original-database".toByteArray()
        val CANDIDATE_BYTES = "validated-candidate".toByteArray()
    }
}
