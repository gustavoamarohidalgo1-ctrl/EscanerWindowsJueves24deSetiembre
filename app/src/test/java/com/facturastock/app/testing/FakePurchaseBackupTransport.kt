package com.facturastock.app.testing

import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import java.util.Collections

/**
 * Transporte con guion: `onSend` decide cada resultado (acuse válido, 5xx, permanente,
 * conflicto, excepción) sin abrir sockets. Por defecto confirma con el eco correcto de la
 * clave idempotente. `sent` es sincronizada para las pruebas de concurrencia.
 */
class FakePurchaseBackupTransport(
    override var configured: Boolean = true,
    private val connectivity: FakeFirebaseConnectivity = FakeFirebaseConnectivity(),
) : PurchaseBackupTransport {
    val sent: MutableList<BackupEnvelope> = Collections.synchronizedList(mutableListOf())

    var onSend: suspend (BackupEnvelope) -> BackupTransportResult = { envelope ->
        BackupTransportResult.Acknowledged(
            receiptId = "receipt-${envelope.operationId}",
            echoedIdempotencyKey = envelope.idempotencyKey,
        )
    }

    override suspend fun send(envelope: BackupEnvelope): BackupTransportResult {
        sent += envelope
        if (!configured) return BackupTransportResult.Unavailable
        if (!connectivity.isConnected) {
            return BackupTransportResult.TransientFailure("NETWORK_UNAVAILABLE")
        }
        return onSend(envelope)
    }
}
