package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.dao.AuditEventDao
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.repository.AuditEventWrite
import com.facturastock.app.domain.repository.AuditTrailRepository
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Bitácora durable sobre Room. El evento queda protegido por los guards append-only y de
 * grafo de `audit_events`: una escritura que no encaja con el libro es rechazada por SQLite,
 * no solo por Kotlin. El payload viaja como JSON determinista (claves ordenadas).
 */
class RoomAuditTrailRepository @Inject constructor(
    private val auditEvents: AuditEventDao,
    private val dispatchers: DispatcherProvider,
) : AuditTrailRepository {

    override suspend fun record(event: AuditEventWrite) = withContext(dispatchers.io) {
        storageCatching {
            auditEvents.insert(
                AuditEventEntity(
                    auditEventId = event.auditEventId,
                    businessId = event.businessId.value,
                    purchaseId = event.purchaseId?.value,
                    eventType = event.eventType.name,
                    entityType = event.entityType,
                    entityId = event.entityId,
                    payload = event.payload.toAuditPayloadJson(event.eventType),
                    occurredAt = event.occurredAt.toEpochMilli(),
                ),
            )
        }
    }
}
