package com.facturastock.app.testing

import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.ProductionObservability

/** Fake de dominio: conserva la señal previa a redacción para verificar cada call site. */
class RecordingProductionObservability : ProductionObservability {
    data class Record(
        val event: OperationalAuditEvent,
        val failure: Throwable?,
    )

    private val mutableRecords = mutableListOf<Record>()
    private val mutableConsentUpdates = mutableListOf<Boolean>()

    val records: List<Record>
        get() = mutableRecords.toList()

    val consentUpdates: List<Boolean>
        get() = mutableConsentUpdates.toList()

    override suspend fun record(event: OperationalAuditEvent, failure: Throwable?) {
        mutableRecords += Record(event, failure)
    }

    override suspend fun updateConsent(enabled: Boolean) {
        mutableConsentUpdates += enabled
    }
}
