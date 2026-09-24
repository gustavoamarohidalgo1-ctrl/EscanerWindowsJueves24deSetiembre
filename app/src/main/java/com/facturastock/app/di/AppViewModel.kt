package com.facturastock.app.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.reflect.KClass

/** Fábrica que crea cualquier ViewModel registrado en [ViewModelBindingsModule]. */
class AppViewModelFactory(
    private val viewModelComponentFactory: ViewModelComponent.Factory,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        val handle = try {
            extras.createSavedStateHandle()
        } catch (_: IllegalArgumentException) {
            SavedStateHandle()
        }
        val provider = viewModelComponentFactory.create(handle).viewModels()[modelClass.java]
            ?: error("ViewModel no registrado: ${modelClass.qualifiedName}")
        @Suppress("UNCHECKED_CAST")
        return provider.get() as T
    }
}

val LocalAppViewModelFactory = staticCompositionLocalOf<ViewModelProvider.Factory> {
    error("AppViewModelFactory no está disponible en esta composición")
}

/** Equivalente de escritorio de `hiltViewModel()`: mismo alcance (destino o ventana). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null): VM =
    viewModel(modelClass = VM::class, key = key, factory = LocalAppViewModelFactory.current)
