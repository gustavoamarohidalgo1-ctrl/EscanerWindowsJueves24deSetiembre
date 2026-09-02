package com.facturastock.app.data.restore

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest

internal data class FullDeviceSnapshotDatabaseFilePaths(
    val active: Path,
    val stagedCandidate: Path,
    val quarantinedOriginal: Path,
    val quarantinedFailedCandidate: Path,
) {
    init {
        val requested = listOf(
            active,
            stagedCandidate,
            quarantinedOriginal,
            quarantinedFailedCandidate,
        )
        val normalized = requested.map { it.toAbsolutePath().normalize() }
        require(requested.map(Path::toAbsolutePath) == normalized)
        require(normalized.distinct().size == normalized.size)
        require(normalized.mapNotNull(Path::getParent).distinct().size == 1)
        require(normalized.none(Files::isSymbolicLink))
    }
}

internal sealed interface FullDeviceSnapshotDatabaseFilesResult {
    data class Observed(val layout: FullDeviceSnapshotSwapLayout) :
        FullDeviceSnapshotDatabaseFilesResult

    data object Moved : FullDeviceSnapshotDatabaseFilesResult

    data class Refused(val code: FullDeviceSnapshotDatabaseFilesFailureCode) :
        FullDeviceSnapshotDatabaseFilesResult
}

internal enum class FullDeviceSnapshotDatabaseFilesFailureCode {
    UNSAFE_DIRECTORY,
    SIDECAR_PRESENT,
    SOURCE_MISSING_OR_UNSAFE,
    SOURCE_FINGERPRINT_MISMATCH,
    DESTINATION_ALREADY_EXISTS,
    LAYOUT_UNSAFE,
    UNSUPPORTED_ACTION,
    ATOMIC_MOVE_UNAVAILABLE,
    POST_MOVE_VERIFICATION_FAILED,
    IO_FAILURE,
}

/**
 * Primitiva de archivos para el swap. No cierra Room ni decide por sí sola qué paso ejecutar.
 * Solo acepta las cinco acciones de rename que la máquina de recuperación ya decidió y nunca
 * reemplaza un destino. La ausencia de WAL/SHM/journal se exige en cada observación y movimiento.
 */
internal class FullDeviceSnapshotAtomicDatabaseFiles(
    private val paths: FullDeviceSnapshotDatabaseFilePaths,
) {
    private val root = paths.active.toAbsolutePath().normalize().parent
    private val lockPath = root.resolve(".full-device-snapshot-database-files.lock")

    fun observe(): FullDeviceSnapshotDatabaseFilesResult = withExclusiveLock {
        requireSafeRootAndNoSidecars()
        FullDeviceSnapshotDatabaseFilesResult.Observed(
            FullDeviceSnapshotSwapLayout(
                activeDatabase = optionalFingerprint(paths.active),
                stagedCandidate = optionalFingerprint(paths.stagedCandidate),
                quarantinedOriginal = optionalFingerprint(paths.quarantinedOriginal),
                quarantinedFailedCandidate = optionalFingerprint(
                    paths.quarantinedFailedCandidate,
                ),
            ),
        )
    }

    fun executeMove(
        action: FullDeviceSnapshotRecoveryAction,
        journal: FullDeviceSnapshotSwapJournal,
    ): FullDeviceSnapshotDatabaseFilesResult {
        if (!FullDeviceSnapshotSwapJournalPolicy.isValid(journal)) {
            return refused(FullDeviceSnapshotDatabaseFilesFailureCode.LAYOUT_UNSAFE)
        }
        val move = when (action) {
            FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_TO_ORIGINAL_QUARANTINE ->
                Move(paths.active, paths.quarantinedOriginal, journal.originalDatabase)

            FullDeviceSnapshotRecoveryAction.MOVE_STAGED_CANDIDATE_TO_ACTIVE ->
                Move(paths.stagedCandidate, paths.active, journal.candidateDatabase)

            FullDeviceSnapshotRecoveryAction.MOVE_ACTIVE_CANDIDATE_TO_FAILED_QUARANTINE ->
                Move(paths.active, paths.quarantinedFailedCandidate, journal.candidateDatabase)

            FullDeviceSnapshotRecoveryAction.MOVE_STAGED_CANDIDATE_TO_FAILED_QUARANTINE ->
                Move(
                    paths.stagedCandidate,
                    paths.quarantinedFailedCandidate,
                    journal.candidateDatabase,
                )

            FullDeviceSnapshotRecoveryAction.MOVE_ORIGINAL_QUARANTINE_TO_ACTIVE ->
                Move(paths.quarantinedOriginal, paths.active, journal.originalDatabase)

            else -> return refused(FullDeviceSnapshotDatabaseFilesFailureCode.UNSUPPORTED_ACTION)
        }
        return withExclusiveLock {
            val layout = observeLayoutUnlocked()
            val expectedDecision = FullDeviceSnapshotRecoveryDecision.Execute(action)
            if (
                FullDeviceSnapshotSwapCoordinator().recoveryDecision(journal, layout) !=
                expectedDecision
            ) {
                reject(FullDeviceSnapshotDatabaseFilesFailureCode.LAYOUT_UNSAFE)
            }
            executeUnlocked(move)
        }
    }

    private fun executeUnlocked(move: Move): FullDeviceSnapshotDatabaseFilesResult {
        requireSafeRootAndNoSidecars()
        val observedSource = requiredFingerprint(move.source)
        if (observedSource != move.expected) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.SOURCE_FINGERPRINT_MISMATCH)
        }
        if (Files.exists(move.destination, LinkOption.NOFOLLOW_LINKS)) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.DESTINATION_ALREADY_EXISTS)
        }
        try {
            Files.move(move.source, move.destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.ATOMIC_MOVE_UNAVAILABLE)
        } catch (_: FileAlreadyExistsException) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.DESTINATION_ALREADY_EXISTS)
        }
        forceRootDirectory()
        if (
            Files.exists(move.source, LinkOption.NOFOLLOW_LINKS) ||
            requiredFingerprint(move.destination) != move.expected
        ) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.POST_MOVE_VERIFICATION_FAILED)
        }
        return FullDeviceSnapshotDatabaseFilesResult.Moved
    }

    private fun withExclusiveLock(
        block: () -> FullDeviceSnapshotDatabaseFilesResult,
    ): FullDeviceSnapshotDatabaseFilesResult = try {
        requireSafeRootAndNoSidecars()
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.lock().use { block() }
        }
    } catch (failure: DatabaseFilesRejectedException) {
        refused(failure.code)
    } catch (_: IOException) {
        refused(FullDeviceSnapshotDatabaseFilesFailureCode.IO_FAILURE)
    } catch (_: RuntimeException) {
        refused(FullDeviceSnapshotDatabaseFilesFailureCode.IO_FAILURE)
    }

    private fun requireSafeRootAndNoSidecars() {
        if (
            root == null ||
            Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.UNSAFE_DIRECTORY)
        }
        if (Files.isSymbolicLink(lockPath)) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.UNSAFE_DIRECTORY)
        }
        listOf(
            paths.active,
            paths.stagedCandidate,
            paths.quarantinedOriginal,
            paths.quarantinedFailedCandidate,
        ).forEach { database ->
            SIDECAR_SUFFIXES.forEach { suffix ->
                val sidecar = database.resolveSibling(database.fileName.toString() + suffix)
                if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                    reject(FullDeviceSnapshotDatabaseFilesFailureCode.SIDECAR_PRESENT)
                }
            }
        }
    }

    private fun optionalFingerprint(path: Path): SnapshotDatabaseFingerprint? {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return null
        return requiredFingerprint(path)
    }

    private fun observeLayoutUnlocked() = FullDeviceSnapshotSwapLayout(
        activeDatabase = optionalFingerprint(paths.active),
        stagedCandidate = optionalFingerprint(paths.stagedCandidate),
        quarantinedOriginal = optionalFingerprint(paths.quarantinedOriginal),
        quarantinedFailedCandidate = optionalFingerprint(paths.quarantinedFailedCandidate),
    )

    private fun requiredFingerprint(path: Path): SnapshotDatabaseFingerprint {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            reject(FullDeviceSnapshotDatabaseFilesFailureCode.SOURCE_MISSING_OR_UNSAFE)
        }
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
        if (size <= 0L) reject(FullDeviceSnapshotDatabaseFilesFailureCode.SOURCE_MISSING_OR_UNSAFE)
        return SnapshotDatabaseFingerprint(size, digest.digest().toHex())
    }

    private fun forceRootDirectory() {
        FileChannel.open(root, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.force(true)
        }
    }

    private fun reject(code: FullDeviceSnapshotDatabaseFilesFailureCode): Nothing =
        throw DatabaseFilesRejectedException(code)

    private fun refused(code: FullDeviceSnapshotDatabaseFilesFailureCode) =
        FullDeviceSnapshotDatabaseFilesResult.Refused(code)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
            HEX_DIGITS[byte.toInt() and 0x0f]
    }

    private data class Move(
        val source: Path,
        val destination: Path,
        val expected: SnapshotDatabaseFingerprint,
    )

    private class DatabaseFilesRejectedException(
        val code: FullDeviceSnapshotDatabaseFilesFailureCode,
    ) : IOException()

    private companion object {
        val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
        const val FINGERPRINT_BUFFER_BYTES = 64 * 1024
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
