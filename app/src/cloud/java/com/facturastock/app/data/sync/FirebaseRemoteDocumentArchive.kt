package com.facturastock.app.data.sync

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDocumentDownloadResult
import com.facturastock.app.domain.repository.RemoteDocumentReference
import com.google.firebase.storage.StorageException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Descarga autenticada de solo lectura; las reglas siguen siendo la autoridad de membresía. */
@Singleton
class FirebaseRemoteDocumentArchive @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accounts: AccountRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
    private val appConfiguration: AppConfigurationRepository,
    private val documentLifecycle: DocumentBackupLifecycleRepository,
    private val dispatchers: DispatcherProvider,
) : RemoteDocumentArchive {
    override val configured: Boolean
        get() = runtime.config?.storageBucket != null

    override suspend fun download(
        reference: RemoteDocumentReference,
    ): RemoteDocumentDownloadResult = withContext(dispatchers.io) {
        val session = accounts.observeSession().first() as? AccountSession.Active
            ?: return@withContext RemoteDocumentDownloadResult.Unavailable
        val link = session.link ?: return@withContext RemoteDocumentDownloadResult.Unavailable
        if (
            link.localBusinessId != reference.businessId ||
            !cloudBusinessBindings.matches(reference.businessId, link.cloudBusinessId)
        ) {
            return@withContext RemoteDocumentDownloadResult.AccessDenied
        }
        val cloudBusinessId = link.cloudBusinessId
        if (!stillEligible(reference, cloudBusinessId)) {
            return@withContext RemoteDocumentDownloadResult.NotEligible
        }
        val storage = runtime.storage()
            ?: return@withContext RemoteDocumentDownloadResult.Unavailable
        val objectRef = storage.reference.child(
            "businesses/${cloudBusinessId.value}/invoices/" +
                "${reference.purchaseId.value}/${reference.imageId.value}.jpg",
        )
        try {
            // `observeSession().first()` y el binding suspenden. El consentimiento se relee
            // inmediatamente antes del primer I/O de Storage, nunca se hereda del use case.
            if (!stillEligible(reference, cloudBusinessId)) {
                return@withContext RemoteDocumentDownloadResult.NotEligible
            }
            val metadata = objectRef.metadata.await()
            val expectedSha256 = validatedRemoteDocumentSha(
                contentType = metadata.contentType,
                sizeBytes = metadata.sizeBytes,
                sha256 = metadata.getCustomMetadata("sha256"),
                metadataPurchaseId = metadata.getCustomMetadata("purchaseId"),
                metadataImageId = metadata.getCustomMetadata("imageId"),
                expectedPurchaseId = reference.purchaseId.value,
                expectedImageId = reference.imageId.value,
            ) ?: run {
                return@withContext RemoteDocumentDownloadResult.IntegrityRejected
            }
            // Metadata y bytes son dos I/O separados; un opt-out entre ambos corta la descarga.
            if (!stillEligible(reference, cloudBusinessId)) {
                return@withContext RemoteDocumentDownloadResult.NotEligible
            }
            val bytes = objectRef.getBytes(MAX_DOWNLOAD_BYTES).await()
            try {
                if (!stillEligible(reference, cloudBusinessId)) {
                    bytes.fill(0)
                    RemoteDocumentDownloadResult.NotEligible
                } else if (
                    bytes.size.toLong() != metadata.sizeBytes || sha256(bytes) != expectedSha256
                ) {
                    bytes.fill(0)
                    RemoteDocumentDownloadResult.IntegrityRejected
                } else {
                    RemoteDocumentDownloadResult.Downloaded(bytes)
                }
            } catch (failure: Throwable) {
                // Si la revalidación fresca se cancela o falla, el buffer remoto no puede quedar
                // vivo en memoria ni entregarse por una ruta de error.
                bytes.fill(0)
                throw failure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: StorageException) {
            when (failure.errorCode) {
                StorageException.ERROR_OBJECT_NOT_FOUND -> RemoteDocumentDownloadResult.NotFound
                StorageException.ERROR_NOT_AUTHENTICATED,
                StorageException.ERROR_NOT_AUTHORIZED,
                -> RemoteDocumentDownloadResult.AccessDenied
                else -> RemoteDocumentDownloadResult.Unavailable
            }
        } catch (_: Exception) {
            RemoteDocumentDownloadResult.Unavailable
        }
    }

    private suspend fun stillEligible(
        reference: RemoteDocumentReference,
        cloudBusinessId: BusinessId,
    ): Boolean {
        val currentSession = accounts.observeSession().first() as? AccountSession.Active
            ?: return false
        val currentLink = currentSession.link ?: return false
        val configuration = appConfiguration.current()
        return currentLink.localBusinessId == reference.businessId &&
            currentLink.cloudBusinessId == cloudBusinessId &&
            configuration.activeBusinessId == reference.businessId &&
            configuration.backupEnabled &&
            configuration.documentBackupEnabled &&
            cloudBusinessBindings.matches(reference.businessId, cloudBusinessId) &&
            documentLifecycle.isBackedUp(
                reference.businessId,
                reference.purchaseId,
                reference.imageId,
            )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Política pura para metadata protegida. El digest se descubre aquí; nunca se inventa desde Room. */
internal fun validatedRemoteDocumentSha(
    contentType: String?,
    sizeBytes: Long,
    sha256: String?,
    metadataPurchaseId: String?,
    metadataImageId: String?,
    expectedPurchaseId: String,
    expectedImageId: String,
): String? {
    if (
        contentType != JPEG_MIME_TYPE ||
        sizeBytes !in 1..MAX_DOWNLOAD_BYTES ||
        sha256 == null || !SHA256.matches(sha256) ||
        metadataPurchaseId != expectedPurchaseId ||
        metadataImageId != expectedImageId
    ) {
        return null
    }
    return sha256
}

private const val JPEG_MIME_TYPE = "image/jpeg"
private const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024
private val SHA256 = Regex("^[0-9a-f]{64}$")
