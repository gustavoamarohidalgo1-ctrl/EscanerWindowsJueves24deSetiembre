package com.facturastock.app.data.restore

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDeviceSnapshotArchiveValidatorTest {
    private val testRoot = Files.createTempDirectory("full-device-snapshot-test")
    private var sequence = 0

    @After
    fun tearDown() {
        if (Files.exists(testRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(testRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `stages a canonical complete snapshot including sales and verifies every byte`() {
        val payloads = standardPayloads()
        val manifest = manifestFor(payloads)
        val archive = writeArchive(manifest, payloads)
        val stage = nextStage()

        val result = FullDeviceSnapshotArchiveValidator().validateAndStage(archive, stage)

        assertTrue(result is FullDeviceSnapshotArchiveValidationResult.Validated)
        val snapshot = (result as FullDeviceSnapshotArchiveValidationResult.Validated).snapshot
        assertEquals(manifest, snapshot.manifest)
        assertEquals(
            payloads.getValue(FullDeviceSnapshotContract.DATABASE_ENTRY_PATH).toList(),
            Files.readAllBytes(snapshot.stagedDatabase).toList(),
        )
        payloads.forEach { (path, expected) ->
            assertEquals(expected.toList(), Files.readAllBytes(snapshot.stagedPayloads.getValue(path)).toList())
        }
        assertTrue(snapshot.manifest.tableDigests.any { it.tableName == "sales" })
        assertTrue(snapshot.manifest.tableDigests.any { it.tableName == "sale_lines" })
    }

    @Test
    fun `rejects traversal absolute backslash dot and empty segments without escaping staging`() {
        val unsafePaths = listOf(
            "../escaped.db",
            "/absolute.db",
            "files/../escaped.db",
            "files\\escaped.db",
            "files/./escaped.db",
            "files//escaped.db",
        )
        unsafePaths.forEach { unsafePath ->
            val payloads = standardPayloads()
            val manifest = manifestFor(payloads)
            val archive = writeArchive(
                manifest = manifest,
                payloads = payloads,
                extraEntries = listOf(ZipFixture(unsafePath, byteArrayOf(1))),
            )
            val stage = nextStage()

            assertRejected(
                result = FullDeviceSnapshotArchiveValidator().validateAndStage(archive, stage),
                expected = FullDeviceSnapshotArchiveFailureCode.INVALID_ENTRY_PATH,
            )
            assertFalse(Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
        }
        assertFalse(Files.exists(testRoot.parent.resolve("escaped.db")))
    }

    @Test
    fun `rejects exact duplicate central-directory names before extracting`() {
        val firstPath = "files/a.bin"
        val secondPath = "files/b.bin"
        val payloads = standardPayloads() + mapOf(
            firstPath to byteArrayOf(1, 2),
            secondPath to byteArrayOf(3, 4),
        )
        val archive = writeArchive(manifestFor(payloads), payloads)
        patchZipEntryName(archive, from = secondPath, to = firstPath)
        val stage = nextStage()

        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(archive, stage),
            FullDeviceSnapshotArchiveFailureCode.DUPLICATE_ENTRY,
        )
        assertFalse(Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `rejects case-folding aliases to avoid cross-filesystem collisions`() {
        val payloads = standardPayloads() + mapOf(
            "files/Page.bin" to byteArrayOf(1),
            "files/page.bin" to byteArrayOf(2),
        )
        val manifest = manifestFor(payloads)
        val archive = writeArchive(manifest, payloads)

        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(archive, nextStage()),
            FullDeviceSnapshotArchiveFailureCode.DUPLICATE_ENTRY,
        )
    }

    @Test
    fun `rejects checksum corruption and removes every partially staged payload`() {
        val payloads = standardPayloads()
        val valid = manifestFor(payloads)
        val corruptedEntries = valid.entries.map { entry ->
            if (entry.path == FullDeviceSnapshotContract.DATABASE_ENTRY_PATH) {
                entry.copy(sha256 = "0".repeat(64))
            } else {
                entry
            }
        }
        val corrupted = valid.copy(
            entries = corruptedEntries,
            payloadSetSha256 = FullDeviceSnapshotDigests.payloadSet(corruptedEntries),
        )
        val stage = nextStage()

        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(corrupted, payloads),
                stage,
            ),
            FullDeviceSnapshotArchiveFailureCode.ENTRY_CHECKSUM_MISMATCH,
        )
        assertFalse(Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `rejects declared size mismatch unknown payload and missing payload`() {
        val payloads = standardPayloads()
        val valid = manifestFor(payloads)
        val wrongSizeEntries = valid.entries.map { entry ->
            if (entry.path == FullDeviceSnapshotContract.DATABASE_ENTRY_PATH) {
                entry.copy(uncompressedSizeBytes = entry.uncompressedSizeBytes + 1L)
            } else {
                entry
            }
        }
        val wrongSize = valid.copy(
            entries = wrongSizeEntries,
            payloadSetSha256 = FullDeviceSnapshotDigests.payloadSet(wrongSizeEntries),
        )
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(wrongSize, payloads),
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.ENTRY_SIZE_MISMATCH,
        )

        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(
                    valid,
                    payloads,
                    extraEntries = listOf(ZipFixture("files/unknown.bin", byteArrayOf(9))),
                ),
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.UNDECLARED_ENTRY,
        )

        val missingDatabase = payloads - FullDeviceSnapshotContract.DATABASE_ENTRY_PATH
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(valid, missingDatabase),
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.MISSING_ENTRY,
        )
    }

    @Test
    fun `rejects truncated zip directory entry and manifest that is not first`() {
        val payloads = standardPayloads()
        val manifest = manifestFor(payloads)
        val truncated = writeArchive(manifest, payloads)
        val bytes = Files.readAllBytes(truncated)
        Files.write(truncated, bytes.copyOf(bytes.size - 22))
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(truncated, nextStage()),
            FullDeviceSnapshotArchiveFailureCode.INVALID_ZIP,
        )

        val withDirectory = writeArchive(
            manifest,
            payloads,
            extraEntries = listOf(ZipFixture("files/", byteArrayOf(), isDirectory = true)),
        )
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(withDirectory, nextStage()),
            FullDeviceSnapshotArchiveFailureCode.DIRECTORY_ENTRY_NOT_ALLOWED,
        )

        val wrongOrder = writeArchive(manifest, payloads, manifestFirst = false)
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(wrongOrder, nextStage()),
            FullDeviceSnapshotArchiveFailureCode.MANIFEST_NOT_FIRST,
        )
    }

    @Test
    fun `rejects noncanonical invalid and incomplete manifests`() {
        val payloads = standardPayloads()
        val valid = manifestFor(payloads)
        val canonical = FullDeviceSnapshotManifestCodec.encode(valid)
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(valid, payloads, manifestBytes = canonical + '\n'.code.toByte()),
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.MANIFEST_NOT_CANONICAL,
        )
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(valid, payloads, manifestBytes = byteArrayOf(0xc3.toByte(), 0x28)),
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.MANIFEST_INVALID_JSON,
        )

        val withoutSales = valid.copy(
            tableDigests = valid.tableDigests.filterNot { it.tableName == "sales" },
        )
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(withoutSales, payloads),
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.MANIFEST_CONTRACT_VIOLATION,
        )

        val currentSchema = manifestFor(payloads, sourceDatabaseSchemaVersion = 27)
        val currentSchemaResult = FullDeviceSnapshotArchiveValidator().validateAndStage(
            writeArchive(currentSchema, payloads),
            nextStage(),
        )
        assertTrue(currentSchemaResult is FullDeviceSnapshotArchiveValidationResult.Validated)
        val currentWithoutDebts = currentSchema.copy(
            tableDigests = currentSchema.tableDigests.filterNot {
                it.tableName == "debts" || it.tableName == "debt_payments"
            },
        )
        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(
                writeArchive(currentWithoutDebts, payloads),
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.MANIFEST_CONTRACT_VIOLATION,
        )

        listOf(
            valid.copy(sourceDatabaseSchemaVersion = 28),
            valid.copy(requiredRestoreChecks = valid.requiredRestoreChecks.dropLast(1)),
        ).forEach { incompatible ->
            assertRejected(
                FullDeviceSnapshotArchiveValidator().validateAndStage(
                    writeArchive(incompatible, payloads),
                    nextStage(),
                ),
                FullDeviceSnapshotArchiveFailureCode.MANIFEST_CONTRACT_VIOLATION,
            )
        }
    }

    @Test
    fun `enforces archive and expanded byte ceilings before promotion`() {
        val payloads = standardPayloads()
        val manifest = manifestFor(payloads)
        val archive = writeArchive(manifest, payloads)
        val archiveLimit = FullDeviceSnapshotLimits(maxArchiveBytes = Files.size(archive) - 1L)
        assertRejected(
            FullDeviceSnapshotArchiveValidator(archiveLimit).validateAndStage(archive, nextStage()),
            FullDeviceSnapshotArchiveFailureCode.ARCHIVE_TOO_LARGE,
        )

        val manifestBytes = FullDeviceSnapshotManifestCodec.encode(manifest)
        val payloadBytes = payloads.values.sumOf { it.size.toLong() }
        val expandedLimit = FullDeviceSnapshotLimits(
            maxExpandedBytes = manifestBytes.size.toLong() + payloadBytes - 1L,
            maxManifestBytes = manifestBytes.size,
        )
        assertRejected(
            FullDeviceSnapshotArchiveValidator(expandedLimit).validateAndStage(
                archive,
                nextStage(),
            ),
            FullDeviceSnapshotArchiveFailureCode.EXPANDED_SIZE_LIMIT_EXCEEDED,
        )
    }

    @Test
    fun `never overwrites or cleans a caller-owned staging directory`() {
        val payloads = standardPayloads()
        val archive = writeArchive(manifestFor(payloads), payloads)
        val stage = nextStage().also(Files::createDirectory)
        val sentinel = stage.resolve("keep.txt")
        Files.write(sentinel, byteArrayOf(7))

        assertRejected(
            FullDeviceSnapshotArchiveValidator().validateAndStage(archive, stage),
            FullDeviceSnapshotArchiveFailureCode.STAGING_TARGET_EXISTS,
        )
        assertEquals(listOf(7.toByte()), Files.readAllBytes(sentinel).toList())
    }

    private fun standardPayloads(): Map<String, ByteArray> = linkedMapOf(
        FullDeviceSnapshotContract.DATABASE_ENTRY_PATH to "SQLite format 3\u0000fixture".toByteArray(),
        "files/draft_images/image-1.jpg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3),
        "settings/app-settings.json" to "{\"activeBusinessId\":null}".toByteArray(),
    )

    private fun manifestFor(
        payloads: Map<String, ByteArray>,
        sourceDatabaseSchemaVersion: Int = 24,
    ): FullDeviceSnapshotManifest {
        val entries = payloads.map { (path, bytes) ->
            FullDeviceSnapshotEntry(
                path = path,
                kind = when {
                    path == FullDeviceSnapshotContract.DATABASE_ENTRY_PATH ->
                        FullDeviceSnapshotEntryKind.ROOM_DATABASE
                    path.startsWith(FullDeviceSnapshotContract.PRIVATE_FILES_PREFIX) ->
                        FullDeviceSnapshotEntryKind.PRIVATE_FILE
                    else -> FullDeviceSnapshotEntryKind.SAFE_APP_SETTINGS
                },
                uncompressedSizeBytes = bytes.size.toLong(),
                sha256 = FullDeviceSnapshotDigests.sha256(bytes),
            )
        }
        return FullDeviceSnapshotManifest.create(
            sourceDatabaseSchemaVersion = sourceDatabaseSchemaVersion,
            sourceRoomIdentityHash = "a".repeat(32),
            sourceApplicationVersion = "1.0.0-test",
            createdAtEpochMillis = 1_777_777_777_777L,
            entries = entries,
            tableDigests = FullDeviceSnapshotContract
                .requiredV1TablesForSchema(sourceDatabaseSchemaVersion)
                .map { table ->
                FullDeviceSnapshotTableDigest(
                    tableName = table,
                    rowCount = 0L,
                    canonicalRowsSha256 = FullDeviceSnapshotDigests.sha256(
                        "rows:$table".toByteArray(),
                    ),
                )
            },
        )
    }

    private fun writeArchive(
        manifest: FullDeviceSnapshotManifest,
        payloads: Map<String, ByteArray>,
        extraEntries: List<ZipFixture> = emptyList(),
        manifestFirst: Boolean = true,
        manifestBytes: ByteArray = FullDeviceSnapshotManifestCodec.encode(manifest),
    ): Path {
        val archive = testRoot.resolve("snapshot-${sequence++}.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            val fixtures = payloads.map { (name, bytes) -> ZipFixture(name, bytes) } + extraEntries
            if (manifestFirst) {
                zip.writeEntry(FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH, manifestBytes)
                fixtures.forEach { fixture -> zip.writeEntry(fixture) }
            } else {
                require(fixtures.isNotEmpty())
                zip.writeEntry(fixtures.first())
                zip.writeEntry(FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH, manifestBytes)
                fixtures.drop(1).forEach { fixture -> zip.writeEntry(fixture) }
            }
        }
        return archive
    }

    private fun ZipOutputStream.writeEntry(fixture: ZipFixture) {
        val name = if (fixture.isDirectory && !fixture.name.endsWith('/')) "${fixture.name}/" else fixture.name
        putNextEntry(ZipEntry(name))
        if (!fixture.isDirectory) write(fixture.bytes)
        closeEntry()
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) =
        writeEntry(ZipFixture(name, bytes))

    /** Cambia solo nombres en headers local/central; el manifiesto comprimido queda intacto. */
    private fun patchZipEntryName(archive: Path, from: String, to: String) {
        require(from.length == to.length)
        val bytes = Files.readAllBytes(archive)
        val fromBytes = from.toByteArray()
        val toBytes = to.toByteArray()
        var patched = 0
        var offset = 0
        while (offset <= bytes.size - 4) {
            val signature = littleEndianInt(bytes, offset)
            val nameLengthOffset = when (signature) {
                LOCAL_FILE_HEADER -> offset + 26
                CENTRAL_FILE_HEADER -> offset + 28
                else -> {
                    offset += 1
                    continue
                }
            }
            val nameLength = littleEndianShort(bytes, nameLengthOffset)
            val nameOffset = if (signature == LOCAL_FILE_HEADER) offset + 30 else offset + 46
            if (
                nameLength == fromBytes.size &&
                bytes.copyOfRange(nameOffset, nameOffset + nameLength).contentEquals(fromBytes)
            ) {
                toBytes.copyInto(bytes, nameOffset)
                patched += 1
            }
            offset = nameOffset + nameLength
        }
        assertEquals("local header and central directory must be patched", 2, patched)
        Files.write(archive, bytes)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)

    private fun nextStage(): Path = testRoot.resolve("stage-${sequence++}")

    private fun assertRejected(
        result: FullDeviceSnapshotArchiveValidationResult,
        expected: FullDeviceSnapshotArchiveFailureCode,
    ) {
        assertTrue(
            "expected rejection $expected but got $result",
            result is FullDeviceSnapshotArchiveValidationResult.Rejected,
        )
        result as FullDeviceSnapshotArchiveValidationResult.Rejected
        assertEquals(expected, result.failure.code)
        assertTrue("rejected archive must leave no untrusted staging", result.failure.stagingDirectoryClean)
    }

    private data class ZipFixture(
        val name: String,
        val bytes: ByteArray,
        val isDirectory: Boolean = false,
    )

    private companion object {
        const val LOCAL_FILE_HEADER = 0x04034b50
        const val CENTRAL_FILE_HEADER = 0x02014b50
    }
}
