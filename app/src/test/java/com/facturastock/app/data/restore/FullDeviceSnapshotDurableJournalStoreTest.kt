package com.facturastock.app.data.restore

import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDeviceSnapshotDurableJournalStoreTest {
    private val root = Files.createTempDirectory("snapshot-journal-store")
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
    fun `create and compare-and-set durably round trip exact revisions`() {
        val store = FullDeviceSnapshotDurableJournalStore(root)
        val staged = newJournal()
        val validated = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            VALIDATION_RECEIPT,
        )

        assertEquals(
            FullDeviceSnapshotJournalPersistResult.Persisted(staged),
            store.create(staged),
        )
        assertEquals(FullDeviceSnapshotJournalLoadResult.Loaded(staged), store.load())
        assertEquals(
            FullDeviceSnapshotJournalPersistResult.Persisted(validated),
            store.compareAndSet(staged, validated),
        )
        assertEquals(FullDeviceSnapshotJournalLoadResult.Loaded(validated), store.load())

        val leftoverTemporaries = Files.list(root).use { files ->
            files.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        assertEquals(0L, leftoverTemporaries)
    }

    @Test
    fun `stale writer and another operation cannot replace current journal`() {
        val store = FullDeviceSnapshotDurableJournalStore(root)
        val staged = newJournal()
        val validated = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            VALIDATION_RECEIPT,
        )
        assertTrue(store.create(staged) is FullDeviceSnapshotJournalPersistResult.Persisted)
        assertTrue(
            store.compareAndSet(staged, validated) is
                FullDeviceSnapshotJournalPersistResult.Persisted,
        )

        val staleUpdate = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 2L,
            VALIDATION_RECEIPT,
        )
        assertPersistRefused(
            store.compareAndSet(staged, staleUpdate),
            FullDeviceSnapshotJournalStoreFailureCode.REVISION_MISMATCH,
        )

        val another = newJournal(operationId = "00000000-0000-4000-8000-000000000999")
        val anotherValidated = coordinator.transition(
            another,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            VALIDATION_RECEIPT,
        )
        assertPersistRefused(
            store.compareAndSet(another, anotherValidated),
            FullDeviceSnapshotJournalStoreFailureCode.OPERATION_MISMATCH,
        )
        assertEquals(FullDeviceSnapshotJournalLoadResult.Loaded(validated), store.load())
    }

    @Test
    fun `corrupt existing journal fails closed and is never overwritten`() {
        val journalPath = root.resolve(".full-device-snapshot-swap.json")
        val corrupt = "{truncated".toByteArray()
        Files.write(journalPath, corrupt)
        val store = FullDeviceSnapshotDurableJournalStore(root)

        assertLoadRefused(
            store.load(),
            FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_INVALID,
        )
        assertPersistRefused(
            store.create(newJournal()),
            FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_INVALID,
        )
        assertEquals(corrupt.toList(), Files.readAllBytes(journalPath).toList())
    }

    @Test
    fun `symlink root is refused without reading or creating journal files`() {
        val actual = root.resolve("actual")
        Files.createDirectory(actual)
        val linked = root.resolve("linked")
        Files.createSymbolicLink(linked, actual)

        assertLoadRefused(
            FullDeviceSnapshotDurableJournalStore(linked).load(),
            FullDeviceSnapshotJournalStoreFailureCode.UNSAFE_DIRECTORY,
        )
        assertEquals(0L, Files.list(actual).use { it.count() })
    }

    @Test
    fun `missing expected journal and skipped revision are refused`() {
        val store = FullDeviceSnapshotDurableJournalStore(root)
        val staged = newJournal()
        val validated = coordinator.transition(
            staged,
            FullDeviceSnapshotSwapState.VALIDATED,
            NOW + 1L,
            VALIDATION_RECEIPT,
        )
        assertPersistRefused(
            store.compareAndSet(staged, validated),
            FullDeviceSnapshotJournalStoreFailureCode.EXPECTED_JOURNAL_MISSING,
        )
        val skipped = validated.copy(revision = 2L)
        assertPersistRefused(
            store.compareAndSet(staged, skipped),
            FullDeviceSnapshotJournalStoreFailureCode.NON_SEQUENTIAL_REVISION,
        )
    }

    @Test
    fun `cas rejects skipped state and any mutation of immutable fingerprints`() {
        val store = FullDeviceSnapshotDurableJournalStore(root)
        val staged = newJournal()
        assertTrue(store.create(staged) is FullDeviceSnapshotJournalPersistResult.Persisted)

        val skippedState = staged.copy(
            state = FullDeviceSnapshotSwapState.SWAP_INTENT,
            revision = 1L,
            updatedAtEpochMillis = NOW + 1L,
            databaseValidationReceiptSha256 = "e".repeat(64),
        )
        assertPersistRefused(
            store.compareAndSet(staged, skippedState),
            FullDeviceSnapshotJournalStoreFailureCode.ILLEGAL_STATE_TRANSITION,
        )

        val forgedFingerprint = staged.copy(
            state = FullDeviceSnapshotSwapState.VALIDATED,
            revision = 1L,
            updatedAtEpochMillis = NOW + 1L,
            candidateDatabase = SnapshotDatabaseFingerprint(9_999L, "f".repeat(64)),
            databaseValidationReceiptSha256 = "e".repeat(64),
        )
        assertPersistRefused(
            store.compareAndSet(staged, forgedFingerprint),
            FullDeviceSnapshotJournalStoreFailureCode.ILLEGAL_STATE_TRANSITION,
        )
        assertEquals(FullDeviceSnapshotJournalLoadResult.Loaded(staged), store.load())
    }

    private fun newJournal(operationId: String = OPERATION_ID) = coordinator.begin(
        operationId = operationId,
        snapshotPayloadSetSha256 = SNAPSHOT_SHA,
        originalDatabase = ORIGINAL,
        candidateDatabase = CANDIDATE,
        nowEpochMillis = NOW,
    )

    private fun assertLoadRefused(
        result: FullDeviceSnapshotJournalLoadResult,
        code: FullDeviceSnapshotJournalStoreFailureCode,
    ) {
        assertEquals(FullDeviceSnapshotJournalLoadResult.Refused(code), result)
    }

    private fun assertPersistRefused(
        result: FullDeviceSnapshotJournalPersistResult,
        code: FullDeviceSnapshotJournalStoreFailureCode,
    ) {
        assertEquals(FullDeviceSnapshotJournalPersistResult.Refused(code), result)
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000123"
        const val NOW = 1_777_777_777_777L
        val ORIGINAL = SnapshotDatabaseFingerprint(1_024L, "a".repeat(64))
        val CANDIDATE = SnapshotDatabaseFingerprint(2_048L, "b".repeat(64))
        val SNAPSHOT_SHA = "c".repeat(64)
        val VALIDATION_RECEIPT = FullDeviceSnapshotDatabaseValidationReceipt.verified(
            snapshotPayloadSetSha256 = SNAPSHOT_SHA,
            validatedCandidateDatabase = CANDIDATE,
            receiptSha256 = "d".repeat(64),
        )
    }
}
