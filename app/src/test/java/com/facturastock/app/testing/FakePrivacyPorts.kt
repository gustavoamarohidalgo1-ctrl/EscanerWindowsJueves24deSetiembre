package com.facturastock.app.testing

import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.OcrVersionSweepReport
import com.facturastock.app.domain.model.PrivateFileSweepReport
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.RetainedImageEncryptionState
import com.facturastock.app.domain.model.RetainedImageMigrationState
import com.facturastock.app.domain.model.UserDataExport
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentPurgeIntentResult
import com.facturastock.app.domain.repository.DocumentUploadArtifactSweepReport
import com.facturastock.app.domain.repository.RetainedImageStore
import com.facturastock.app.domain.repository.RetainedImageReadResult
import com.facturastock.app.domain.repository.RetentionFileSweep
import com.facturastock.app.domain.repository.OcrVersionSweepDecision
import com.facturastock.app.domain.repository.UserDataExportWriter
import java.time.Instant

/**
 * Fake en memoria del almacén de imágenes retenidas: los "archivos" son bytes por ruta
 * relativa y el cifrado se simula con un conjunto de rutas cifradas. Registra borrados y
 * cifrados para que las pruebas verifiquen el reporte del mantenimiento; admite fallos por
 * ruta para cubrir el mejor esfuerzo por archivo.
 */
class FakeRetainedImageStore : RetainedImageStore {
    private val files = mutableMapOf<String, ByteArray>()
    private val encryptedPaths = mutableSetOf<String>()
    private val corruptPaths = mutableSetOf<String>()

    /** Rutas cuyo borrado o cifrado devuelve false (simula un archivo no eliminable). */
    val failingPaths = mutableSetOf<String>()
    /** Rutas cuyo borrado lanza después de que la purga remota ya pudo quedar durable. */
    val throwingDeletePaths = mutableSetOf<String>()

    val deletedPaths = mutableListOf<String>()
    val encryptInPlaceAttempts = mutableListOf<String>()
    val encryptedInPlacePaths = mutableListOf<String>()
    var beforeDelete: ((String) -> Unit)? = null

    /** Crea un archivo retenido en claro. */
    fun putFile(relativePath: String, content: ByteArray = byteArrayOf(1, 2, 3)) {
        files[relativePath] = content
        encryptedPaths.remove(relativePath)
        corruptPaths.remove(relativePath)
    }

    /** Crea un archivo retenido ya cifrado. */
    fun putEncryptedFile(relativePath: String, content: ByteArray = byteArrayOf(1, 2, 3)) {
        files[relativePath] = content
        encryptedPaths += relativePath
        corruptPaths.remove(relativePath)
    }

    fun putCorruptFile(relativePath: String, content: ByteArray = byteArrayOf(4, 5, 6)) {
        files[relativePath] = content
        encryptedPaths.remove(relativePath)
        corruptPaths += relativePath
    }

    fun exists(relativePath: String): Boolean = relativePath in files

    override suspend fun readForDisplay(relativePath: String): RetainedImageReadResult = when {
        relativePath in corruptPaths -> RetainedImageReadResult.IntegrityRejected
        relativePath !in files -> RetainedImageReadResult.Absent
        else -> RetainedImageReadResult.Available(requireNotNull(files[relativePath]).copyOf())
    }

    override suspend fun readDecrypted(relativePath: String): ByteArray? =
        (readForDisplay(relativePath) as? RetainedImageReadResult.Available)?.bytes

    override suspend fun encryptInPlace(relativePath: String): Boolean {
        encryptInPlaceAttempts += relativePath
        if (relativePath in failingPaths) return false
        if (relativePath !in files) return false
        if (relativePath in corruptPaths) return false
        if (relativePath in encryptedPaths) return true
        encryptedPaths += relativePath
        encryptedInPlacePaths += relativePath
        return true
    }

    override suspend fun encryptionState(
        relativePath: String,
    ): RetainedImageEncryptionState = when {
        relativePath in corruptPaths -> RetainedImageEncryptionState.CORRUPT
        relativePath in encryptedPaths -> RetainedImageEncryptionState.ENCRYPTED
        relativePath in files -> RetainedImageEncryptionState.PLAINTEXT
        else -> RetainedImageEncryptionState.UNAVAILABLE
    }

    override suspend fun encryptionMigrationState(
        relativePath: String,
    ): RetainedImageMigrationState = when {
        relativePath in corruptPaths -> RetainedImageMigrationState.CORRUPT
        relativePath in encryptedPaths -> RetainedImageMigrationState.ENVELOPED
        relativePath in files -> RetainedImageMigrationState.PLAINTEXT
        else -> RetainedImageMigrationState.ABSENT
    }

    override suspend fun delete(relativePath: String): PrivateImageDeletionResult {
        beforeDelete?.invoke(relativePath)
        if (relativePath in throwingDeletePaths) throw IllegalStateException("delete fallido")
        if (relativePath in failingPaths) return PrivateImageDeletionResult.FAILED
        val removed = files.remove(relativePath) != null
        encryptedPaths.remove(relativePath)
        corruptPaths.remove(relativePath)
        if (removed) deletedPaths += relativePath
        return if (removed) {
            PrivateImageDeletionResult.DELETED
        } else {
            PrivateImageDeletionResult.ALREADY_ABSENT
        }
    }
}

class FakeDocumentBackupLifecycleRepository : DocumentBackupLifecycleRepository {
    data class PurgeCall(
        val businessId: BusinessId,
        val image: RetainedImageRef,
        val requestedAt: Instant,
    )

    var purgeResult: DocumentPurgeIntentResult = DocumentPurgeIntentResult.NOT_REQUIRED
    var purgeFailure: Exception? = null
    var retainedUploadsResult: Int = 0
    var withdrawnUploadsResult: Int = 0
    val withdrawAllCalls = mutableListOf<Instant>()
    var artifactSweepResult: DocumentUploadArtifactSweepReport =
        DocumentUploadArtifactSweepReport()
    var artifactSweepFailure: Exception? = null
    var onEnsurePurge: (() -> Unit)? = null
    val purgeCalls = mutableListOf<PurgeCall>()

    override suspend fun ensurePurge(
        businessId: BusinessId,
        image: RetainedImageRef,
        requestedAt: Instant,
    ): DocumentPurgeIntentResult {
        purgeCalls += PurgeCall(businessId, image, requestedAt)
        onEnsurePurge?.invoke()
        purgeFailure?.let { throw it }
        return purgeResult
    }

    override suspend fun ensureRetainedUploads(
        businessId: BusinessId,
        postedAfterExclusive: Instant?,
        requestedAt: Instant,
    ): Int = retainedUploadsResult

    override suspend fun withdrawOpenUploads(
        businessId: BusinessId,
        requestedAt: Instant,
    ): Int = withdrawnUploadsResult

    override suspend fun withdrawAllOpenUploads(requestedAt: Instant): Int {
        withdrawAllCalls += requestedAt
        return withdrawnUploadsResult
    }

    override suspend fun sweepOrphanedPreparedArtifacts(): DocumentUploadArtifactSweepReport {
        artifactSweepFailure?.let { throw it }
        return artifactSweepResult
    }
}

/**
 * Fake de los barridos de archivos: cada barrido devuelve el contador programado y registra
 * los argumentos recibidos. Un contador negativo programa un fallo de la operación completa
 * (el caso de uso lo reporta como cero).
 */
class FakeRetentionFileSweep : RetentionFileSweep {
    var staleImportsResult: Int = 0
    var staleCacheEntriesResult: Int = 0
    var stalePrivateTempsResult: Int = 0
    var orphanDraftDirsResult: Int = 0
    var unreferencedDraftImagesResult: PrivateFileSweepReport = PrivateFileSweepReport()
    var committedOcrRunsResult: Int = 0
    var allDraftOcrResult: OcrVersionSweepReport = OcrVersionSweepReport()
    var allDraftOcrFailure: Exception? = null
    var orphanCandidateIds: Set<DraftId> = emptySet()
    var orphanCandidatePaths: Set<String> = emptySet()
    var beforeOrphanRevalidation: suspend () -> Unit = {}
    val orphanExistenceDecisions = mutableMapOf<DraftId, Boolean>()
    val orphanGlobalPathReferenceDecisions = mutableMapOf<String, Boolean>()
    var committedOcrCandidateIds: Set<DraftId> = emptySet()
    var unreferencedCandidateIds: Set<DraftId> = emptySet()
    var unreferencedCandidatePaths: Set<String> = emptySet()
    var beforeUnreferencedRevalidation: suspend () -> Unit = {}
    val unreferencedPathsByDraft = mutableMapOf<DraftId, Set<String>?>()
    val globalPathReferenceDecisions = mutableMapOf<String, Boolean>()

    var lastStaleImportsNow: Instant? = null
        private set
    var lastStaleCacheNow: Instant? = null
        private set
    var lastStalePrivateTempsNow: Instant? = null
        private set
    var lastOrphanExistingDraftIds: Set<DraftId>? = null
        private set
    var lastCommittedDraftIds: Set<DraftId>? = null
        private set
    var lastUnreferencedDraftIds: Set<DraftId>? = null
        private set
    var lastAllOcrDraftIds: Set<DraftId>? = null
        private set
    var allOcrCandidateIds: Set<DraftId> = emptySet()
    val allOcrDecisions = mutableMapOf<DraftId, OcrVersionSweepDecision>()

    override suspend fun sweepStaleImports(
        now: Instant,
        forceDeletionCutoff: Instant?,
    ): PrivateFileSweepReport {
        lastStaleImportsNow = now
        if (staleImportsResult < 0) throw IllegalStateException("barrido programado para fallar")
        return deletedReport(staleImportsResult)
    }

    override suspend fun sweepStaleCache(
        now: Instant,
        forceDeletionCutoff: Instant?,
    ): PrivateFileSweepReport {
        lastStaleCacheNow = now
        if (staleCacheEntriesResult < 0) {
            throw IllegalStateException("barrido programado para fallar")
        }
        return deletedReport(staleCacheEntriesResult)
    }

    override suspend fun sweepStalePrivateTemps(
        now: Instant,
        forceDeletionCutoff: Instant?,
    ): PrivateFileSweepReport {
        lastStalePrivateTempsNow = now
        if (stalePrivateTempsResult < 0) {
            throw IllegalStateException("barrido programado para fallar")
        }
        return deletedReport(stalePrivateTempsResult)
    }

    override suspend fun sweepOrphanDraftImageDirs(
        now: Instant,
        existingDraftIds: Set<DraftId>,
        forceDeletionCutoff: Instant?,
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
        draftExists: suspend (DraftId) -> Boolean,
    ): PrivateFileSweepReport {
        lastOrphanExistingDraftIds = existingDraftIds
        beforeOrphanRevalidation()
        orphanCandidateIds.forEach { draftId ->
            orphanExistenceDecisions[draftId] = draftExists(draftId)
        }
        orphanCandidatePaths.forEach { path ->
            orphanGlobalPathReferenceDecisions[path] = pathIsReferencedAnywhere(path)
        }
        if (orphanDraftDirsResult < 0) throw IllegalStateException("barrido programado para fallar")
        return deletedReport(orphanDraftDirsResult)
    }

    override suspend fun sweepCommittedOcrVersions(
        committedDraftIds: Set<DraftId>,
        draftIsCommitted: suspend (DraftId) -> Boolean,
    ): PrivateFileSweepReport {
        lastCommittedDraftIds = committedDraftIds
        committedOcrCandidateIds.filter { it in committedDraftIds }.forEach {
            draftIsCommitted(it)
        }
        if (committedOcrRunsResult < 0) throw IllegalStateException("barrido programado para fallar")
        return deletedReport(committedOcrRunsResult)
    }

    override suspend fun sweepUnreferencedDraftImages(
        now: Instant,
        existingDraftIds: Set<DraftId>,
        forceDeletionCutoff: Instant?,
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
        referencedPaths: suspend (DraftId) -> Set<String>?,
    ): PrivateFileSweepReport {
        lastUnreferencedDraftIds = existingDraftIds
        beforeUnreferencedRevalidation()
        unreferencedCandidateIds.forEach { draftId ->
            unreferencedPathsByDraft[draftId] = referencedPaths(draftId)
        }
        unreferencedCandidatePaths.forEach { path ->
            globalPathReferenceDecisions[path] = pathIsReferencedAnywhere(path)
        }
        return unreferencedDraftImagesResult
    }

    override suspend fun sweepAllDraftOcrVersions(
        existingDraftIds: Set<DraftId>,
        decision: suspend (DraftId) -> OcrVersionSweepDecision,
    ): OcrVersionSweepReport {
        allDraftOcrFailure?.let { throw it }
        lastAllOcrDraftIds = existingDraftIds
        allOcrCandidateIds.forEach { draftId ->
            allOcrDecisions[draftId] = decision(draftId)
        }
        return allDraftOcrResult
    }

    private fun deletedReport(count: Int): PrivateFileSweepReport =
        PrivateFileSweepReport(attempted = count, deleted = count)
}

class FakeUserDataExportWriter : UserDataExportWriter {
    var result: UserDataExportWriteStatus = UserDataExportWriteStatus.WRITTEN
    val writes = mutableListOf<Pair<String, UserDataExport>>()

    override suspend fun write(
        documentUri: String,
        export: UserDataExport,
    ): UserDataExportWriteStatus {
        writes += documentUri to export
        return result
    }
}
