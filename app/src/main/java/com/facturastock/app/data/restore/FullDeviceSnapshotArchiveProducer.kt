package com.facturastock.app.data.restore

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

/**
 * Solicitud pura de empaquetado. [alreadyCheckpointedDatabase] debe ser una imagen SQLite estable:
 * este componente no abre, cierra ni ejecuta checkpoint sobre Room y tampoco valida su contenido.
 */
data class FullDeviceSnapshotArchiveProductionRequest(
    val alreadyCheckpointedDatabase: Path,
    val payloads: List<FullDeviceSnapshotSourcePayload>,
    val sourceDatabaseSchemaVersion: Int,
    val sourceRoomIdentityHash: String,
    val sourceApplicationVersion: String,
    val createdAtEpochMillis: Long,
    val tableDigests: List<FullDeviceSnapshotTableDigest>,
    /** Debe ser una ruta nueva en un directorio local que permita hard links y `fsync`. */
    val destination: Path,
)

data class FullDeviceSnapshotSourcePayload(
    val source: Path,
    val archivePath: String,
    val kind: FullDeviceSnapshotEntryKind,
)

sealed interface FullDeviceSnapshotArchiveProductionResult {
    data class Produced(
        val destination: Path,
        val manifest: FullDeviceSnapshotManifest,
        val archiveSizeBytes: Long,
        val archiveSha256: String,
        /** `false` solo advierte de un temporal huérfano; el destino ya fue validado y forzado. */
        val temporaryArtifactsCleanAndForced: Boolean,
    ) : FullDeviceSnapshotArchiveProductionResult

    data class Rejected(
        val failure: FullDeviceSnapshotArchiveProductionFailure,
    ) : FullDeviceSnapshotArchiveProductionResult
}

data class FullDeviceSnapshotArchiveProductionFailure(
    val code: FullDeviceSnapshotArchiveProductionFailureCode,
    val temporaryArtifactsClean: Boolean,
    /** `false` exige inspección manual; nunca equivale a un snapshot publicable. */
    val destinationUntouched: Boolean,
    val selfValidationFailure: FullDeviceSnapshotArchiveFailureCode? = null,
)

enum class FullDeviceSnapshotArchiveProductionFailureCode {
    DESTINATION_PATH_NOT_CANONICAL,
    DESTINATION_PARENT_UNSAFE,
    DESTINATION_IS_SYMLINK,
    DESTINATION_EXISTS,
    SOURCE_PATH_NOT_CANONICAL,
    SOURCE_IS_SYMLINK,
    SOURCE_NOT_REGULAR_FILE,
    SOURCE_EMPTY,
    SOURCE_TOO_LARGE,
    SOURCE_CHANGED_DURING_PRODUCTION,
    INVALID_ARCHIVE_PATH,
    INVALID_ENTRY_KIND,
    DUPLICATE_ENTRY,
    MANIFEST_CONTRACT_VIOLATION,
    ARCHIVE_TOO_LARGE,
    SELF_VALIDATION_FAILED,
    TEMPORARY_CLEANUP_FAILED,
    ATOMIC_NO_REPLACE_PUBLICATION_UNAVAILABLE,
    IO_FAILURE,
}

/**
 * Productor determinista de ZIP `FULL_DEVICE_SNAPSHOT` para un filesystem local.
 *
 * El archivo se escribe como ZIP STORED con metadata fija, se fuerza a disco, se vuelve a abrir con
 * [FullDeviceSnapshotArchiveValidator] y solo entonces se publica mediante hard link. El hard link
 * aporta una operación atómica `create-new`: nunca reemplaza un destino que apareció por carrera.
 * Filesystems sin esa primitiva fallan cerrados.
 *
 * Esto no es el backup Android completo: no coordina el cierre/checkpoint de Room, no usa SAF, no
 * cifra para portabilidad y no habilita la restauración ni el swap de la base activa.
 */
class FullDeviceSnapshotArchiveProducer internal constructor(
    private val limits: FullDeviceSnapshotLimits,
    private val archivePreparedObserver: (Path) -> Unit,
) {
    constructor(
        limits: FullDeviceSnapshotLimits = FullDeviceSnapshotLimits(),
    ) : this(limits, {})

    fun produce(
        request: FullDeviceSnapshotArchiveProductionRequest,
    ): FullDeviceSnapshotArchiveProductionResult {
        val normalizedDestination = request.destination.toAbsolutePath().normalize()
        var temporaryArchive: Path? = null
        var validationStaging: Path? = null
        var destinationPublished = false
        var selfValidationFailure: FullDeviceSnapshotArchiveFailureCode? = null

        return try {
            requireSafeDestination(request.destination, normalizedDestination)
            val destinationParent = normalizedDestination.parent
                ?: reject(FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_PARENT_UNSAFE)

            FileChannel.open(
                destinationParent,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { parentDirectoryChannel ->
                // Comprueba la capacidad de fsync antes de crear temporales o publicar el destino.
                parentDirectoryChannel.force(true)

                val preparedPayloads = preparePayloads(request)
                val manifest = createAndValidateManifest(request, preparedPayloads)
                val manifestBytes = FullDeviceSnapshotManifestCodec.encode(manifest)
                if (manifestBytes.size > limits.maxManifestBytes) {
                    reject(
                        FullDeviceSnapshotArchiveProductionFailureCode.MANIFEST_CONTRACT_VIOLATION,
                    )
                }
                val expectedArchiveSize = deterministicArchiveSize(manifestBytes, preparedPayloads)
                if (expectedArchiveSize > limits.maxArchiveBytes) {
                    reject(FullDeviceSnapshotArchiveProductionFailureCode.ARCHIVE_TOO_LARGE)
                }

                val archivePath = Files.createTempFile(
                    destinationParent,
                    TEMP_ARCHIVE_PREFIX,
                    TEMP_ARCHIVE_SUFFIX,
                )
                temporaryArchive = archivePath
                writeDeterministicArchive(
                    archive = archivePath,
                    manifestBytes = manifestBytes,
                    payloads = preparedPayloads,
                )
                forceFile(archivePath)
                archivePreparedObserver(archivePath)

                val archiveSize = Files.size(archivePath)
                if (
                    archiveSize != expectedArchiveSize ||
                    archiveSize <= 0L ||
                    archiveSize > limits.maxArchiveBytes
                ) {
                    reject(FullDeviceSnapshotArchiveProductionFailureCode.ARCHIVE_TOO_LARGE)
                }

                validationStaging = uniqueValidationStaging(destinationParent)
                when (
                    val validation = FullDeviceSnapshotArchiveValidator(limits).validateAndStage(
                        archive = archivePath,
                        stagingDirectory = validationStaging,
                    )
                ) {
                    is FullDeviceSnapshotArchiveValidationResult.Rejected -> {
                        selfValidationFailure = validation.failure.code
                        reject(
                            FullDeviceSnapshotArchiveProductionFailureCode.SELF_VALIDATION_FAILED,
                        )
                    }

                    is FullDeviceSnapshotArchiveValidationResult.Validated -> {
                        if (validation.snapshot.manifest != manifest) {
                            reject(
                                FullDeviceSnapshotArchiveProductionFailureCode.SELF_VALIDATION_FAILED,
                            )
                        }
                    }
                }

                if (!deleteTree(validationStaging)) {
                    reject(FullDeviceSnapshotArchiveProductionFailureCode.TEMPORARY_CLEANUP_FAILED)
                }
                validationStaging = null

                val archiveSha256 = sha256File(archivePath)
                try {
                    Files.createLink(normalizedDestination, archivePath)
                } catch (_: FileAlreadyExistsException) {
                    reject(FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_EXISTS)
                } catch (_: UnsupportedOperationException) {
                    reject(
                        FullDeviceSnapshotArchiveProductionFailureCode
                            .ATOMIC_NO_REPLACE_PUBLICATION_UNAVAILABLE,
                    )
                }
                destinationPublished = true
                // Persiste la nueva entrada de directorio antes de retirar el nombre temporal.
                parentDirectoryChannel.force(true)

                val temporaryDeleted = deleteFile(temporaryArchive)
                if (temporaryDeleted) temporaryArchive = null
                val cleanupForced = temporaryDeleted && forceDirectoryWithoutThrowing(
                    parentDirectoryChannel,
                )

                FullDeviceSnapshotArchiveProductionResult.Produced(
                    destination = normalizedDestination,
                    manifest = manifest,
                    archiveSizeBytes = archiveSize,
                    archiveSha256 = archiveSha256,
                    temporaryArtifactsCleanAndForced = cleanupForced,
                )
            }
        } catch (rejected: ProductionRejectedException) {
            val rolledBack = if (destinationPublished) {
                rollbackPublishedLink(normalizedDestination, temporaryArchive)
            } else {
                true
            }
            val temporaryArtifactsClean = cleanupTemporaries(
                archive = temporaryArchive,
                stagingDirectory = validationStaging,
            )
            FullDeviceSnapshotArchiveProductionResult.Rejected(
                FullDeviceSnapshotArchiveProductionFailure(
                    code = rejected.code,
                    temporaryArtifactsClean = temporaryArtifactsClean,
                    destinationUntouched = rolledBack,
                    selfValidationFailure = selfValidationFailure,
                ),
            )
        } catch (_: IOException) {
            val rolledBack = if (destinationPublished) {
                rollbackPublishedLink(normalizedDestination, temporaryArchive)
            } else {
                true
            }
            val temporaryArtifactsClean = cleanupTemporaries(
                archive = temporaryArchive,
                stagingDirectory = validationStaging,
            )
            FullDeviceSnapshotArchiveProductionResult.Rejected(
                FullDeviceSnapshotArchiveProductionFailure(
                    code = FullDeviceSnapshotArchiveProductionFailureCode.IO_FAILURE,
                    temporaryArtifactsClean = temporaryArtifactsClean,
                    destinationUntouched = rolledBack,
                    selfValidationFailure = selfValidationFailure,
                ),
            )
        } catch (_: RuntimeException) {
            val rolledBack = if (destinationPublished) {
                rollbackPublishedLink(normalizedDestination, temporaryArchive)
            } else {
                true
            }
            val temporaryArtifactsClean = cleanupTemporaries(
                archive = temporaryArchive,
                stagingDirectory = validationStaging,
            )
            FullDeviceSnapshotArchiveProductionResult.Rejected(
                FullDeviceSnapshotArchiveProductionFailure(
                    code = FullDeviceSnapshotArchiveProductionFailureCode.IO_FAILURE,
                    temporaryArtifactsClean = temporaryArtifactsClean,
                    destinationUntouched = rolledBack,
                    selfValidationFailure = selfValidationFailure,
                ),
            )
        }
    }

    private fun requireSafeDestination(requested: Path, normalized: Path) {
        if (requested.toAbsolutePath() != normalized) {
            reject(
                FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_PATH_NOT_CANONICAL,
            )
        }
        if (Files.isSymbolicLink(normalized)) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_IS_SYMLINK)
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_EXISTS)
        }
        val parent = normalized.parent
            ?: reject(FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_PARENT_UNSAFE)
        if (
            Files.isSymbolicLink(parent) ||
            !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
        ) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_PARENT_UNSAFE)
        }
    }

    private fun preparePayloads(
        request: FullDeviceSnapshotArchiveProductionRequest,
    ): List<PreparedPayload> {
        val sourcePayloads = buildList {
            add(
                FullDeviceSnapshotSourcePayload(
                    source = request.alreadyCheckpointedDatabase,
                    archivePath = FullDeviceSnapshotContract.DATABASE_ENTRY_PATH,
                    kind = FullDeviceSnapshotEntryKind.ROOM_DATABASE,
                ),
            )
            addAll(request.payloads)
        }
        val collisionKeys = mutableSetOf(
            FullDeviceSnapshotPathPolicy.collisionKey(
                FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH,
            ),
        )
        sourcePayloads.forEach { payload ->
            requireValidArchivePathAndKind(payload)
            if (!collisionKeys.add(FullDeviceSnapshotPathPolicy.collisionKey(payload.archivePath))) {
                reject(FullDeviceSnapshotArchiveProductionFailureCode.DUPLICATE_ENTRY)
            }
        }
        return sourcePayloads.sortedBy { it.archivePath }.map(::measurePayload)
    }

    private fun requireValidArchivePathAndKind(payload: FullDeviceSnapshotSourcePayload) {
        if (!FullDeviceSnapshotPathPolicy.isCanonical(payload.archivePath, limits.maxPathUtf8Bytes)) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.INVALID_ARCHIVE_PATH)
        }
        val kindMatchesPath = when (payload.kind) {
            FullDeviceSnapshotEntryKind.ROOM_DATABASE ->
                payload.archivePath == FullDeviceSnapshotContract.DATABASE_ENTRY_PATH

            FullDeviceSnapshotEntryKind.PRIVATE_FILE ->
                payload.archivePath.startsWith(FullDeviceSnapshotContract.PRIVATE_FILES_PREFIX)

            FullDeviceSnapshotEntryKind.SAFE_APP_SETTINGS ->
                payload.archivePath.startsWith(FullDeviceSnapshotContract.SAFE_SETTINGS_PREFIX)
        }
        if (!kindMatchesPath) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.INVALID_ENTRY_KIND)
        }
        if (
            payload.archivePath == FullDeviceSnapshotContract.DATABASE_ENTRY_PATH &&
            payload.kind != FullDeviceSnapshotEntryKind.ROOM_DATABASE
        ) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.INVALID_ENTRY_KIND)
        }
    }

    private fun measurePayload(payload: FullDeviceSnapshotSourcePayload): PreparedPayload {
        val absoluteSource = payload.source.toAbsolutePath()
        val normalizedSource = absoluteSource.normalize()
        if (absoluteSource != normalizedSource) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.SOURCE_PATH_NOT_CANONICAL)
        }
        if (Files.isSymbolicLink(normalizedSource)) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.SOURCE_IS_SYMLINK)
        }
        if (!Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.SOURCE_NOT_REGULAR_FILE)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        FileChannel.open(
            normalizedSource,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { input ->
            val buffer = ByteBuffer.allocate(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (size > limits.maxEntryBytes - read) {
                    reject(FullDeviceSnapshotArchiveProductionFailureCode.SOURCE_TOO_LARGE)
                }
                digest.update(buffer.array(), 0, read)
                crc.update(buffer.array(), 0, read)
                size += read
                buffer.clear()
            }
        }
        if (size == 0L) reject(FullDeviceSnapshotArchiveProductionFailureCode.SOURCE_EMPTY)

        return PreparedPayload(
            source = normalizedSource,
            entry = FullDeviceSnapshotEntry(
                path = payload.archivePath,
                kind = payload.kind,
                uncompressedSizeBytes = size,
                sha256 = digest.digest().toHex(),
            ),
            crc32 = crc.value,
        )
    }

    private fun createAndValidateManifest(
        request: FullDeviceSnapshotArchiveProductionRequest,
        payloads: List<PreparedPayload>,
    ): FullDeviceSnapshotManifest {
        val manifest = FullDeviceSnapshotManifest.create(
            sourceDatabaseSchemaVersion = request.sourceDatabaseSchemaVersion,
            sourceRoomIdentityHash = request.sourceRoomIdentityHash,
            sourceApplicationVersion = request.sourceApplicationVersion,
            createdAtEpochMillis = request.createdAtEpochMillis,
            entries = payloads.map { it.entry },
            tableDigests = request.tableDigests,
        )
        if (FullDeviceSnapshotManifestPolicy.firstViolation(manifest, limits) != null) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.MANIFEST_CONTRACT_VIOLATION)
        }
        return manifest
    }

    private fun writeDeterministicArchive(
        archive: Path,
        manifestBytes: ByteArray,
        payloads: List<PreparedPayload>,
    ) {
        BufferedOutputStream(
            Files.newOutputStream(
                archive,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ),
        ).use { output ->
            val zip = DeterministicStoredZipWriter(output)
            zip.writeBytes(
                name = FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH,
                bytes = manifestBytes,
            )
            payloads.forEach { payload -> zip.writePayload(payload) }
            zip.finish()
        }
    }

    private fun deterministicArchiveSize(
        manifestBytes: ByteArray,
        payloads: List<PreparedPayload>,
    ): Long {
        val entries = buildList {
            add(FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH to manifestBytes.size.toLong())
            payloads.forEach { payload ->
                add(payload.entry.path to payload.entry.uncompressedSizeBytes)
            }
        }
        return try {
            entries.fold(END_OF_CENTRAL_DIRECTORY_BYTES) { total, (name, payloadBytes) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8).size.toLong()
                Math.addExact(
                    total,
                    Math.addExact(
                        payloadBytes,
                        Math.addExact(ZIP_ENTRY_FIXED_OVERHEAD_BYTES, nameBytes * 2L),
                    ),
                )
            }.also { archiveBytes ->
                if (archiveBytes > UINT_MAX) {
                    reject(FullDeviceSnapshotArchiveProductionFailureCode.ARCHIVE_TOO_LARGE)
                }
            }
        } catch (_: ArithmeticException) {
            reject(FullDeviceSnapshotArchiveProductionFailureCode.ARCHIVE_TOO_LARGE)
        }
    }

    private fun DeterministicStoredZipWriter.writePayload(payload: PreparedPayload) {
        writeEntry(
            name = payload.entry.path,
            size = payload.entry.uncompressedSizeBytes,
            crc32 = payload.crc32,
        ) { output ->
            val actualDigest = MessageDigest.getInstance("SHA-256")
            val actualCrc = CRC32()
            var actualSize = 0L
            FileChannel.open(
                payload.source,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { input ->
                val buffer = ByteBuffer.allocate(STREAM_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (actualSize > payload.entry.uncompressedSizeBytes - read) {
                        reject(
                            FullDeviceSnapshotArchiveProductionFailureCode
                                .SOURCE_CHANGED_DURING_PRODUCTION,
                        )
                    }
                    output.write(buffer.array(), 0, read)
                    actualDigest.update(buffer.array(), 0, read)
                    actualCrc.update(buffer.array(), 0, read)
                    actualSize += read
                    buffer.clear()
                }
            }
            if (
                actualSize != payload.entry.uncompressedSizeBytes ||
                actualCrc.value != payload.crc32 ||
                actualDigest.digest().toHex() != payload.entry.sha256
            ) {
                reject(
                    FullDeviceSnapshotArchiveProductionFailureCode
                        .SOURCE_CHANGED_DURING_PRODUCTION,
                )
            }
        }
    }

    private fun forceFile(path: Path) {
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { archive -> archive.force(true) }
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { input ->
            val buffer = ByteBuffer.allocate(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer.array(), 0, read)
                buffer.clear()
            }
        }
        return digest.digest().toHex()
    }

    private fun uniqueValidationStaging(parent: Path): Path {
        repeat(MAX_TEMP_NAME_ATTEMPTS) {
            val candidate = parent.resolve("$VALIDATION_STAGE_PREFIX${UUID.randomUUID()}")
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate
        }
        reject(FullDeviceSnapshotArchiveProductionFailureCode.IO_FAILURE)
    }

    private fun rollbackPublishedLink(destination: Path, temporaryArchive: Path?): Boolean {
        if (temporaryArchive == null) return false
        val destinationParent = destination.parent ?: return false
        return try {
            if (
                Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
                Files.exists(temporaryArchive, LinkOption.NOFOLLOW_LINKS) &&
                Files.isSameFile(destination, temporaryArchive)
            ) {
                Files.delete(destination)
            }
            !Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
                forceDirectory(destinationParent)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun cleanupTemporaries(archive: Path?, stagingDirectory: Path?): Boolean {
        val cleanupParent = archive?.parent ?: stagingDirectory?.parent
        val stagingClean = deleteTree(stagingDirectory)
        val archiveClean = deleteFile(archive)
        return stagingClean && archiveClean &&
            (cleanupParent == null || forceDirectory(cleanupParent))
    }

    private fun deleteFile(path: Path?): Boolean {
        if (path == null) return true
        return try {
            Files.deleteIfExists(path)
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun deleteTree(path: Path?): Boolean {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun forceDirectoryWithoutThrowing(channel: FileChannel): Boolean = try {
        channel.force(true)
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun forceDirectory(path: Path): Boolean = try {
        FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { directory -> directory.force(true) }
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun reject(code: FullDeviceSnapshotArchiveProductionFailureCode): Nothing =
        throw ProductionRejectedException(code)

    private data class PreparedPayload(
        val source: Path,
        val entry: FullDeviceSnapshotEntry,
        val crc32: Long,
    )

    private class ProductionRejectedException(
        val code: FullDeviceSnapshotArchiveProductionFailureCode,
    ) : RuntimeException()

    /** ZIP STORED clásico, sin data descriptors, extras ni timestamps dependientes del host. */
    private class DeterministicStoredZipWriter(
        destination: OutputStream,
    ) {
        private val output = CountingOutputStream(destination)
        private val records = mutableListOf<CentralDirectoryRecord>()
        private var finished = false

        fun writeBytes(name: String, bytes: ByteArray) {
            val crc = CRC32().apply { update(bytes) }
            writeEntry(name, bytes.size.toLong(), crc.value) { body -> body.write(bytes) }
        }

        fun writeEntry(
            name: String,
            size: Long,
            crc32: Long,
            writeBody: (OutputStream) -> Unit,
        ) {
            check(!finished)
            requireUInt32(size)
            requireUInt32(crc32)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            require(nameBytes.size <= USHORT_MAX)
            val localHeaderOffset = output.count
            requireUInt32(localHeaderOffset)

            output.writeUInt32(LOCAL_FILE_HEADER_SIGNATURE)
            output.writeUInt16(VERSION_NEEDED)
            output.writeUInt16(UTF8_FLAG)
            output.writeUInt16(STORED_METHOD)
            output.writeUInt16(FIXED_DOS_TIME)
            output.writeUInt16(FIXED_DOS_DATE)
            output.writeUInt32(crc32)
            output.writeUInt32(size)
            output.writeUInt32(size)
            output.writeUInt16(nameBytes.size)
            output.writeUInt16(0)
            output.write(nameBytes)

            val bodyStart = output.count
            writeBody(output)
            if (output.count - bodyStart != size) {
                throw IOException("ZIP entry size changed while streaming")
            }
            records += CentralDirectoryRecord(
                nameBytes = nameBytes,
                size = size,
                crc32 = crc32,
                localHeaderOffset = localHeaderOffset,
            )
        }

        fun finish() {
            check(!finished)
            require(records.size <= USHORT_MAX)
            val centralDirectoryOffset = output.count
            requireUInt32(centralDirectoryOffset)
            records.forEach { record ->
                output.writeUInt32(CENTRAL_DIRECTORY_HEADER_SIGNATURE)
                output.writeUInt16(VERSION_MADE_BY)
                output.writeUInt16(VERSION_NEEDED)
                output.writeUInt16(UTF8_FLAG)
                output.writeUInt16(STORED_METHOD)
                output.writeUInt16(FIXED_DOS_TIME)
                output.writeUInt16(FIXED_DOS_DATE)
                output.writeUInt32(record.crc32)
                output.writeUInt32(record.size)
                output.writeUInt32(record.size)
                output.writeUInt16(record.nameBytes.size)
                output.writeUInt16(0)
                output.writeUInt16(0)
                output.writeUInt16(0)
                output.writeUInt16(0)
                output.writeUInt32(0)
                output.writeUInt32(record.localHeaderOffset)
                output.write(record.nameBytes)
            }
            val centralDirectorySize = output.count - centralDirectoryOffset
            requireUInt32(centralDirectorySize)
            output.writeUInt32(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
            output.writeUInt16(0)
            output.writeUInt16(0)
            output.writeUInt16(records.size)
            output.writeUInt16(records.size)
            output.writeUInt32(centralDirectorySize)
            output.writeUInt32(centralDirectoryOffset)
            output.writeUInt16(0)
            output.flush()
            finished = true
        }

        private fun requireUInt32(value: Long) {
            require(value in 0L..UINT_MAX)
        }

        private data class CentralDirectoryRecord(
            val nameBytes: ByteArray,
            val size: Long,
            val crc32: Long,
            val localHeaderOffset: Long,
        )
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count += 1L
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length.toLong()
        }

        override fun flush() = delegate.flush()

        fun writeUInt16(value: Int) {
            require(value in 0..USHORT_MAX)
            write(value and 0xff)
            write((value ushr 8) and 0xff)
        }

        fun writeUInt32(value: Long) {
            require(value in 0L..UINT_MAX)
            write((value and 0xff).toInt())
            write(((value ushr 8) and 0xff).toInt())
            write(((value ushr 16) and 0xff).toInt())
            write(((value ushr 24) and 0xff).toInt())
        }
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_TEMP_NAME_ATTEMPTS = 8
        const val TEMP_ARCHIVE_PREFIX = ".full-device-snapshot-"
        const val TEMP_ARCHIVE_SUFFIX = ".tmp"
        const val VALIDATION_STAGE_PREFIX = ".full-device-snapshot-validation-"

        const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
        const val CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50L
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
        const val VERSION_NEEDED = 10
        const val VERSION_MADE_BY = 20
        const val UTF8_FLAG = 0x0800
        const val STORED_METHOD = 0
        const val FIXED_DOS_TIME = 0
        const val FIXED_DOS_DATE = 0x0021
        const val USHORT_MAX = 0xffff
        const val UINT_MAX = 0xffff_ffffL
        const val ZIP_ENTRY_FIXED_OVERHEAD_BYTES = 30L + 46L
        const val END_OF_CENTRAL_DIRECTORY_BYTES = 22L
    }
}
