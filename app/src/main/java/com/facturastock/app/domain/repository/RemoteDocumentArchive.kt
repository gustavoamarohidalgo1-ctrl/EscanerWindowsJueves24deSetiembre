package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId

/** Referencia tipada al JPEG derivado respaldado; nunca contiene una ruta local. */
data class RemoteDocumentReference(
    val businessId: BusinessId,
    val purchaseId: PurchaseId,
    val imageId: ImageId,
)

sealed interface RemoteDocumentDownloadResult {
    data class Downloaded(val jpeg: ByteArray) : RemoteDocumentDownloadResult
    /** Consentimiento/configuración revocados antes o durante la lectura; no se entregan bytes. */
    data object NotEligible : RemoteDocumentDownloadResult
    data object NotFound : RemoteDocumentDownloadResult
    data object AccessDenied : RemoteDocumentDownloadResult
    data object IntegrityRejected : RemoteDocumentDownloadResult
    data object Unavailable : RemoteDocumentDownloadResult
}

/**
 * Puerto de solo lectura del archivo documental remoto. Las escrituras no se exponen al
 * cliente: pasan exclusivamente por el callable que valida y usa Admin Storage.
 */
interface RemoteDocumentArchive {
    val configured: Boolean

    suspend fun download(reference: RemoteDocumentReference): RemoteDocumentDownloadResult
}
