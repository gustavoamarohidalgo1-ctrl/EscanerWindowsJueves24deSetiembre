package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.SyncCursorRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Binding productivo del flavor local: sin libro remoto no hay cursor que persistir. La seq es
 * siempre 0, no hay cursor observable y guardar es un éxito vacío: el puerto de lectura ya
 * declara `Unavailable` antes de que nadie consulte el cursor.
 */
@Singleton
class NoOpSyncCursorRepository @Inject constructor() : SyncCursorRepository {
    override suspend fun lastPulledSeq(businessId: BusinessId): Long = 0L

    override suspend fun saveCursor(
        businessId: BusinessId,
        seq: Long,
        pulledAt: Instant,
    ): DomainResult<Unit> = DomainResult.Success(Unit)

    override fun observeCursor(businessId: BusinessId): Flow<SyncCursor?> = flowOf(null)
}
