package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import kotlinx.coroutines.flow.Flow

/** Observa la instantánea preparada exclusivamente desde Room. */
class ObservePreparedPurchaseUseCase(
    private val repository: PreparedPurchaseRepository,
) {
    operator fun invoke(draftId: DraftId): Flow<PreparedPurchase?> = repository.observe(draftId)
}
