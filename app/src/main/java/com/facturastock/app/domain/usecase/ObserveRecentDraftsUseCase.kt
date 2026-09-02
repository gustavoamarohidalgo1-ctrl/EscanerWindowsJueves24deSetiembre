package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.RecentDraftReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Emite los borradores recientes del negocio activo: los [MAX_RECENT_DRAFTS] más nuevos por
 * `updatedAt` descendente con desempate estable, con el proveedor resuelto por el read-model
 * Room en una sola consulta acotada. Si no hay negocio activo (onboarding pendiente), emite
 * siempre una lista vacía.
 *
 * Un cambio de negocio activo cancela la observación anterior y empieza la del nuevo
 * negocio (semántica de "solo el último").
 */
class ObserveRecentDraftsUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val recentDraftReadRepository: RecentDraftReadRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<RecentDraft>> =
        appConfigurationRepository.observe().flatMapLatest { configuration ->
            configuration.activeBusinessId?.let { businessId ->
                recentDraftReadRepository.observeRecent(businessId, MAX_RECENT_DRAFTS)
            } ?: flowOf(emptyList())
        }

    private companion object {
        const val MAX_RECENT_DRAFTS = 10
    }
}
