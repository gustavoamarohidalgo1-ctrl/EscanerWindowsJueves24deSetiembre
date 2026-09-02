package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.id.BusinessId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Cursor durable del pull incremental, por negocio de la nube. */
interface SyncCursorRepository {
    /** Última seq aplicada; 0 si nunca se ha hecho pull de ese negocio. */
    suspend fun lastPulledSeq(businessId: BusinessId): Long

    suspend fun saveCursor(businessId: BusinessId, seq: Long, pulledAt: Instant): DomainResult<Unit>

    /** Cursor y fecha del último pull para mostrar en la pantalla de sincronización. */
    fun observeCursor(businessId: BusinessId): Flow<SyncCursor?>
}
