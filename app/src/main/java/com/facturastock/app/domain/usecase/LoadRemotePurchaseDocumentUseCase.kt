package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDocumentDownloadResult
import com.facturastock.app.domain.repository.RemoteDocumentReference

/**
 * Fallback de lectura temporal para una imagen local ausente. No restaura archivos ni altera
 * Room: exige los dos opt-ins frescos y un ACK de subida durable sin tombstone de purga antes de
 * permitir que el adaptador autenticado consulte Storage.
 */
class LoadRemotePurchaseDocumentUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val documentLifecycle: DocumentBackupLifecycleRepository,
    private val remoteDocumentArchive: RemoteDocumentArchive,
) {
    suspend operator fun invoke(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        imageId: ImageId,
    ): RemotePurchaseDocumentLoadResult {
        if (!isEligible(businessId, purchaseId, imageId)) {
            return RemotePurchaseDocumentLoadResult.NotEligible
        }
        // La primera comprobación puede haber suspendido; repite inmediatamente antes de delegar
        // el I/O. El adaptador cloud vuelve a leer consentimiento tras resolver sesión/binding.
        if (!isEligible(businessId, purchaseId, imageId)) {
            return RemotePurchaseDocumentLoadResult.NotEligible
        }
        return when (
            val result = remoteDocumentArchive.download(
                RemoteDocumentReference(
                    businessId = businessId,
                    purchaseId = purchaseId,
                    imageId = imageId,
                ),
            )
        ) {
            is RemoteDocumentDownloadResult.Downloaded -> try {
                // El opt-out o un PURGE pueden ocurrir mientras Storage responde. Revalidar antes
                // de entregar el buffer evita que bytes ya revocados lleguen a Compose/Coil.
                if (isEligible(businessId, purchaseId, imageId)) {
                    RemotePurchaseDocumentLoadResult.Downloaded(result.jpeg)
                } else {
                    result.jpeg.fill(0)
                    RemotePurchaseDocumentLoadResult.NotEligible
                }
            } catch (failure: Throwable) {
                result.jpeg.fill(0)
                throw failure
            }
            RemoteDocumentDownloadResult.NotEligible ->
                RemotePurchaseDocumentLoadResult.NotEligible
            RemoteDocumentDownloadResult.NotFound -> RemotePurchaseDocumentLoadResult.NotFound
            RemoteDocumentDownloadResult.AccessDenied ->
                RemotePurchaseDocumentLoadResult.AccessDenied
            RemoteDocumentDownloadResult.IntegrityRejected ->
                RemotePurchaseDocumentLoadResult.IntegrityRejected
            RemoteDocumentDownloadResult.Unavailable ->
                RemotePurchaseDocumentLoadResult.Unavailable
        }
    }

    private suspend fun isEligible(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        imageId: ImageId,
    ): Boolean {
        val configuration = appConfigurationRepository.current()
        return configuration.activeBusinessId == businessId &&
            configuration.backupEnabled &&
            configuration.documentBackupEnabled &&
            remoteDocumentArchive.configured &&
            documentLifecycle.isBackedUp(businessId, purchaseId, imageId)
    }
}

sealed interface RemotePurchaseDocumentLoadResult {
    data class Downloaded(val jpeg: ByteArray) : RemotePurchaseDocumentLoadResult
    data object NotEligible : RemotePurchaseDocumentLoadResult
    data object NotFound : RemotePurchaseDocumentLoadResult
    data object AccessDenied : RemotePurchaseDocumentLoadResult
    data object IntegrityRejected : RemotePurchaseDocumentLoadResult
    data object Unavailable : RemotePurchaseDocumentLoadResult
}
