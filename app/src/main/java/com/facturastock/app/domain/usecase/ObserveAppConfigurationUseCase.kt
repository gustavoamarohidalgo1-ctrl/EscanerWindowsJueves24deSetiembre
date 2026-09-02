package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.repository.AppConfigurationRepository
import kotlinx.coroutines.flow.Flow

/** Expone la configuración global de la app como flujo observable. */
class ObserveAppConfigurationUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
) {
    operator fun invoke(): Flow<AppConfiguration> = appConfigurationRepository.observe()
}
