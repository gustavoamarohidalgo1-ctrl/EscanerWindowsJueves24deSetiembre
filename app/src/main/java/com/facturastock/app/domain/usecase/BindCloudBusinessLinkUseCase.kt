package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult

/**
 * Única frontera para aceptar un link de UI: fija primero L→X en Room. DataStore nunca puede
 * anunciar un tenant que la outbox inmutable rechazaría después en segundo plano.
 */
class BindCloudBusinessLinkUseCase(
    private val bindings: CloudBusinessBindingRepository,
    private val clock: AppClock,
) {
    suspend fun fixedTargetFor(localBusinessId: BusinessId): BusinessId? =
        bindings.targetFor(localBusinessId)

    suspend operator fun invoke(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
    ): DomainResult<Unit> = when (
        bindings.bindOnce(localBusinessId, cloudBusinessId, clock.now())
    ) {
        CloudBusinessBindingResult.Bound,
        CloudBusinessBindingResult.AlreadyBound,
        -> DomainResult.Success(Unit)

        CloudBusinessBindingResult.LocalBusinessAlreadyBound,
        CloudBusinessBindingResult.CloudBusinessAlreadyBound,
        -> DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)

        CloudBusinessBindingResult.LegacyDestinationUnknown ->
            DomainResult.Failure(AccountError.LegacySyncDestinationUnknown)

        CloudBusinessBindingResult.AmbiguousInventoryLocations ->
            DomainResult.Failure(AccountError.Conflict)
    }
}
