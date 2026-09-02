package com.facturastock.app.data.restore

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullDeviceSnapshotRestoreReadinessTest {
    private val testRoot = Files.createTempDirectory("snapshot-restore-readiness")

    @After
    fun tearDown() {
        if (Files.exists(testRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(testRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `manifest explicitly means complete Room database including sales and requires all checks`() {
        val fixture = fixture()

        assertEquals(
            FullDeviceSnapshotDatabaseCoverage.COMPLETE_ROOM_DATABASE_INCLUDING_SALES,
            fixture.manifest.databaseCoverage,
        )
        assertEquals(
            FullDeviceSnapshotContract.REQUIRED_V1_RESTORE_CHECKS,
            fixture.manifest.requiredRestoreChecks,
        )
        assertTrue(fixture.manifest.tableDigests.any { it.tableName == "sales" })
        assertTrue(fixture.manifest.tableDigests.any { it.tableName == "sale_lines" })
        assertEquals(
            FullDeviceSnapshotEntryKind.ROOM_DATABASE,
            fixture.manifest.entries.single().kind,
        )
    }

    @Test
    fun `receipt issuer requires checksums Room FK integrity and every accounting invariant`() {
        val fixture = fixture()
        val validEvidence = fixture.validEvidence()

        assertTrue(
            FullDeviceSnapshotDatabaseValidationReceiptIssuer.issue(
                fixture.manifest,
                validEvidence,
            ) is FullDeviceSnapshotDatabaseValidationReceiptResult.Issued,
        )
        assertReceiptRejected(
            fixture,
            validEvidence.copy(archiveEntryChecksumsVerified = false),
            FullDeviceSnapshotDatabaseValidationFailureCode.ARCHIVE_CHECKSUMS_NOT_VERIFIED,
        )
        assertReceiptRejected(
            fixture,
            validEvidence.copy(roomSchemaIdentityValidated = false),
            FullDeviceSnapshotDatabaseValidationFailureCode.ROOM_SCHEMA_NOT_VALIDATED,
        )
        assertReceiptRejected(
            fixture,
            validEvidence.copy(foreignKeyViolationCount = 1L),
            FullDeviceSnapshotDatabaseValidationFailureCode.FOREIGN_KEY_VIOLATIONS,
        )
        assertReceiptRejected(
            fixture,
            validEvidence.copy(integrityCheckRows = listOf("ok", "unexpected")),
            FullDeviceSnapshotDatabaseValidationFailureCode.INTEGRITY_CHECK_FAILED,
        )
        assertReceiptRejected(
            fixture,
            validEvidence.copy(
                passedInvariants = validEvidence.passedInvariants.filterNot {
                    it == FullDeviceSnapshotAccountingInvariant.SALE_GRAPHS_COMPLETE
                },
            ),
            FullDeviceSnapshotDatabaseValidationFailureCode.INVARIANTS_INCOMPLETE,
        )
        assertReceiptRejected(
            fixture,
            validEvidence.copy(privateFileChecksumsVerified = false),
            FullDeviceSnapshotDatabaseValidationFailureCode.PRIVATE_FILE_CHECKSUMS_NOT_VERIFIED,
        )
    }

    @Test
    fun `activation remains NOT_READY and cannot replace or rename the active database`() {
        val fixture = fixture()
        val receiptResult = FullDeviceSnapshotDatabaseValidationReceiptIssuer.issue(
            fixture.manifest,
            fixture.validEvidence(),
        ) as FullDeviceSnapshotDatabaseValidationReceiptResult.Issued
        val treeBefore = regularFileTree(testRoot)

        val attempt = FullDeviceSnapshotRestoreCoordinator().requestActivation(
            activeDatabase = fixture.activeDatabase,
            stagedSnapshot = fixture.stagedSnapshot,
            validationReceipt = receiptResult.receipt,
        )

        assertEquals(FullDeviceSnapshotRestoreStatus.NOT_READY, attempt.status)
        assertEquals(
            FullDeviceSnapshotRestoreNotReadyReason.PROCESS_WIDE_ROOM_QUIESCE_NOT_IMPLEMENTED,
            attempt.reason,
        )
        assertEquals(treeBefore, regularFileTree(testRoot))
        assertEquals(ACTIVE_BYTES.toList(), Files.readAllBytes(fixture.activeDatabase).toList())
        assertEquals(CANDIDATE_BYTES.toList(), Files.readAllBytes(fixture.candidateDatabase).toList())
    }

    @Test
    fun `missing receipt or changed candidate stays NOT_READY without touching active database`() {
        val fixture = fixture()
        val coordinator = FullDeviceSnapshotRestoreCoordinator()
        val activeBefore = Files.readAllBytes(fixture.activeDatabase)

        val missing = coordinator.requestActivation(
            activeDatabase = fixture.activeDatabase,
            stagedSnapshot = fixture.stagedSnapshot,
            validationReceipt = null,
        )
        assertEquals(FullDeviceSnapshotRestoreStatus.NOT_READY, missing.status)
        assertEquals(
            FullDeviceSnapshotRestoreNotReadyReason.VALIDATION_RECEIPT_REQUIRED,
            missing.reason,
        )

        val receipt = (
            FullDeviceSnapshotDatabaseValidationReceiptIssuer.issue(
                fixture.manifest,
                fixture.validEvidence(),
            ) as FullDeviceSnapshotDatabaseValidationReceiptResult.Issued
            ).receipt
        Files.write(fixture.candidateDatabase, CANDIDATE_BYTES + 9.toByte())
        val changed = coordinator.requestActivation(
            activeDatabase = fixture.activeDatabase,
            stagedSnapshot = fixture.stagedSnapshot,
            validationReceipt = receipt,
        )
        assertEquals(FullDeviceSnapshotRestoreStatus.NOT_READY, changed.status)
        assertEquals(
            FullDeviceSnapshotRestoreNotReadyReason.CANDIDATE_DATABASE_CHANGED,
            changed.reason,
        )
        assertEquals(activeBefore.toList(), Files.readAllBytes(fixture.activeDatabase).toList())
    }

    private fun fixture(): RestoreFixture {
        val activeDatabase = testRoot.resolve("facturastock.db")
        Files.write(activeDatabase, ACTIVE_BYTES)
        val staging = testRoot.resolve("restore-operation")
        Files.createDirectory(staging)
        val candidateDatabase = staging.resolve(FullDeviceSnapshotContract.DATABASE_ENTRY_PATH)
        Files.createDirectories(candidateDatabase.parent)
        Files.write(candidateDatabase, CANDIDATE_BYTES)

        val archiveFingerprint = fingerprint(CANDIDATE_BYTES)
        val entry = FullDeviceSnapshotEntry(
            path = FullDeviceSnapshotContract.DATABASE_ENTRY_PATH,
            kind = FullDeviceSnapshotEntryKind.ROOM_DATABASE,
            uncompressedSizeBytes = archiveFingerprint.sizeBytes,
            sha256 = archiveFingerprint.sha256,
        )
        val manifest = FullDeviceSnapshotManifest.create(
            sourceDatabaseSchemaVersion = 24,
            sourceRoomIdentityHash = "a".repeat(32),
            sourceApplicationVersion = "1.0.0-test",
            createdAtEpochMillis = 1_777_777_777_777L,
            entries = listOf(entry),
            tableDigests = FullDeviceSnapshotContract.requiredV1TablesForSchema(24).map { table ->
                FullDeviceSnapshotTableDigest(
                    tableName = table,
                    rowCount = 0L,
                    canonicalRowsSha256 = FullDeviceSnapshotDigests.sha256(table.toByteArray()),
                )
            },
        )
        return RestoreFixture(
            activeDatabase = activeDatabase,
            candidateDatabase = candidateDatabase,
            manifest = manifest,
            stagedSnapshot = StagedFullDeviceSnapshot(
                manifest = manifest,
                stagingDirectory = staging,
                stagedDatabase = candidateDatabase,
                stagedPayloads = mapOf(entry.path to candidateDatabase),
            ),
            archiveFingerprint = archiveFingerprint,
        )
    }

    private fun RestoreFixture.validEvidence() = FullDeviceSnapshotDatabaseValidationEvidence(
        snapshotPayloadSetSha256 = manifest.payloadSetSha256,
        archiveDatabase = archiveFingerprint,
        validatedCandidateDatabase = archiveFingerprint,
        archiveEntryChecksumsVerified = true,
        roomSchemaIdentityValidated = true,
        foreignKeyViolationCount = 0L,
        integrityCheckRows = listOf("ok"),
        passedInvariants = FullDeviceSnapshotDatabaseValidationContract.REQUIRED_INVARIANTS,
        privateFileChecksumsVerified = true,
    )

    private fun assertReceiptRejected(
        fixture: RestoreFixture,
        evidence: FullDeviceSnapshotDatabaseValidationEvidence,
        expected: FullDeviceSnapshotDatabaseValidationFailureCode,
    ) {
        assertEquals(
            FullDeviceSnapshotDatabaseValidationReceiptResult.Rejected(expected),
            FullDeviceSnapshotDatabaseValidationReceiptIssuer.issue(fixture.manifest, evidence),
        )
    }

    private fun regularFileTree(root: Path): Map<String, List<Byte>> {
        val result = sortedMapOf<String, List<Byte>>()
        Files.walk(root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .forEach { path ->
                    result[root.relativize(path).toString()] = Files.readAllBytes(path).toList()
                }
        }
        return result
    }

    private fun fingerprint(bytes: ByteArray) = SnapshotDatabaseFingerprint(
        sizeBytes = bytes.size.toLong(),
        sha256 = FullDeviceSnapshotDigests.sha256(bytes),
    )

    private data class RestoreFixture(
        val activeDatabase: Path,
        val candidateDatabase: Path,
        val manifest: FullDeviceSnapshotManifest,
        val stagedSnapshot: StagedFullDeviceSnapshot,
        val archiveFingerprint: SnapshotDatabaseFingerprint,
    )

    private companion object {
        val ACTIVE_BYTES = "active-database-must-survive".toByteArray()
        val CANDIDATE_BYTES = "validated-candidate-database".toByteArray()
    }
}
