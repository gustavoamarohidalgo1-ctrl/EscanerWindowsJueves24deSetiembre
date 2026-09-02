package com.facturastock.app.data.account

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.settings.AppIdentityMutationCoordinator
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkCasResult
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Une el enlace de control de sesión con la autoridad durable Room.
 *
 * DataStore puede cambiar de cuenta o quedar vacío al cerrar sesión; el destino del libro no.
 * Este coordinador fija primero Room y solo después publica el enlace activo en DataStore.
 * Si esa segunda escritura falla, el binding Room funciona como journal irreversible: reintentar
 * el mismo destino es idempotente y completa DataStore; cualquier destino distinto falla cerrado.
 */
@Singleton
class CloudBusinessLinkBinder @Inject constructor(
    private val store: CloudAccountSettingsStore,
    private val bindings: CloudBusinessBindingRepository,
    private val appClock: AppClock,
    private val coordinator: CloudAccountMutationCoordinator,
    private val appConfiguration: AppConfigurationRepository,
    private val identityCoordinator: AppIdentityMutationCoordinator,
) {
    fun currentLocalBusinessIdentityEpoch(): Long = identityCoordinator.currentEpoch()

    /**
     * Valida la cuenta bajo su exclusión y el negocio local bajo la exclusión de identidad que
     * también usan sus escrituras. El éxito devuelve la revisión durable de esta publicación.
     */
    suspend fun setActive(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
        isCurrentAccount: suspend () -> Boolean = { true },
    ): DomainResult<CloudBusinessLinkReceipt> = coordinator.withLock accountLock@{
        if (!isCurrentAccount()) {
            return@accountLock DomainResult.Failure(AccountError.SessionExpired)
        }
        identityCoordinator.withLock {
            if (!isCurrentLocalBusiness(expectedLocalIdentityEpoch, link)) {
                DomainResult.Failure(AccountError.Conflict)
            } else {
                setActiveLocked(expectedUid, link)
            }
        }
    }

    /**
     * CAS serializado por propietario, enlace y revisión. Evita ABA tanto entre cuentas como
     * entre dos escrituras idénticas de la misma cuenta. Un mismatch no toca Room ni DataStore.
     */
    suspend fun compareAndSet(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
        isCurrentAccount: suspend () -> Boolean = { true },
    ): DomainResult<CloudBusinessLinkCasResult> = coordinator.withLock accountLock@{
        if (!isCurrentAccount()) {
            return@accountLock DomainResult.Success(CloudBusinessLinkCasResult(false, null))
        }
        identityCoordinator.withLock identityLock@{
            if (
                newLink != null &&
                !isCurrentLocalBusiness(expectedLocalIdentityEpoch, newLink)
            ) {
                return@identityLock DomainResult.Success(
                    CloudBusinessLinkCasResult(false, null),
                )
            }
            val current = store.storedLink()
            if (
                current == null ||
                (current.ownerUid != null && current.ownerUid != expectedUid) ||
                current.link != expectedReceipt.link ||
                current.revision != expectedReceipt.revision
            ) {
                return@identityLock DomainResult.Success(
                    CloudBusinessLinkCasResult(false, null),
                )
            }
            if (newLink == null) {
                store.setLink(expectedUid, null)
                return@identityLock DomainResult.Success(
                    CloudBusinessLinkCasResult(true, null),
                )
            }
            when (val result = setActiveLocked(expectedUid, newLink)) {
                is DomainResult.Success -> DomainResult.Success(
                    CloudBusinessLinkCasResult(true, result.value),
                )
                is DomainResult.Failure -> result
            }
        }
    }

    /** Las limpiezas no crean un destino y deben poder compensar una identidad ya obsoleta. */
    private suspend fun isCurrentLocalBusiness(
        expectedEpoch: Long,
        link: CloudBusinessLink,
    ): Boolean = identityCoordinator.isCurrentEpoch(expectedEpoch) &&
        appConfiguration.current().activeBusinessId == link.localBusinessId

    private suspend fun setActiveLocked(
        expectedUid: String,
        link: CloudBusinessLink,
    ): DomainResult<CloudBusinessLinkReceipt> {
        require(expectedUid.isNotBlank()) { "uid requerido" }

        val durableTarget = bindings.targetFor(link.localBusinessId)
        if (durableTarget != null && durableTarget != link.cloudBusinessId) {
            return DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
        }

        // Upgrade v19: DataStore ya podía recordar X antes de que Room tuviera binding. Si la
        // persona elige Y antes del primer worker, se fija X y se rechaza el retarget.
        val remembered = store.storedLink()
            ?.takeIf { it.ownerUid == null || it.ownerUid == expectedUid }
            ?.link
            ?.takeIf { it.localBusinessId == link.localBusinessId }
        if (
            durableTarget == null && remembered != null &&
            remembered.cloudBusinessId != link.cloudBusinessId
        ) {
            val rememberedError = pin(remembered)
            return DomainResult.Failure(
                rememberedError ?: AccountError.CloudBusinessAlreadyBound,
            )
        }

        pin(link)?.let { return DomainResult.Failure(it) }
        val stored = checkNotNull(store.setLink(expectedUid, link)) {
            "una publicación no nula debe emitir recibo"
        }
        return DomainResult.Success(
            CloudBusinessLinkReceipt(stored.link, stored.revision),
        )
    }

    /** `null` significa que el vínculo exacto quedó fijado; otro valor es un fallo cerrado. */
    private suspend fun pin(link: CloudBusinessLink): AccountError? =
        when (
            bindings.bindOnce(
                localBusinessId = link.localBusinessId,
                cloudBusinessId = link.cloudBusinessId,
                boundAt = appClock.now(),
            )
        ) {
            CloudBusinessBindingResult.Bound,
            CloudBusinessBindingResult.AlreadyBound,
            -> null
            CloudBusinessBindingResult.LocalBusinessAlreadyBound,
            CloudBusinessBindingResult.CloudBusinessAlreadyBound,
            -> AccountError.CloudBusinessAlreadyBound
            CloudBusinessBindingResult.LegacyDestinationUnknown ->
                AccountError.LegacySyncDestinationUnknown
            CloudBusinessBindingResult.AmbiguousInventoryLocations ->
                AccountError.Conflict
        }
}
