package com.facturastock.app.di

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.BindsInstance
import dagger.Module
import dagger.Subcomponent
import javax.inject.Provider

/**
 * Alcance de creación de un ViewModel: igual que Hilt, recibe el [SavedStateHandle] del destino
 * de navegación y resuelve el resto de dependencias desde [AppComponent].
 */
@Subcomponent(modules = [ViewModelBindingsModule::class])
interface ViewModelComponent {
    fun viewModels(): Map<Class<*>, @JvmSuppressWildcards Provider<ViewModel>>

    @Subcomponent.Factory
    interface Factory {
        fun create(@BindsInstance savedStateHandle: SavedStateHandle): ViewModelComponent
    }
}

@Module(subcomponents = [ViewModelComponent::class])
interface ViewModelSubcomponentModule
