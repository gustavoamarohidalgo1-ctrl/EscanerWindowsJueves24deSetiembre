package com.facturastock.app.di

import com.facturastock.app.data.reporting.FirebaseObservabilitySink
import com.facturastock.app.data.reporting.ObservabilitySink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ObservabilitySinkModule {
    @Binds
    @Singleton
    abstract fun bindObservabilitySink(
        implementation: FirebaseObservabilitySink,
    ): ObservabilitySink
}
