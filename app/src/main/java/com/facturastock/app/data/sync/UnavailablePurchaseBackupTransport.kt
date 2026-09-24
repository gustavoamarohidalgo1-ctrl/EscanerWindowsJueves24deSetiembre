package com.facturastock.app.data.sync

import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binding productivo del flavor local: sin backend, declara el transporte no disponible para
 * que la cola permanezca honestamente en PENDING_SYNC y el programador no encole trabajo.
 * Nunca marca nada como respaldado.
 */
@Singleton
class UnavailablePurchaseBackupTransport @Inject constructor() : PurchaseBackupTransport {
    override val configured: Boolean = false

    override suspend fun send(envelope: BackupEnvelope): BackupTransportResult =
        BackupTransportResult.Unavailable
}
