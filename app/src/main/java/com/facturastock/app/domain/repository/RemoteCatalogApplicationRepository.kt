package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.id.BusinessId
import java.time.Instant

/** Resultado de aplicar hechos cloud ya persistidos; las filas conflictivas quedan en Room. */
data class CatalogApplicationOutcome(
    val applied: Int,
    val conflicts: Int,
    val blockedByEarlierConflict: Int,
) {
    init {
        require(applied >= 0 && conflicts >= 0 && blockedByEarlierConflict >= 0)
    }
}

enum class CatalogOutboxConflictResolution { APPLY_REMOTE, KEEP_LOCAL }

/**
 * Aplica a los catálogos locales únicamente desde el espejo Room. Cada cambio y su proyección
 * APPLIED/CONFLICT se confirman en una transacción, por lo que un cierre nunca deja medio cambio.
 */
interface RemoteCatalogApplicationRepository {
    suspend fun applyPending(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        appliedAt: Instant,
    ): DomainResult<CatalogApplicationOutcome>

    suspend fun resolveOutboxConflict(
        activeBusinessId: BusinessId,
        operation: OutboxOperationView,
        resolution: CatalogOutboxConflictResolution,
        actorId: String,
        resolvedAt: Instant,
    ): DomainResult<Boolean>
}
