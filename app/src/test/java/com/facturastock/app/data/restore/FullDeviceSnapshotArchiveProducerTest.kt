package com.facturastock.app.data.restore

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.TimeZone
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDeviceSnapshotArchiveProducerTest {
    private val testRoot = Files.createTempDirectory("full-device-snapshot-producer-test")
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
    fun `produces and self-validates a complete snapshot with manifest first and sales`() {
        val database = writeSource("source.db", "SQLite format 3\u0000all-room-tables".toByteArray())
        val image = writeSource("image.jpg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3))
        val settings = writeSource("settings.json", "{\"locale\":\"es-PE\"}".toByteArray())
        val destination = testRoot.resolve("complete.zip")
        val request = request(
            database = database,
            destination = destination,
            payloads = listOf(
                FullDeviceSnapshotSourcePayload(
                    source = settings,
                    archivePath = "settings/app-settings.json",
                    kind = FullDeviceSnapshotEntryKind.SAFE_APP_SETTINGS,
                ),
                FullDeviceSnapshotSourcePayload(
                    source = image,
                    archivePath = "files/draft_images/image-1.jpg",
                    kind = FullDeviceSnapshotEntryKind.PRIVATE_FILE,
                ),
            ),
        )

        val produced = assertProduced(FullDeviceSnapshotArchiveProducer().produce(request))

        assertEquals(destination.toAbsolutePath(), produced.destination)
        assertTrue(produced.temporaryArtifactsCleanAndForced)
        assertEquals(Files.size(destination), produced.archiveSizeBytes)
        assertEquals(
            FullDeviceSnapshotDigests.sha256(Files.readAllBytes(destination)),
            produced.archiveSha256,
        )
        assertEquals(
            FullDeviceSnapshotDatabaseCoverage.COMPLETE_ROOM_DATABASE_INCLUDING_SALES,
            produced.manifest.databaseCoverage,
        )
        assertTrue(produced.manifest.tableDigests.any { it.tableName == "sales" })
        assertTrue(produced.manifest.tableDigests.any { it.tableName == "sale_lines" })

        ZipFile(destination.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(FullDeviceSnapshotContract.MANIFEST_ENTRY_PATH, names.first())
            assertEquals(
                produced.manifest.entries.map { it.path },
                names.drop(1),
            )
        }
        val validationStage = testRoot.resolve("external-validation")
        val validation = FullDeviceSnapshotArchiveValidator().validateAndStage(
            destination,
            validationStage,
        )
        assertTrue(validation is FullDeviceSnapshotArchiveValidationResult.Validated)
        validation as FullDeviceSnapshotArchiveValidationResult.Validated
        assertEquals(produced.manifest, validation.snapshot.manifest)
        assertArrayEquals(
            Files.readAllBytes(database),
            Files.readAllBytes(validation.snapshot.stagedDatabase),
        )
        assertNoProducerTemporaries()
    }

    @Test
    fun `is byte deterministic across input order and host timezone`() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            val database = writeSource("deterministic.db", "stable-database".toByteArray())
            val first = writeSource("first.bin", byteArrayOf(1, 2, 3, 4))
            val second = writeSource("second.bin", byteArrayOf(9, 8, 7))
            val payloads = listOf(
                FullDeviceSnapshotSourcePayload(
                    source = first,
                    archivePath = "files/z-last.bin",
                    kind = FullDeviceSnapshotEntryKind.PRIVATE_FILE,
                ),
                FullDeviceSnapshotSourcePayload(
                    source = second,
                    archivePath = "settings/a-first.bin",
                    kind = FullDeviceSnapshotEntryKind.SAFE_APP_SETTINGS,
                ),
            )

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val firstResult = assertProduced(
                FullDeviceSnapshotArchiveProducer().produce(
                    request(database, testRoot.resolve("deterministic-1.zip"), payloads),
                ),
            )
            TimeZone.setDefault(TimeZone.getTimeZone("America/Lima"))
            val secondResult = assertProduced(
                FullDeviceSnapshotArchiveProducer().produce(
                    request(database, testRoot.resolve("deterministic-2.zip"), payloads.reversed()),
                ),
            )

            assertEquals(firstResult.manifest, secondResult.manifest)
            assertEquals(firstResult.archiveSha256, secondResult.archiveSha256)
            assertArrayEquals(
                Files.readAllBytes(firstResult.destination),
                Files.readAllBytes(secondResult.destination),
            )
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `rejects symlink sources noncanonical paths and case-fold aliases`() {
        val database = writeSource("safe.db", "database".toByteArray())
        val payload = writeSource("payload.bin", byteArrayOf(1, 2, 3))
        val symlink = testRoot.resolve("payload-link.bin")
        Files.createSymbolicLink(symlink, payload.fileName)

        assertRejected(
            FullDeviceSnapshotArchiveProducer().produce(
                request(
                    database,
                    testRoot.resolve("symlink.zip"),
                    listOf(privatePayload(symlink, "files/symlink.bin")),
                ),
            ),
            FullDeviceSnapshotArchiveProductionFailureCode.SOURCE_IS_SYMLINK,
        )
        assertRejected(
            FullDeviceSnapshotArchiveProducer().produce(
                request(
                    database,
                    testRoot.resolve("traversal.zip"),
                    listOf(privatePayload(payload, "files/../escaped.bin")),
                ),
            ),
            FullDeviceSnapshotArchiveProductionFailureCode.INVALID_ARCHIVE_PATH,
        )
        assertRejected(
            FullDeviceSnapshotArchiveProducer().produce(
                request(
                    database,
                    testRoot.resolve("aliases.zip"),
                    listOf(
                        privatePayload(payload, "files/Page.bin"),
                        privatePayload(payload, "files/page.bin"),
                    ),
                ),
            ),
            FullDeviceSnapshotArchiveProductionFailureCode.DUPLICATE_ENTRY,
        )

        assertFalse(Files.exists(testRoot.resolve("symlink.zip"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(testRoot.resolve("traversal.zip"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(testRoot.resolve("aliases.zip"), LinkOption.NOFOLLOW_LINKS))
        assertNoProducerTemporaries()
    }

    @Test
    fun `never overwrites an existing destination or follows a destination symlink`() {
        val database = writeSource("existing-source.db", "database".toByteArray())
        val destination = testRoot.resolve("existing.zip")
        val sentinel = byteArrayOf(7, 7, 7, 7)
        Files.write(destination, sentinel)

        val existing = assertRejected(
            FullDeviceSnapshotArchiveProducer().produce(request(database, destination)),
            FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_EXISTS,
        )
        assertTrue(existing.destinationUntouched)
        assertArrayEquals(sentinel, Files.readAllBytes(destination))

        val symlinkDestination = testRoot.resolve("destination-link.zip")
        Files.createSymbolicLink(symlinkDestination, destination.fileName)
        val symlink = assertRejected(
            FullDeviceSnapshotArchiveProducer().produce(request(database, symlinkDestination)),
            FullDeviceSnapshotArchiveProductionFailureCode.DESTINATION_IS_SYMLINK,
        )
        assertTrue(symlink.destinationUntouched)
        assertArrayEquals(sentinel, Files.readAllBytes(destination))
        assertTrue(Files.isSymbolicLink(symlinkDestination))
        assertNoProducerTemporaries()
    }

    @Test
    fun `corruption before self-validation is rejected and every temporary is removed`() {
        val database = writeSource("corrupt-source.db", "database".toByteArray())
        val destination = testRoot.resolve("must-not-publish.zip")
        val producer = FullDeviceSnapshotArchiveProducer(
            limits = FullDeviceSnapshotLimits(),
            archivePreparedObserver = { archive ->
                val corrupted = Files.readAllBytes(archive)
                corrupted[0] = (corrupted[0].toInt() xor 0xff).toByte()
                Files.write(
                    archive,
                    corrupted,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            },
        )

        val failure = assertRejected(
            producer.produce(request(database, destination)),
            FullDeviceSnapshotArchiveProductionFailureCode.SELF_VALIDATION_FAILED,
        )

        assertNotNull(failure.selfValidationFailure)
        assertTrue(failure.temporaryArtifactsClean)
        assertTrue(failure.destinationUntouched)
        assertFalse(Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
        assertNoProducerTemporaries()
    }

    @Test
    fun `rejects incomplete table evidence and archive limit failures without publishing`() {
        val database = writeSource("limits-source.db", "database".toByteArray())
        val incompleteDestination = testRoot.resolve("incomplete.zip")
        val incomplete = request(database, incompleteDestination).copy(
            tableDigests = tableDigests().filterNot { it.tableName == "sale_lines" },
        )
        assertRejected(
            FullDeviceSnapshotArchiveProducer().produce(incomplete),
            FullDeviceSnapshotArchiveProductionFailureCode.MANIFEST_CONTRACT_VIOLATION,
        )

        val limitedDestination = testRoot.resolve("limited.zip")
        val limits = FullDeviceSnapshotLimits(maxArchiveBytes = 32L)
        assertRejected(
            FullDeviceSnapshotArchiveProducer(limits).produce(
                request(database, limitedDestination),
            ),
            FullDeviceSnapshotArchiveProductionFailureCode.ARCHIVE_TOO_LARGE,
        )

        assertFalse(Files.exists(incompleteDestination, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(limitedDestination, LinkOption.NOFOLLOW_LINKS))
        assertNoProducerTemporaries()
    }

    private fun request(
        database: Path,
        destination: Path,
        payloads: List<FullDeviceSnapshotSourcePayload> = emptyList(),
    ): FullDeviceSnapshotArchiveProductionRequest =
        FullDeviceSnapshotArchiveProductionRequest(
            alreadyCheckpointedDatabase = database,
            payloads = payloads,
            sourceDatabaseSchemaVersion = 24,
            sourceRoomIdentityHash = "a".repeat(32),
            sourceApplicationVersion = "1.0.0-test",
            createdAtEpochMillis = 1_777_777_777_777L,
            tableDigests = tableDigests(),
            destination = destination,
        )

    private fun tableDigests(): List<FullDeviceSnapshotTableDigest> =
        FullDeviceSnapshotContract.requiredV1TablesForSchema(24).map { table ->
            FullDeviceSnapshotTableDigest(
                tableName = table,
                rowCount = if (table == "sales" || table == "sale_lines") 2L else 0L,
                canonicalRowsSha256 = FullDeviceSnapshotDigests.sha256(
                    "canonical-rows:$table".toByteArray(),
                ),
            )
        }

    private fun privatePayload(source: Path, archivePath: String) =
        FullDeviceSnapshotSourcePayload(
            source = source,
            archivePath = archivePath,
            kind = FullDeviceSnapshotEntryKind.PRIVATE_FILE,
        )

    private fun writeSource(name: String, bytes: ByteArray): Path =
        testRoot.resolve("${sequence++}-$name").also { Files.write(it, bytes) }

    private fun assertProduced(
        result: FullDeviceSnapshotArchiveProductionResult,
    ): FullDeviceSnapshotArchiveProductionResult.Produced {
        assertTrue(
            "expected produced snapshot but got $result",
            result is FullDeviceSnapshotArchiveProductionResult.Produced,
        )
        return result as FullDeviceSnapshotArchiveProductionResult.Produced
    }

    private fun assertRejected(
        result: FullDeviceSnapshotArchiveProductionResult,
        expected: FullDeviceSnapshotArchiveProductionFailureCode,
    ): FullDeviceSnapshotArchiveProductionFailure {
        assertTrue(
            "expected rejection $expected but got $result",
            result is FullDeviceSnapshotArchiveProductionResult.Rejected,
        )
        result as FullDeviceSnapshotArchiveProductionResult.Rejected
        assertEquals(expected, result.failure.code)
        assertTrue("all pre-publication failures must clean temporaries", result.failure.temporaryArtifactsClean)
        return result.failure
    }

    private fun assertNoProducerTemporaries() {
        Files.list(testRoot).use { paths ->
            assertFalse(
                paths.anyMatch { path ->
                    val name = path.fileName.toString()
                    name.startsWith(".full-device-snapshot-")
                },
            )
        }
    }
}
