package com.facturastock.app.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * "Atrás" del sistema para tests de escritorio (sustituye a `GLOBAL_ACTION_BACK` de UiAutomation).
 *
 * En la ventana real, un Esc que nadie consume entra como Atrás al `NavigationEventDispatcher` de
 * la escena (`ComposeSceneMediator` → `BackNavigationEventInput`); ahí escuchan `Dialog` y
 * `Popup` para `dismissOnBackPress`. La escena de `createComposeRule` no tiene esa entrada de
 * teclado, así que se entrega el mismo evento con una `DirectNavigationEventInput` sobre el
 * dispatcher de la escena.
 *
 * `androidx.navigationevent` llega al runtime como dependencia transitiva de Compose UI pero no
 * está en el classpath de compilación de los tests, por eso se usa por reflexión.
 *
 * Uso: llamar [capture] dentro de `setContent` y [pressBack] desde el test.
 */
class SceneSystemBack {
    private var dispatcher: Any? = null

    @Composable
    fun capture() {
        val localClass = Class.forName("androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner")
        val local = localClass.getField("INSTANCE").get(null)
        val composerClass = Class.forName("androidx.compose.runtime.Composer")
        val owner = localClass
            .getMethod("getCurrent", composerClass, Int::class.javaPrimitiveType)
            .invoke(local, currentComposer, 0)
        dispatcher = owner?.let {
            Class.forName("androidx.navigationevent.NavigationEventDispatcherOwner")
                .getMethod("getNavigationEventDispatcher")
                .invoke(it)
        }
    }

    fun pressBack(composeRule: ComposeContentTestRule) {
        composeRule.runOnIdle { dispatchBackNow() }
        composeRule.waitForIdle()
    }

    /**
     * Entrega el Atrás en el hilo actual (debe ser el de UI). Es lo que hace
     * `ComposeSceneMediator` con un Esc (KeyDown) que ni la escena ni la ventana consumieron.
     */
    fun dispatchBackNow() {
        val target = checkNotNull(dispatcher) {
            "La escena no expone NavigationEventDispatcherOwner (¿se llamó capture()?)"
        }
        val inputBase = Class.forName("androidx.navigationevent.NavigationEventInput")
        val input = Class.forName("androidx.navigationevent.DirectNavigationEventInput")
            .getConstructor()
            .newInstance()
        target.javaClass.getMethod("addInput", inputBase).invoke(target, input)
        try {
            input.javaClass.getMethod("backCompleted").invoke(input)
        } finally {
            target.javaClass.getMethod("removeInput", inputBase).invoke(target, input)
        }
    }
}
