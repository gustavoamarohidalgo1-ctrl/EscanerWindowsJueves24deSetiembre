package com.facturastock.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler as ComposeUiBackHandler

/**
 * Despachador de "Atrás" para escritorio con la semántica de `OnBackPressedDispatcher`: el
 * callback habilitado registrado más recientemente atiende el evento; si ninguno lo hace, se usa
 * el respaldo (volver en la navegación). Lo dispara la flecha de la barra superior.
 *
 * La tecla Esc no pasa por aquí: la ventana la entrega como Atrás al `NavigationEventDispatcher`
 * de la escena, que es el mismo que usan NavHost, `Dialog` y los menús; [BackHandler] se registra
 * también allí para que un solo Esc lo atienda un solo destinatario.
 */
class BackPressedDispatcher {
    private val callbacks = mutableListOf<BackCallback>()

    internal fun add(callback: BackCallback) {
        callbacks += callback
    }

    internal fun remove(callback: BackCallback) {
        callbacks -= callback
    }

    fun hasEnabledCallbacks(): Boolean = callbacks.any { it.enabled }

    /** Respaldo cuando ninguna pantalla atiende "Atrás" (el NavHost retrocede). */
    var fallback: (() -> Unit)? = null

    /** Equivalente al botón Atrás del sistema: tecla Esc. */
    fun onBackPressed() {
        if (!dispatch()) fallback?.invoke()
    }

    /** Devuelve `true` si algún [BackHandler] atendió el evento. */
    fun dispatch(): Boolean {
        val callback = callbacks.lastOrNull { it.enabled } ?: return false
        callback.onBack()
        return true
    }
}

internal class BackCallback(var enabled: Boolean, var onBack: () -> Unit)

val LocalBackPressedDispatcher = staticCompositionLocalOf<BackPressedDispatcher?> { null }

/** Misma firma que `androidx.activity.compose.BackHandler`. */
@Suppress("DEPRECATION") // Es la misma API que usa NavHost 2.9 en escritorio para su propio Atrás.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    // Esc: el Atrás de la escena respeta el orden de registro frente al de NavHost (que se registra
    // antes que las pantallas) y deja que un diálogo abierto lo reciba primero, como en Android.
    ComposeUiBackHandler(enabled = enabled) { currentOnBack() }
    val dispatcher = LocalBackPressedDispatcher.current ?: return
    val callback = remember { BackCallback(enabled) { currentOnBack() } }
    SideEffect { callback.enabled = enabled }
    DisposableEffect(dispatcher) {
        dispatcher.add(callback)
        onDispose { dispatcher.remove(callback) }
    }
}

private operator fun <T> androidx.compose.runtime.State<T>.getValue(thisRef: Any?, property: Any?): T = value
