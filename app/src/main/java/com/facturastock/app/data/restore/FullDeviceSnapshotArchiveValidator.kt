package com.facturastock.app.data.restore

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Resultado que nunca degrada un archivo inválido a una restauración parcial. */
sealed interface FullDeviceSnapshotArchiveValidationResult {
    data class Validated(val snapshot: StagedFullDeviceSnapshot) :
        FullDeviceSnapshotArchiveValidationResult

    data class Rejected(val failure: FullDeviceSnapshotArchiveFailure) :
        FullDeviceSnapshotArchiveValidationResult
}

data class StagedFullDeviceSnapshot(
    val manifest: FullDeviceSnapshotManifest,
    val stagingDirectory: Path,
    val stagedDatabase: Path,
    val stagedPayloads: Map<String, Path>,
)

data class FullDeviceSnapshotArchiveFailure(
    val code: FullDeviceSnapshotArchiveFailureCode,
    /** `false` obliga a mantenimiento manual; nunca autoriza un swap. */
    val stagingDirectoryClean: Boolean,
)

enum class FullDeviceSnapshotArchiveFailureCode {
    ARCHIVE_NOT_REGULAR_FILE,
    ARCHIVE_TOO_LARGE,
    STAGING_PARENT_UNSAFE,
    STAGING_TARGET_EXISTS,
    INVALID_ZIP,
    EMPTY_ARCHIVE,
    MANIFEST_NOT_FIRST,
    MANIFEST_TOO_LARGE,
    MANIFEST_INVALID_JSON,
    MANIFEST_NOT_CANONICAL,
    MANIFEST_CONTRACT_VIOLATION,
    TOO_MANY_ENTRIES,
    DIRECTORY_ENTRY_NOT_ALLOWED,
    UNSUPPORTED_ZIP_METHOD,
    INVALID_ENTRY_PATH,
    DUPLICATE_ENTRY,
    UNDECLARED_ENTRY,
    MISSING_ENTRY,
    ENTRY_TOO_LARGE,
    ENTRY_SIZE_MISMATCH,
    ENTRY_CHECKSUM_MISMATCH,
    EXPANDED_SIZE_LIMIT_EXCEEDED,
    IO_FAILURE,
}

/**
 * Validador ZIP de dos fases:
 *
 * 1. Lee el directorio central, exige `manifest.json` primero y valida nombres, duplicados,
 *    métodos, conteos y tamaños antes de escribir.
 * 2. Extrae exclusivamente payloads declarados hacia un directorio nuevo, con `CREATE_NEW`,
 *    límites durante el streaming, SHA-256 y `force(true)` por archivo.
 *
 * Un resultado [Validated] acredita el contenedor, no las invariantes SQLite. El consumidor debe
 * abrir [StagedFullDeviceSnapshot.stagedDatabase] de forma aislada, migrarla y ejecutar Room,
 * `foreign_key_check`, `integrity_check` e invariantes semánticas antes de marcar el journal como
 * `VALIDATED`.
 */
class FullDeviceSnapshotArchiveValidator(
    private val limits: FullDeviceSnapshotLimits = FullDeviceSnapshotLimits(),
) {
    fun validateAndStage(
        archive: Path,
        stagingDirectory: Path,
    ): FullDeviceSnapshotArchiveValidationResult {
        var stagingCreated = false
        return try {
            requireSafeArchive(archive)
            val normalizedStaging = requireSafeStagingTarget(stagingDirectory)
            val staged = ZipFile(archive.toFile()).use { zip ->
                val preflight = preflight(zip)
                Files.createDirectory(normalizedStaging)
                stagingCreated = true
                extract(zip, preflight, normalizedStaging)
            }
            FullDeviceSnapshotArchiveValidationResult.Validated(staged)
        } catch (rejected: ArchiveRejectedException) {
            FullDeviceSnapshotArchiveValidationResult.Rejected(
                FullDeviceSnapshotArchiveFailure(
                    code = rejected.code,
                    stagingDirectoryClean = !stagingCreated || cleanupStaging(stagingDirectory),
                ),
            )
        } catch (_: ZipException) {
            FullDeviceSnapshotArchiveValidationResult.Rejected(
                FullDeviceSnapshotArchiveFailure(
                    code = FullDeviceSnapshotArchiveFailureCode.INVALID_ZIP,
                    stagingDirectoryClean = !stagingCreated || cleanupStaging(stagingDirectory),
                ),
            )
        } catch (_: IOException) {
            FullDeviceSnapshotArchiveValidationResult.Rejected(
                FullDeviceSnapshotArchiveFailure(
                    code = FullDeviceSnapshotArchiveFailureCode.IO_FAILURE,
                    stagingDirectoryClean = !stagingCreated || cleanupStaging(stagingDirectory),
                ),
            )
        } catch (_: RuntimeException) {
            // Un parser/FS inesperado tampoco puede promover el staging.
            FullDeviceSnapshotArchiveValidationResult.Rejected(
                FullDeviceSnapshotArchiveFailure(
                    code = FullDeviceSnapshotArchiveFailureCode.IO_FAILURE,
                    stagingDirectoryClean = !stagingCreated || cleanupStaging(stagingDirectory),
                ),
            )
        }
    }

    private fun requireSafeArchive(archive: Path) {
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            reject(FullDeviceSnapshotArchiveFailureCode.ARCHIVE_NOT_REGULAR_FILE)
        }
        val archiveBytes = Files.size(archive)
        if (archiveBytes <= 0L) reject(FullDeviceSnapshotArchiveFailureCode.EMPTY_ARCHIVE)
        if (archiveBytes > limits.maxArchiveBytes) {
            reject(FullDeviceSnapshotArchiveFailureCode.ARCHIVE_TOO_LARGE)
        }
    }

    private fun requireSafeStagingTarget(stagingDirectory: Path): Path {
        val normalized = stagingDirectory.toAbsolutePath().normalize()
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            reject(FullDeviceSnapshotArchiveFailureCode.STAGING_TARGET_EXISTS)
        }
        val parent = normalized.parent
            ?: reject(FullDeviceSnapshotArchiveFailureCode.STAGING_PARENT_UNSAFE)
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            reject(FullDeviceSnapshotArchiveFailureCode.STAGING_PARENT_UNSAFE)
        }
        return normalized
    }

    private fun preflight(zip: ZipFile): ArchivePreflight {
        val entries = mutableListOf<ZipEntry>()
        val collisionKeys = mutableSetOf<String>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entries.size >= limits.maxArchiveEntries) {
                reject(FullDeviceSnapshotArchiveFailureCode.TOO_MANY_ENTRIES)
            }
            requireSafeZipEntry(entry)
            if (!collisionKeys.add(FullDeviceSnapshotPathPolicy.collisionKey(entry.name))) {
                reject(FullDeviceSnapshotArchiveFailureCode.DUPLICATE_ENTRY)
            }
            entries += entry
        }
        if (entries.isEmpty()) reject(FullDeviceSnapshotArchiveFailureCode.EMPTY_ARCHIVE)
        if (entries.first().name != FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH) {
            reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_NOT_FIRST)
        }
        val manifestEntry = entries.first()
        if (manifestEntry.size < 0L || manifestEntry.size > limits.maxManifestBytes.toLong()) {
            reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_TOO_LARGE)
        }
        val manifestBytes = zip.getInputStream(manifestEntry).use { input ->
            readManifestBytes(input, manifestEntry.size)
        }
        val manifest = decodeManifest(manifestBytes)
        FullDeviceSnapshotManifestPolicy.firstViolation(manifest, limits)?.let {
            reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_CONTRACT_VIOLATION)
        }

        val expectedByPath = manifest.entries.associateBy { it.path }
        val actualPayloadEntries = entries.drop(1)
        actualPayloadEntries.forEach { entry ->
            val expected = expectedByPath[entry.name]
                ?: reject(FullDeviceSnapshotArchiveFailureCode.UNDECLARED_ENTRY)
            if (entry.size < 0L || entry.size > limits.maxEntryBytes) {
                reject(FullDeviceSnapshotArchiveFailureCode.ENTRY_TOO_LARGE)
            }
            if (entry.size != expected.uncompressedSizeBytes) {
                reject(FullDeviceSnapshotArchiveFailureCode.ENTRY_SIZE_MISMATCH)
            }
        }
        val actualNames = actualPayloadEntries.mapTo(mutableSetOf()) { it.name }
        if (actualNames != expectedByPath.keys) {
            reject(FullDeviceSnapshotArchiveFailureCode.MISSING_ENTRY)
        }

        var expandedBytes = manifestBytes.size.toLong()
        manifest.entries.forEach { expected ->
            if (expandedBytes > limits.maxExpandedBytes - expected.uncompressedSizeBytes) {
                reject(FullDeviceSnapshotArchiveFailureCode.EXPANDED_SIZE_LIMIT_EXCEEDED)
            }
            expandedBytes += expected.uncompressedSizeBytes
        }
        return ArchivePreflight(
            manifest = manifest,
            manifestBytes = manifestBytes,
            payloadEntryPaths = actualPayloadEntries.map { it.name },
        )
    }

    private fun requireSafeZipEntry(entry: ZipEntry) {
        if (entry.isDirectory || entry.name.endsWith('/')) {
            reject(FullDeviceSnapshotArchiveFailureCode.DIRECTORY_ENTRY_NOT_ALLOWED)
        }
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            reject(FullDeviceSnapshotArchiveFailureCode.UNSUPPORTED_ZIP_METHOD)
        }
        if (!FullDeviceSnapshotPathPolicy.isCanonical(entry.name, limits.maxPathUtf8Bytes)) {
            reject(FullDeviceSnapshotArchiveFailureCode.INVALID_ENTRY_PATH)
        }
    }

    private fun readManifestBytes(input: java.io.InputStream, declaredSize: Long): ByteArray {
        if (declaredSize > Int.MAX_VALUE) {
            reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_TOO_LARGE)
        }
        val expected = declaredSize.toInt()
        val bytes = ByteArray(expected)
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_TOO_LARGE)
            offset += read
        }
        if (input.read() >= 0) reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_TOO_LARGE)
        return bytes
    }

    private fun decodeManifest(bytes: ByteArray): FullDeviceSnapshotManifest = try {
        FullDeviceSnapshotManifestCodec.decodeCanonical(bytes)
    } catch (_: FullDeviceSnapshotManifestNotCanonicalException) {
        reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_NOT_CANONICAL)
    } catch (_: FullDeviceSnapshotManifestDecodingException) {
        reject(FullDeviceSnapshotArchiveFailureCode.MANIFEST_INVALID_JSON)
    }

    private fun extract(
        zip: ZipFile,
        preflight: ArchivePreflight,
        stagingDirectory: Path,
    ): StagedFullDeviceSnapshot {
        val manifestPath = stagingDirectory.resolve(FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH)
        writeForced(manifestPath, preflight.manifestBytes)

        val expectedByPath = preflight.manifest.entries.associateBy { it.path }
        val staged = linkedMapOf<String, Path>()
        var expandedBytes = preflight.manifestBytes.size.toLong()
        preflight.payloadEntryPaths.forEach { archivedPath ->
            val archivedEntry = zip.getEntry(archivedPath)
                ?: reject(FullDeviceSnapshotArchiveFailureCode.MISSING_ENTRY)
            val expected = expectedByPath.getValue(archivedPath)
            val destination = safeDestination(stagingDirectory, archivedPath)
            createSafeParents(stagingDirectory, destination.parent)
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                zip.getInputStream(archivedEntry).use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (written > expected.uncompressedSizeBytes - read) {
                            reject(FullDeviceSnapshotArchiveFailureCode.ENTRY_SIZE_MISMATCH)
                        }
                        if (expandedBytes > limits.maxExpandedBytes - read) {
                            reject(
                                FullDeviceSnapshotArchiveFailureCode.EXPANDED_SIZE_LIMIT_EXCEEDED,
                            )
                        }
                        val byteBuffer = ByteBuffer.wrap(buffer, 0, read)
                        while (byteBuffer.hasRemaining()) output.write(byteBuffer)
                        digest.update(buffer, 0, read)
                        written += read
                        expandedBytes += read
                    }
                }
                output.force(true)
            }
            if (written != expected.uncompressedSizeBytes) {
                reject(FullDeviceSnapshotArchiveFailureCode.ENTRY_SIZE_MISMATCH)
            }
            val actualHash = digest.digest().toHex()
            if (!MessageDigest.isEqual(actualHash.toByteArray(), expected.sha256.toByteArray())) {
                reject(FullDeviceSnapshotArchiveFailureCode.ENTRY_CHECKSUM_MISMATCH)
            }
            staged[expected.path] = destination
        }
        val database = staged[FullDeviceSnapshotContract.DATABASE_ENTRY_PATH]
            ?: reject(FullDeviceSnapshotArchiveFailureCode.MISSING_ENTRY)
        return StagedFullDeviceSnapshot(
            manifest = preflight.manifest,
            stagingDirectory = stagingDirectory,
            stagedDatabase = database,
            stagedPayloads = staged.toMap(),
        )
    }

    private fun safeDestination(root: Path, relativePath: String): Path {
        val destination = root.resolve(relativePath).normalize()
        if (!destination.startsWith(root) || destination == root) {
            reject(FullDeviceSnapshotArchiveFailureCode.INVALID_ENTRY_PATH)
        }
        return destination
    }

    /** Crea cada padre sin seguir ni aceptar enlaces simbólicos preexistentes. */
    private fun createSafeParents(root: Path, targetParent: Path) {
        var current = root
        val relative = root.relativize(targetParent)
        relative.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    reject(FullDeviceSnapshotArchiveFailureCode.INVALID_ENTRY_PATH)
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun writeForced(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) output.write(buffer)
            output.force(true)
        }
    }

    private fun cleanupStaging(stagingDirectory: Path): Boolean {
        val normalized = stagingDirectory.toAbsolutePath().normalize()
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.walk(normalized).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
            !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
            HEX_DIGITS[byte.toInt() and 0x0f]
    }

    private fun reject(code: FullDeviceSnapshotArchiveFailureCode): Nothing =
        throw ArchiveRejectedException(code)

    private data class ArchivePreflight(
        val manifest: FullDeviceSnapshotManifest,
        val manifestBytes: ByteArray,
        val payloadEntryPaths: List<String>,
    )

    private class ArchiveRejectedException(
        val code: FullDeviceSnapshotArchiveFailureCode,
    ) : RuntimeException()

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
