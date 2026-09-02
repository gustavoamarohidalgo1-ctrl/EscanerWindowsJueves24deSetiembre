package com.facturastock.app.testing

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.ParsedInvoicePublication
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.PersistedParsedInvoice
import com.facturastock.app.domain.repository.PublishParsedInvoiceResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fake de la publicación estructurada. Serializa los intentos y aplica la misma regla de
 * idempotencia que Room: un audit trail idéntico es un no-op y uno distinto es conflicto.
 *
 * Las proyecciones se delegan al fake del agregado únicamente después de validar el estado; de
 * esta forma las pruebas de caso de uso observan cabecera y líneas publicadas, no un almacén
 * paralelo que podría ocultar duplicados.
 */
class FakeParsedInvoiceRepository(
    private val drafts: FakeInvoiceDraftRepository,
) : ParsedInvoiceRepository {
    private val lock = Mutex()
    private val persisted = mutableMapOf<DraftId, PersistedParsedInvoice>()
    private val recordedAttempts = mutableListOf<ParsedInvoicePublication>()
    private val recordedPublications = mutableListOf<ParsedInvoicePublication>()

    val attempts: List<ParsedInvoicePublication>
        get() = recordedAttempts.toList()

    val publications: List<ParsedInvoicePublication>
        get() = recordedPublications.toList()

    override suspend fun publish(
        publication: ParsedInvoicePublication,
    ): PublishParsedInvoiceResult = lock.withLock {
        recordedAttempts += publication
        val audit = publication.parsedInvoice.audit
        val existing = persisted[audit.draftId]
        if (existing != null) {
            return@withLock if (existing.audit == audit) {
                PublishParsedInvoiceResult.ALREADY_PUBLISHED
            } else {
                PublishParsedInvoiceResult.CONFLICT
            }
        }

        val current = drafts.findDraft(audit.draftId)
            ?: return@withLock PublishParsedInvoiceResult.DRAFT_NOT_READY
        if (
            current.status != DraftStatus.OCR_READY ||
            current.activeOcrRunId != null ||
            current.confirmedPurchaseId != null ||
            current.businessId != publication.draft.businessId
        ) {
            return@withLock PublishParsedInvoiceResult.DRAFT_NOT_READY
        }

        check(drafts.updateDraft(publication.draft)) {
            "El borrador desapareció durante la publicación fake"
        }
        drafts.replaceLines(audit.draftId, publication.lines)

        val stored = PersistedParsedInvoice(
            audit = audit,
            parsedAt = publication.parsedAt,
            payloadSha256 = audit.stableTestHash(),
        )
        persisted[audit.draftId] = stored
        recordedPublications += publication
        PublishParsedInvoiceResult.PUBLISHED
    }

    override suspend fun find(draftId: DraftId): PersistedParsedInvoice? =
        lock.withLock { persisted[draftId] }
}

private fun Any.stableTestHash(): String = MessageDigest.getInstance("SHA-256")
    .digest(toString().toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
