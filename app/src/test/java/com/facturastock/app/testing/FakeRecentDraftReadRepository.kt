package com.facturastock.app.testing

import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.RecentDraftReadRepository
import com.facturastock.app.domain.repository.SupplierRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Adaptador del read-model para pruebas que ya usan los repositorios fake de borradores y
 * proveedores. Replica el orden, aislamiento y límite del query Room sin acoplar el caso de uso
 * a dos colecciones completas.
 */
class FakeRecentDraftReadRepository(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val supplierRepository: SupplierRepository,
) : RecentDraftReadRepository {
    private val requests = mutableListOf<Pair<BusinessId, Int>>()

    val observedRequests: List<Pair<BusinessId, Int>>
        get() = requests.toList()

    override fun observeRecent(
        businessId: BusinessId,
        limit: Int,
    ): Flow<List<RecentDraft>> {
        require(limit in 1..50) { "limit debe estar entre 1 y 50" }
        requests += businessId to limit
        return combine(
            invoiceDraftRepository.observeDrafts(businessId, status = null),
            supplierRepository.observeForBusiness(businessId),
        ) { drafts, suppliers ->
            val namesById = suppliers.associate { it.supplierId to it.legalName }
            drafts.asSequence()
                .filter {
                    it.confirmedPurchaseId == null && it.status != DraftStatus.COMMITTED
                }
                .sortedWith(RECENT_DRAFT_ORDER)
                .take(limit)
                .map { draft ->
                    RecentDraft(
                        draft = draft,
                        supplierName = draft.supplierId?.let(namesById::get),
                    )
                }
                .toList()
        }
    }

    private companion object {
        val RECENT_DRAFT_ORDER = compareByDescending<InvoiceDraft> { it.updatedAt }
            .thenByDescending { it.draftId.value }
    }
}
