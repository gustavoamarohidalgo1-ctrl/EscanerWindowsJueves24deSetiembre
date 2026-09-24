package com.facturastock.app.di

import com.facturastock.app.data.reporting.ConsentAwareProductionObservability
import com.facturastock.app.domain.observability.ProductionObservability
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
abstract class ObservabilityModule {
    @Binds
    @Singleton
    abstract fun bindProductionObservability(
        implementation: ConsentAwareProductionObservability,
    ): ProductionObservability
}
