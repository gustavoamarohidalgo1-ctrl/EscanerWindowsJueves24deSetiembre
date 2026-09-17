package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.InvoiceMatchingIssue
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.InvoiceMatchingCommitRepository

sealed interface ConfirmInvoiceMatchingResult {
    data class Applied(
        val updatedCount: Int,
    ) : ConfirmInvoiceMatchingResult

    data class AlreadyApplied(
        val updatedCount: Int,
    ) : ConfirmInvoiceMatchingResult

    data class ReviewRequired(
        val issues: Map<Int, Set<InvoiceMatchingIssue>>,
    ) : ConfirmInvoiceMatchingResult

    data object NoActiveBusiness : ConfirmInvoiceMatchingResult

    data object CloudBound : ConfirmInvoiceMatchingResult

    data object UnresolvedLines : ConfirmInvoiceMatchingResult

    data object NothingToApply : ConfirmInvoiceMatchingResult

    data object MissingLocation : ConfirmInvoiceMatchingResult

    data object DraftChanged : ConfirmInvoiceMatchingResult

    /** Un ingreso de la versión anterior difiere de la revisión: no se vuelve a sumar. */
    data object LegacyConflict : ConfirmInvoiceMatchingResult
}

class ConfirmInvoiceMatchingUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val confirmationRepository: InvoiceMatchingCommitRepository,
) {
    suspend fun appliedCount(draftId: DraftId): Int? {
        val businessId = appConfigurationRepository.current().activeBusinessId ?: return null
        return confirmationRepository.findAppliedCount(businessId, draftId)
    }

    suspend operator fun invoke(
        draftId: DraftId,
        items: List<ScannedItemMatch>,
    ): ConfirmInvoiceMatchingResult {
        val config = appConfigurationRepository.current()
        val businessId = config.activeBusinessId ?: return ConfirmInvoiceMatchingResult.NoActiveBusiness
        return confirmationRepository.confirm(businessId, config.currency, draftId, items)
    }
}
