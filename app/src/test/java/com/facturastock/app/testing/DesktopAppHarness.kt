package com.facturastock.app.testing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.facturastock.app.AppAccessState
import com.facturastock.app.AppRoot
import com.facturastock.app.core.input.DesktopKeyboardWedge
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.core.platform.LocalAppDirectories
import com.facturastock.app.di.AppViewModelFactory
import com.facturastock.app.di.LocalAppViewModelFactory
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.handleWindowKeyEvent
import com.facturastock.app.ui.navigation.BackPressedDispatcher
import com.facturastock.app.ui.navigation.LocalBackPressedDispatcher
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.io.File
import java.nio.file.Files
import org.junit.rules.ExternalResource

/**
 * Sustituto de escritorio de `ActivityScenario<MainActivity>` + Hilt: grafo real
 * ([TestAppComponent]) sobre un directorio temporal y el mismo árbol que monta la ventana de
 * `Main.kt` (`AppRoot` con sus CompositionLocals, tema y manejo de teclas de ventana).
 *
 * Uso: declarar como `@get:Rule(order = 0)`, fijar [TestAppConfigurationState] y sembrar datos
 * con [component] antes de [setAppContent]. [keyboard] envía teclas físicas; lo que ningún nodo
 * consume llega a `handleWindowKeyEvent` (lector físico) y un Esc sin consumir se entrega como
 * Atrás a la escena, en el mismo orden que la ventana real.
 */
class DesktopAppHarness(
    private val initialConfiguration: AppConfiguration? = completedGateConfiguration(),
) : ExternalResource() {
    lateinit var root: File
        private set
    lateinit var directories: AppDirectories
        private set
    lateinit var component: TestAppComponent
        private set
    val backPressedDispatcher = BackPressedDispatcher()

    private var accessState by mutableStateOf(AppAccessState.CHECKING)
    private var generation by mutableIntStateOf(0)
    private var viewModelStore = ViewModelStore()
    private val sceneBack = SceneSystemBack()

    override fun before() {
        KeyboardWedgeRouter.deactivate()
        DesktopKeyboardWedge.reset()
        initialConfiguration?.let { TestAppConfigurationState.current.value = it }
        root = Files.createTempDirectory("facturastock-ui-test").toFile()
        directories = AppDirectories(root)
        component = DaggerTestAppComponent.factory().create(directories)
    }

    override fun after() {
        KeyboardWedgeRouter.deactivate()
        DesktopKeyboardWedge.reset()
        viewModelStore.clear()
        runCatching { component.database().close() }
        root.deleteRecursively()
    }

    /** Monta la aplicación como lo hace la ventana principal. */
    fun setAppContent(composeRule: ComposeContentTestRule) {
        val factory = AppViewModelFactory(component.viewModelComponentFactory())
        composeRule.setContent {
            sceneBack.capture()
            key(generation) {
                val storeOwner = remember {
                    object : ViewModelStoreOwner {
                        override val viewModelStore: ViewModelStore = this@DesktopAppHarness.viewModelStore
                    }
                }
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides storeOwner,
                    LocalAppViewModelFactory provides factory,
                    LocalBackPressedDispatcher provides backPressedDispatcher,
                    LocalAppDirectories provides directories,
                ) {
                    FacturaStockTheme {
                        AppRoot(
                            component = component,
                            accessState = accessState,
                            onAccessStateChange = { accessState = it },
                        )
                    }
                }
            }
        }
    }

    /**
     * Equivalente de escritorio a cerrar y volver a abrir la ventana en el mismo proceso: se
     * descarta la composición y los ViewModels; persiste lo guardado en Room/configuración.
     */
    fun restartApp(composeRule: ComposeContentTestRule) {
        composeRule.runOnIdle {
            viewModelStore.clear()
            viewModelStore = ViewModelStore()
            accessState = AppAccessState.CHECKING
            generation += 1
        }
        composeRule.waitForIdle()
    }

    /**
     * Mismo orden que `ComposeSceneMediator.onKeyEvent` en la ventana real: escena, luego
     * `onKeyEvent` de la ventana (`handleWindowKeyEvent`) y, si nadie lo consumió, un Esc (KeyDown)
     * se entrega como Atrás al `NavigationEventDispatcher` de la escena (diálogos, menús, NavHost).
     */
    fun keyboard(composeRule: ComposeContentTestRule): DesktopKeyboard =
        DesktopKeyboard(composeRule) { event ->
            handleWindowKeyEvent(event, accessState, backPressedDispatcher) ||
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    sceneBack.dispatchBackNow()
                    true
                } else {
                    false
                }
        }
}
