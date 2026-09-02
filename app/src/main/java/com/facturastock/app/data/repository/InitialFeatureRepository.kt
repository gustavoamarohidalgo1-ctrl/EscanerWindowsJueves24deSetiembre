package com.facturastock.app.data.repository

import com.facturastock.app.domain.model.FeatureArea
import com.facturastock.app.domain.model.FeatureSnapshot
import com.facturastock.app.domain.repository.FeatureRepository

/** Fake de compatibilidad para pruebas; no está enlazado al grafo de producción. */
class InitialFeatureRepository : FeatureRepository {
    override suspend fun load(area: FeatureArea): FeatureSnapshot =
        FeatureSnapshot(area = area)
}
