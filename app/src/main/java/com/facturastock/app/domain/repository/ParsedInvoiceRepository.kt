package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.normalization.ParsedInvoice
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import java.time.Instant

enum class PublishParsedInvoiceResult {
    PUBLISHED,
    ALREADY_PUBLISHED,
    STALE_SNAPSHOT,
    DRAFT_NOT_READY,
    CONFLICT,
}

/** Proyección editable que debe confirmarse en la misma transacción que el audit trail. */
data class ParsedInvoicePublication(
    val parsedInvoice: ParsedInvoice,
    val draft: InvoiceDraft,
    val lines: List<InvoiceLine>,
    val parsedAt: Instant,
) {
    init {
        require(draft.draftId == parsedInvoice.draftId) {
            "La proyección de cabecera pertenece a otro borrador"
        }
        require(lines.all { line ->
            line.draftId == draft.draftId && line.businessId == draft.businessId
        }) {
            "Las líneas proyectadas deben pertenecer al mismo borrador y negocio"
        }
        require(lines.map(InvoiceLine::position) == lines.indices.toList()) {
            "Las líneas proyectadas deben tener posiciones correlativas"
        }
    }
}

data class PersistedParsedInvoice(
    val audit: ParsedInvoiceAudit,
    val parsedAt: Instant,
    val payloadSha256: String,
)

/** Puerto de publicación atómica del resultado estructurado y su proyección editable. */
interface ParsedInvoiceRepository {
    suspend fun publish(publication: ParsedInvoicePublication): PublishParsedInvoiceResult

    suspend fun find(draftId: DraftId): PersistedParsedInvoice?
}
