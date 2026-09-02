package com.facturastock.app.data.restore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullDeviceSnapshotAndroidFilePrimitivesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = context.noBackupFilesDir.toPath().resolve("restore-test-${UUID.randomUUID()}")
    private val coordinator = FullDeviceSnapshotSwapCoordinator()

    @After
    fun tearDown() {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun journalCreateAndCasAreDurableOnTheAndroidPrivateFilesystem() {
        Files.createDirectory(root)
        val store = FullDeviceSnapshotDurableJournalStore(root)
        val staged = newJournal()
        val validated = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            validationReceipt(staged),
        )

        assertEquals(
            FullDeviceSnapshotJournalPersistResult.Persisted(staged),
            store.create(staged),
        )
        assertEquals(
            FullDeviceSnapshotJournalPersistResult.Persisted(validated),
            store.compareAndSet(staged, validated),
        )
        assertEquals(FullDeviceSnapshotJournalLoadResult.Loaded(validated), store.load())
    }

    @Test
    fun forwardDatabaseRenamesAreAtomicAndRecoverableOnAndroidPrivateFilesystem() {
        Files.createDirectory(root)
        val paths = FullDeviceSnapshotDatabaseFilePaths(
            active = root.resolve("facturastock.db"),
            stagedCandidate = root.resolve(".facturastock.restore-candidate.db"),
            quarantinedOriginal = root.resolve(".facturastock.restore-original.db"),
            quarantinedFailedCandidate = root.resolve(".facturastock.restore-failed.db"),
        )
        Files.write(paths.active, ORIGINAL_BYTES)
        Files.write(paths.stagedCandidate, CANDIDATE_BYTES)
        val staged = newJournal()
        val validated = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            validationReceipt(staged),
        )
        val intent = coordinator.transition(
            validated,
            FullDeviceSnapshotSwapState.SWAP_INTENT,
            NOW + 2L,
        )
        val files = FullDeviceSnapshotAtomicDatabaseFiles(paths)

        assertEquals(
            FullDeviceSnapshotDatabaseFilesResult.Moved,
            files.executeMove(
                FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE,
                intent,
            ),
        )
        val afterKill = files.observe()
        assertTrue(afterKill is FullDeviceSnapshotDatabaseFilesResult.Observed)
        afterKill as FullDeviceSnapshotDatabaseFilesResult.Observed
        assertEquals(
            FullDeviceSnapshotRecoveryDecision.Execute(
                FullDeviceSnapshotRecoveryAction.RECORD_ORIGINAL_QUARANTINED,
            ),
            coordinator.recoveryDecision(intent, afterKill.layout),
        )
    }

    private fun newJournal() = coordinator.begin(
        operationId = "00000000-0000-4000-8000-000000000123",
        snapshotPayloadSetSha256 = "c".repeat(64),
        originalDatabase = fingerprint(ORIGINAL_BYTES),
        candidateDatabase = fingerprint(CANDIDATE_BYTES),
        nowEpochMillis = NOW,
    )

    private fun validationReceipt(journal: FullDeviceSnapshotSwapJournal) =
        FullDeviceSnapshotDatabaseValidationReceipt.verified(
            snapshotPayloadSetSha256 = journal.snapshotPayloadSetSha256,
            validatedCandidateDatabase = journal.candidateDatabase,
            receiptSha256 = "d".repeat(64),
        )

    private fun fingerprint(bytes: ByteArray) = SnapshotDatabaseFingerprint(
        sizeBytes = bytes.size.toLong(),
        sha256 = FullDeviceSnapshotDigests.sha256(bytes),
    )

    private companion object {
        const val NOW = 1_777_777_777_777L
        val ORIGINAL_BYTES = "android-original-database".toByteArray()
        val CANDIDATE_BYTES = "android-validated-candidate".toByteArray()
    }
}
