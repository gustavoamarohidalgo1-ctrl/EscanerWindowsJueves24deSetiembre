package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.FeatureArea
import com.facturastock.app.domain.model.FeatureSnapshot
import com.facturastock.app.domain.repository.FeatureRepository

class LoadFeatureUseCase(
    private val repository: FeatureRepository,
) {
    suspend operator fun invoke(area: FeatureArea): FeatureSnapshot =
        repository.load(area)
}
