package com.facturastock.app.di

import com.facturastock.app.data.reporting.NoOpObservabilitySink
import com.facturastock.app.data.reporting.ObservabilitySink
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
abstract class ObservabilitySinkModule {
    @Binds
    @Singleton
    abstract fun bindObservabilitySink(
        implementation: NoOpObservabilitySink,
    ): ObservabilitySink
}
