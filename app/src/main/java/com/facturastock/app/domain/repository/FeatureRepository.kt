package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.FeatureArea
import com.facturastock.app.domain.model.FeatureSnapshot

interface FeatureRepository {
    suspend fun load(area: FeatureArea): FeatureSnapshot
}
