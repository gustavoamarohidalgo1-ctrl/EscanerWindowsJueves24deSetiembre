package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.HomeDashboardRead
import com.facturastock.app.domain.model.id.BusinessId
import kotlinx.coroutines.flow.Flow

/** Read-model acotado de Inicio; evita cargar historiales completos para dibujar el panel. */
interface HomeDashboardReadRepository {
    fun observe(businessId: BusinessId): Flow<HomeDashboardRead>
}
