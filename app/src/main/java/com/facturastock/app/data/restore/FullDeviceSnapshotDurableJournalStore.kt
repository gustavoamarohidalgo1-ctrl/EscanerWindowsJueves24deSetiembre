package com.facturastock.app.data.restore

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal sealed interface FullDeviceSnapshotJournalLoadResult {
    data object Missing : FullDeviceSnapshotJournalLoadResult

    data class Loaded(val journal: FullDeviceSnapshotSwapJournal) :
        FullDeviceSnapshotJournalLoadResult

    data class Refused(val code: FullDeviceSnapshotJournalStoreFailureCode) :
        FullDeviceSnapshotJournalLoadResult
}

internal sealed interface FullDeviceSnapshotJournalPersistResult {
    data class Persisted(val journal: FullDeviceSnapshotSwapJournal) :
        FullDeviceSnapshotJournalPersistResult

    data class Refused(val code: FullDeviceSnapshotJournalStoreFailureCode) :
        FullDeviceSnapshotJournalPersistResult
}

internal enum class FullDeviceSnapshotJournalStoreFailureCode {
    UNSAFE_DIRECTORY,
    JOURNAL_NOT_REGULAR_FILE,
    JOURNAL_TOO_LARGE,
    JOURNAL_INVALID,
    EXPECTED_JOURNAL_MISSING,
    JOURNAL_ALREADY_EXISTS,
    OPERATION_MISMATCH,
    REVISION_MISMATCH,
    NON_SEQUENTIAL_REVISION,
    ILLEGAL_STATE_TRANSITION,
    ATOMIC_REPLACE_UNAVAILABLE,
    IO_FAILURE,
}

/**
 * Persistencia durable del journal fuera de Room.
 *
 * Cada escritura se serializa con un file lock, se publica desde un temporal del mismo directorio,
 * fuerza primero los bytes, luego exige un rename atómico y finalmente fuerza la entrada del
 * directorio. El CAS por operación/revisión evita que dos procesos hagan retroceder el journal. Un
 * archivo existente corrupto nunca se reemplaza: obliga a intervención manual.
 */
internal class FullDeviceSnapshotDurableJournalStore(
    directory: Path,
) {
    private val root = directory.toAbsolutePath().normalize()
    private val journalPath = root.resolve(JOURNAL_FILE_NAME)
    private val lockPath = root.resolve(LOCK_FILE_NAME)

    fun load(): FullDeviceSnapshotJournalLoadResult = try {
        requireSafeRoot()
        loadUnlocked()
    } catch (_: UnsafeJournalDirectoryException) {
        refusedLoad(FullDeviceSnapshotJournalStoreFailureCode.UNSAFE_DIRECTORY)
    } catch (_: IOException) {
        refusedLoad(FullDeviceSnapshotJournalStoreFailureCode.IO_FAILURE)
    } catch (_: RuntimeException) {
        refusedLoad(FullDeviceSnapshotJournalStoreFailureCode.IO_FAILURE)
    }

    fun create(
        journal: FullDeviceSnapshotSwapJournal,
    ): FullDeviceSnapshotJournalPersistResult {
        if (journal.revision != 0L || !FullDeviceSnapshotSwapJournalPolicy.isValid(journal)) {
            return refusedPersist(
                FullDeviceSnapshotJournalStoreFailureCode.NON_SEQUENTIAL_REVISION,
            )
        }
        return withExclusiveLock {
            when (val current = loadUnlocked()) {
                FullDeviceSnapshotJournalLoadResult.Missing -> persistUnlocked(
                    journal = journal,
                    replaceExisting = false,
                )

                is FullDeviceSnapshotJournalLoadResult.Loaded -> refusedPersist(
                    FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_ALREADY_EXISTS,
                )

                is FullDeviceSnapshotJournalLoadResult.Refused -> refusedPersist(current.code)
            }
        }
    }

    fun compareAndSet(
        expected: FullDeviceSnapshotSwapJournal,
        updated: FullDeviceSnapshotSwapJournal,
    ): FullDeviceSnapshotJournalPersistResult {
        if (
            !FullDeviceSnapshotSwapJournalPolicy.isValid(expected) ||
            !FullDeviceSnapshotSwapJournalPolicy.isValid(updated) ||
            updated.revision != expected.revision + 1L
        ) {
            return refusedPersist(
                FullDeviceSnapshotJournalStoreFailureCode.NON_SEQUENTIAL_REVISION,
            )
        }
        if (!isLegalSuccessor(expected, updated)) {
            return refusedPersist(
                FullDeviceSnapshotJournalStoreFailureCode.ILLEGAL_STATE_TRANSITION,
            )
        }
        return withExclusiveLock {
            when (val current = loadUnlocked()) {
                FullDeviceSnapshotJournalLoadResult.Missing -> refusedPersist(
                    FullDeviceSnapshotJournalStoreFailureCode.EXPECTED_JOURNAL_MISSING,
                )

                is FullDeviceSnapshotJournalLoadResult.Refused -> refusedPersist(current.code)
                is FullDeviceSnapshotJournalLoadResult.Loaded -> when {
                    current.journal.operationId != expected.operationId -> refusedPersist(
                        FullDeviceSnapshotJournalStoreFailureCode.OPERATION_MISMATCH,
                    )

                    current.journal != expected -> refusedPersist(
                        FullDeviceSnapshotJournalStoreFailureCode.REVISION_MISMATCH,
                    )

                    else -> persistUnlocked(journal = updated, replaceExisting = true)
                }
            }
        }
    }

    private fun withExclusiveLock(
        block: () -> FullDeviceSnapshotJournalPersistResult,
    ): FullDeviceSnapshotJournalPersistResult = try {
        requireSafeRoot()
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.lock().use { block() }
        }
    } catch (_: UnsafeJournalDirectoryException) {
        refusedPersist(FullDeviceSnapshotJournalStoreFailureCode.UNSAFE_DIRECTORY)
    } catch (_: FileAlreadyExistsException) {
        refusedPersist(FullDeviceSnapshotJournalStoreFailureCode.IO_FAILURE)
    } catch (_: IOException) {
        refusedPersist(FullDeviceSnapshotJournalStoreFailureCode.IO_FAILURE)
    } catch (_: RuntimeException) {
        refusedPersist(FullDeviceSnapshotJournalStoreFailureCode.IO_FAILURE)
    }

    private fun requireSafeRoot() {
        if (
            Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw UnsafeJournalDirectoryException()
        }
        if (Files.isSymbolicLink(lockPath)) throw UnsafeJournalDirectoryException()
    }

    private fun loadUnlocked(): FullDeviceSnapshotJournalLoadResult {
        if (Files.notExists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            return FullDeviceSnapshotJournalLoadResult.Missing
        }
        if (!Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            return refusedLoad(
                FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_NOT_REGULAR_FILE,
            )
        }
        val size = Files.size(journalPath)
        if (size <= 0L || size > MAX_JOURNAL_BYTES) {
            return refusedLoad(FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_TOO_LARGE)
        }
        val bytes = Files.readAllBytes(journalPath)
        return when (val decoded = FullDeviceSnapshotSwapJournalCodec.decode(bytes)) {
            is FullDeviceSnapshotSwapJournalDecodeResult.Accepted ->
                FullDeviceSnapshotJournalLoadResult.Loaded(decoded.journal)

            is FullDeviceSnapshotSwapJournalDecodeResult.Rejected ->
                refusedLoad(FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_INVALID)
        }
    }

    private fun persistUnlocked(
        journal: FullDeviceSnapshotSwapJournal,
        replaceExisting: Boolean,
    ): FullDeviceSnapshotJournalPersistResult {
        val bytes = FullDeviceSnapshotSwapJournalCodec.encode(journal)
        if (bytes.isEmpty() || bytes.size > MAX_JOURNAL_BYTES) {
            return refusedPersist(FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_TOO_LARGE)
        }
        val temporary = root.resolve("$TEMP_PREFIX${UUID.randomUUID()}$TEMP_SUFFIX")
        var published = false
        return try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val options = if (replaceExisting) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(temporary, journalPath, *options)
            } catch (_: AtomicMoveNotSupportedException) {
                return refusedPersist(
                    FullDeviceSnapshotJournalStoreFailureCode.ATOMIC_REPLACE_UNAVAILABLE,
                )
            } catch (_: FileAlreadyExistsException) {
                return refusedPersist(
                    FullDeviceSnapshotJournalStoreFailureCode.JOURNAL_ALREADY_EXISTS,
                )
            }
            published = true
            forceRootDirectory()
            FullDeviceSnapshotJournalPersistResult.Persisted(journal)
        } catch (_: IOException) {
            refusedPersist(FullDeviceSnapshotJournalStoreFailureCode.IO_FAILURE)
        } catch (_: RuntimeException) {
            refusedPersist(FullDeviceSnapshotJournalStoreFailureCode.IO_FAILURE)
        } finally {
            if (!published) runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun forceRootDirectory() {
        FileChannel.open(root, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { directory ->
            directory.force(true)
        }
    }

    private fun isLegalSuccessor(
        expected: FullDeviceSnapshotSwapJournal,
        updated: FullDeviceSnapshotSwapJournal,
    ): Boolean {
        if (
            updated.journalFormat != expected.journalFormat ||
            updated.journalVersion != expected.journalVersion ||
            updated.operationId != expected.operationId ||
            updated.snapshotPayloadSetSha256 != expected.snapshotPayloadSetSha256 ||
            updated.originalDatabase != expected.originalDatabase ||
            updated.candidateDatabase != expected.candidateDatabase ||
            updated.createdAtEpochMillis != expected.createdAtEpochMillis ||
            updated.updatedAtEpochMillis < expected.updatedAtEpochMillis
        ) {
            return false
        }
        val allowed: Set<FullDeviceSnapshotSwapState> = when (expected.state) {
            FullDeviceSnapshotSwapState.STAGED ->
                setOf(FullDeviceSnapshotSwapState.VALIDATED)

            FullDeviceSnapshotSwapState.VALIDATED ->
                setOf(FullDeviceSnapshotSwapState.SWAP_INTENT)

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

            FullDeviceSnapshotSwapState.ACTIVATION_VERIFIED -> setOf(
                FullDeviceSnapshotSwapState.COMPLETE,
                FullDeviceSnapshotSwapState.ROLLBACK_INTENT,
            )

            FullDeviceSnapshotSwapState.ROLLBACK_INTENT -> setOf(
                FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED,
            )

            FullDeviceSnapshotSwapState.FAILED_CANDIDATE_QUARANTINED -> setOf(
                FullDeviceSnapshotSwapState.ORIGINAL_RESTORED,
            )

            FullDeviceSnapshotSwapState.ORIGINAL_RESTORED -> setOf(
                FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED,
            )

            FullDeviceSnapshotSwapState.ROLLBACK_VERIFIED -> setOf(
                FullDeviceSnapshotSwapState.ROLLED_BACK,
            )

            FullDeviceSnapshotSwapState.COMPLETE,
            FullDeviceSnapshotSwapState.ROLLED_BACK,
            -> emptySet()
        }
        if (updated.state !in allowed) return false
        return if (expected.state == FullDeviceSnapshotSwapState.STAGED) {
            expected.databaseValidationReceiptSha256 == null &&
                updated.databaseValidationReceiptSha256 != null
        } else {
            updated.databaseValidationReceiptSha256 == expected.databaseValidationReceiptSha256
        }
    }

    private fun refusedLoad(code: FullDeviceSnapshotJournalStoreFailureCode) =
        FullDeviceSnapshotJournalLoadResult.Refused(code)

    private fun refusedPersist(code: FullDeviceSnapshotJournalStoreFailureCode) =
        FullDeviceSnapshotJournalPersistResult.Refused(code)

    private class UnsafeJournalDirectoryException : IOException()

    private companion object {
        const val JOURNAL_FILE_NAME = ".full-device-snapshot-swap.json"
        const val LOCK_FILE_NAME = ".full-device-snapshot-swap.lock"
        const val TEMP_PREFIX = ".full-device-snapshot-swap-"
        const val TEMP_SUFFIX = ".tmp"
        const val MAX_JOURNAL_BYTES = 64L * 1024L
    }
}
