package com.facturastock.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import com.facturastock.app.ui.navigation.BackPressedDispatcher
import com.facturastock.app.ui.navigation.LocalBackPressedDispatcher
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.facturastock.app.core.input.DesktopKeyboardWedge
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.core.platform.LocalAppDirectories
import com.facturastock.app.core.platform.SingleInstanceLock
import com.facturastock.app.di.AppComponent
import com.facturastock.app.di.AppViewModelFactory
import com.facturastock.app.di.DaggerAppComponent
import com.facturastock.app.di.LocalAppViewModelFactory
import com.facturastock.app.di.appViewModel
import com.facturastock.app.feature.common.LocalScannerInputPermission
import com.facturastock.app.feature.root.AppGateViewModel
import com.facturastock.app.navigation.FacturaStockApp
import com.facturastock.app.resources.*
import com.facturastock.app.startup.LocalDesktopStartup
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign
import com.facturastock.app.ui.theme.FacturaStockTheme
import javax.swing.JOptionPane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun main() {
    val directories = AppDirectories.default()
    // Dos procesos sobre la misma base romperían los cerrojos en memoria (cobro, stock).
    val instanceLock = SingleInstanceLock.acquire(directories.root)
    if (instanceLock == null) {
        JOptionPane.showMessageDialog(
            null,
            "FacturaStock ya está abierto en este equipo.",
            "FacturaStock",
            JOptionPane.INFORMATION_MESSAGE,
        )
        return
    }
    val component = DaggerAppComponent.factory().create(directories)
    val viewModelFactory = AppViewModelFactory(component.viewModelComponentFactory())
    application {
        val windowState = rememberWindowState(
            placement = WindowPlacement.Maximized,
            position = WindowPosition(Alignment.Center),
            size = DpSize(1280.dp, 800.dp),
        )
        var accessState by remember { mutableStateOf(AppAccessState.CHECKING) }
        val backPressedDispatcher = remember { BackPressedDispatcher() }
        Window(
            onCloseRequest = {
                instanceLock.release()
                exitApplication()
            },
            title = "FacturaStock",
            icon = painterResource(Res.drawable.app_icon),
            state = windowState,
            // Burbujeo, no preview: un campo de texto enfocado recibe primero las teclas (lo que
            // escribe una persona o el lector sobre el campo); solo las que nadie consume llegan
            // al enrutador del lector, igual que las teclas físicas fuera de un EditText en Android.
            onKeyEvent = { event -> handleWindowKeyEvent(event, accessState, backPressedDispatcher) },
        ) {
            window.minimumSize = java.awt.Dimension(MIN_WIDTH_PX, MIN_HEIGHT_PX)
            CompositionLocalProvider(
                LocalAppViewModelFactory provides viewModelFactory,
                LocalDesktopStartup provides component.startup(),
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
 * Lo que hace la ventana con una tecla que ningún nodo de Compose consumió: se ofrece al lector
 * físico. Separado de `main` para probarlo sin una ventana real.
 *
 * Esc no se atiende aquí: si nadie lo consume, `ComposeSceneMediator` lo entrega (en KeyDown) como
 * Atrás al `NavigationEventDispatcher` de la escena, donde escuchan NavHost, los diálogos, los
 * menús y los `BackHandler` de la app. Atenderlo también aquí producía un segundo Atrás (en KeyUp):
 * un Esc retrocedía dos pantallas o cerraba un diálogo y además la pantalla de debajo.
 */
internal fun handleWindowKeyEvent(
    event: KeyEvent,
    accessState: AppAccessState,
    @Suppress("UNUSED_PARAMETER") backPressedDispatcher: BackPressedDispatcher,
): Boolean {
    // Sin sesión abierta, Esc tampoco debe navegar la app que pudiera seguir montada detrás.
    if (accessState != AppAccessState.UNLOCKED) return event.key == Key.Escape
    val wedgeEvent = DesktopKeyboardWedge.toKeyboardWedgeEventOrNull(event) ?: return false
    return KeyboardWedgeRouter.route(wedgeEvent)
}

@Composable
internal fun AppRoot(
    component: AppComponent,
    accessState: AppAccessState,
    onAccessStateChange: (AppAccessState) -> Unit,
) {
    val configurationRepository = remember(component) { component.appConfigurationRepository() }
    val retryGeneration = remember { MutableStateFlow(0L) }
    val appGateViewModel = appViewModel<AppGateViewModel>()
    val lifecycleOwner = LocalLifecycleOwner.current
    var appContentEverMounted by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        KeyboardWedgeRouter.reset()
        DesktopKeyboardWedge.reset()
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observeAppLockConfigurationResults(
                repository = configurationRepository,
                retryGeneration = retryGeneration,
            ).collect { result ->
                when (result) {
                    // Windows no tiene BiometricPrompt: la versión Android ya no ofrecía activar el
                    // bloqueo, así que la sesión se abre directamente.
                    is AppLockConfigurationResult.Available -> {
                        appContentEverMounted = true
                        onAccessStateChange(AppAccessState.UNLOCKED)
                    }

                    AppLockConfigurationResult.Unavailable -> {
                        KeyboardWedgeRouter.reset()
                        onAccessStateChange(AppAccessState.CONFIGURATION_UNAVAILABLE)
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (shouldMountAppContent(accessState, appContentEverMounted)) {
            CompositionLocalProvider(
                LocalScannerInputPermission provides { accessState == AppAccessState.UNLOCKED },
            ) {
                FacturaStockApp()
            }
        }
        if (accessState == AppAccessState.CONFIGURATION_UNAVAILABLE) {
            DeferredStartupAfterRootFrameEffect()
        }
        when (accessState) {
            AppAccessState.CHECKING -> LoadingState(
                message = stringResource(Res.string.feature_loading_message),
                modifier = Modifier.fillMaxSize(),
            )

            AppAccessState.CONFIGURATION_UNAVAILABLE -> RecoverableError(
                title = stringResource(Res.string.app_configuration_error_title),
                message = stringResource(Res.string.app_configuration_error_message),
                actionLabel = stringResource(Res.string.action_retry),
                onAction = {
                    onAccessStateChange(AppAccessState.CHECKING)
                    retrySharedConfigurationSources(
                        retrySharedObservation = configurationRepository::retryObservation,
                        retryAppGate = appGateViewModel::retry,
                        retryRootGate = { retryGeneration.update { generation -> generation + 1L } },
                    )
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(FacturaStockDesign.spacing.lg),
            )

            AppAccessState.LOCKED,
            AppAccessState.UNLOCKED,
            -> Unit
        }
    }
}

@Composable
private fun DeferredStartupAfterRootFrameEffect() {
    val startup = LocalDesktopStartup.current
    LaunchedEffect(startup) {
        startup ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        startup.onFirstAppFrameRendered()
    }
}

private const val MIN_WIDTH_PX = 900
private const val MIN_HEIGHT_PX = 600
