package com.facturastock.app.di

import com.facturastock.app.core.coroutines.ApplicationScope
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.RandomUuidGenerator
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.core.time.SystemAppClock
import com.facturastock.app.domain.config.RegionalSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InitialRegionalSettings

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.io)

    @Provides
    @Singleton
    fun provideClock(): AppClock = SystemAppClock()

    @Provides
    @Singleton
    fun provideUuidGenerator(): UuidGenerator = RandomUuidGenerator()

    @Provides
    @Singleton
    @InitialRegionalSettings
    fun provideRegionalSettings(): RegionalSettings = RegionalSettings.peru()
}
