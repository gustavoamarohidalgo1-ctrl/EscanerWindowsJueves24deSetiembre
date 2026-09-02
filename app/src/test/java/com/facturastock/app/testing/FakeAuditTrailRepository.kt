package com.facturastock.app.testing

import com.facturastock.app.domain.repository.AuditEventWrite
import com.facturastock.app.domain.repository.AuditTrailRepository

/**
 * Fake local de la bitácora: conserva los eventos en memoria para las aserciones y admite una
 * excepción programada de un solo uso (típicamente una `StorageException`). Nunca toca disco.
 */
class FakeAuditTrailRepository : AuditTrailRepository {

    /** Eventos registrados, en orden de llegada. */
    val events = mutableListOf<AuditEventWrite>()

    /** Excepción que lanzará el próximo [record]; se consume una vez. */
    var nextException: Throwable? = null

    override suspend fun record(event: AuditEventWrite) {
        val failure = nextException
        if (failure != null) {
            nextException = null
            throw failure
        }
        events += event
    }
}
