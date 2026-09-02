package com.facturastock.app.di

import com.facturastock.app.data.reporting.ConsentAwareProductionObservability
import com.facturastock.app.domain.observability.ProductionObservability
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ObservabilityModule {
    @Binds
    @Singleton
    abstract fun bindProductionObservability(
        implementation: ConsentAwareProductionObservability,
    ): ProductionObservability
}
