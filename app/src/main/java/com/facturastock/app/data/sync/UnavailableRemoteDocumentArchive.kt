package com.facturastock.app.data.sync

import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDocumentDownloadResult
import com.facturastock.app.domain.repository.RemoteDocumentReference
import javax.inject.Inject

class UnavailableRemoteDocumentArchive @Inject constructor() : RemoteDocumentArchive {
    override val configured: Boolean = false

    override suspend fun download(
        reference: RemoteDocumentReference,
    ): RemoteDocumentDownloadResult = RemoteDocumentDownloadResult.Unavailable
}
