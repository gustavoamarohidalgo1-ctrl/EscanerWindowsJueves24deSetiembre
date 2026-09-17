package com.facturastock.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.core.input.toKeyboardWedgeEvents
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.feature.common.LocalScannerInputPermission
import com.facturastock.app.feature.root.AppGateViewModel
import com.facturastock.app.navigation.FacturaStockApp
import com.facturastock.app.navigation.InternalDeepLinks
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign
import com.facturastock.app.ui.theme.FacturaStockTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var appConfigurationRepository: AppConfigurationRepository

    private var internalDeepLinkRequest by mutableStateOf<InternalDeepLinkRequest?>(null)
    private var lastInternalDeepLinkRequestId = 0L
    private var accessState by mutableStateOf(AppAccessState.CHECKING)
    private var appContentEverMounted by mutableStateOf(false)
    private var showLockRecoveryNotice by mutableStateOf(false)
    private var biometricLockConfigured = false
    private var sessionUnlocked = false
    private var authenticationInProgress = false
    private var activityResumed = false
    private var activeBiometricPrompt: BiometricPrompt? = null
    private val appLockRetryGeneration = MutableStateFlow(0L)
    // FacturaStockApp usa el mismo Activity como ViewModelStoreOwner. Obtener esta instancia solo
    // al reintentar coordina ambas rutas de configuración sin crear DraftFlow ni abrir Room.
    private val appGateViewModel: AppGateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = ContextCompat.getColor(this, R.color.window_background_light),
                darkScrim = ContextCompat.getColor(this, R.color.window_background_dark),
            ),
        )
        super.onCreate(savedInstanceState)
        if (
            intent.getBooleanExtra(SUPPRESS_DEFERRED_STARTUP_EXTRA, false) &&
            allowsDeferredStartupHarnessSuppression(BuildConfig.BUILD_TYPE)
        ) {
            (application as FacturaStockApplication)
                .suppressDeferredStartupForHarnessPreparation()
        }
        KeyboardWedgeRouter.reset()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lastInternalDeepLinkRequestId = savedInstanceState?.getLong(
            INTERNAL_DEEP_LINK_LAST_ID_KEY,
        ) ?: 0L
        internalDeepLinkRequest = savedInstanceState
            ?.getString(INTERNAL_DEEP_LINK_URI_KEY)
            ?.let { uri ->
                InternalDeepLinkRequest(
                    uri = uri,
                    requestId = savedInstanceState.getLong(INTERNAL_DEEP_LINK_REQUEST_ID_KEY),
                )
            }
            ?: newInternalDeepLinkRequest(intent.validInternalDeepLinkOrNull())
        observeAppLockConfiguration()
        setContent {
            FacturaStockTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Selectores estables para perfiles/benchmarks; no dependen del idioma.
                        .semantics { testTagsAsResourceId = true },
                ) {
                    if (shouldMountAppContent(accessState, appContentEverMounted)) {
                        val exposedDeepLinkRequest = internalDeepLinkRequest.takeIf {
                            shouldExposeInternalDeepLink(accessState)
                        }
                        // La primera pantalla bloqueada no materializa AppGate, DraftFlow ni Room.
                        // Tras un desbloqueo sí conservamos launchers y back stack bajo el lock.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (accessState == AppAccessState.LOCKED) {
                                        Modifier.clearAndSetSemantics { }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            CompositionLocalProvider(
                                LocalScannerInputPermission provides {
                                    accessState == AppAccessState.UNLOCKED && !showLockRecoveryNotice
                                },
                            ) {
                                FacturaStockApp(
                                    // Un intent recibido mientras la sesión está bloqueada queda
                                    // encolado en MainActivity, pero no navega el árbol oculto.
                                    initialInternalDeepLink = exposedDeepLinkRequest?.uri,
                                    initialInternalDeepLinkRequestId =
                                        exposedDeepLinkRequest?.requestId ?: 0L,
                                )
                            }
                        }
                    }
                    if (
                        accessState == AppAccessState.LOCKED ||
                        accessState == AppAccessState.CONFIGURATION_UNAVAILABLE
                    ) {
                        DeferredStartupAfterRootFrameEffect(
                            application = application as FacturaStockApplication,
                        )
                    }
                    when (accessState) {
                        AppAccessState.CHECKING -> LoadingState(
                            message = getString(R.string.feature_loading_message),
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppAccessState.CONFIGURATION_UNAVAILABLE -> RecoverableError(
                            title = getString(R.string.app_configuration_error_title),
                            message = getString(R.string.app_configuration_error_message),
                            actionLabel = getString(R.string.action_retry),
                            onAction = ::retryAppLockConfiguration,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(FacturaStockDesign.spacing.lg),
                        )

                        AppAccessState.LOCKED -> {
                            BackHandler { /* El bloqueo no navega ni descarta un borrador. */ }
                            AppLockScreen(onUnlock = ::requestUnlock)
                        }

                        AppAccessState.UNLOCKED -> if (showLockRecoveryNotice) {
                            FacturaStockDialog(
                                title = getString(R.string.app_lock_title),
                                message = getString(R.string.app_lock_recovery_message),
                                confirmLabel = getString(R.string.app_lock_recovery_action),
                                dismissLabel = getString(R.string.app_lock_recovery_action),
                                onConfirm = { showLockRecoveryNotice = false },
                                onDismiss = { showLockRecoveryNotice = false },
                            )
                        }
                    }
                }
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // La solicitud nueva ya se conserva con identidad propia y en savedInstanceState. No
        // reemplazar el intent de lanzamiento también permite que ActivityScenario y otros
        // observadores de lifecycle sigan asociando las transiciones a esta misma Activity.
        internalDeepLinkRequest = newInternalDeepLinkRequest(
            intent.validInternalDeepLinkOrNull(),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(INTERNAL_DEEP_LINK_LAST_ID_KEY, lastInternalDeepLinkRequestId)
        internalDeepLinkRequest?.let { request ->
            outState.putString(INTERNAL_DEEP_LINK_URI_KEY, request.uri)
            outState.putLong(INTERNAL_DEEP_LINK_REQUEST_ID_KEY, request.requestId)
        }
        super.onSaveInstanceState(outState)
    }

    private fun newInternalDeepLinkRequest(uri: String?): InternalDeepLinkRequest? {
        uri ?: return null
        lastInternalDeepLinkRequestId += 1L
        return InternalDeepLinkRequest(uri = uri, requestId = lastInternalDeepLinkRequestId)
    }

    // Activity.dispatchKeyEvent is public. Lint inherits @RestrictTo from AndroidX Core's
    // internal ComponentActivity bridge even though FragmentActivity is a public base class.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (accessState == AppAccessState.UNLOCKED && !showLockRecoveryNotice) {
            var consumed = false
            event.toKeyboardWedgeEvents().forEach { wedgeEvent ->
                if (KeyboardWedgeRouter.route(wedgeEvent)) consumed = true
            }
            if (consumed) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        requestUnlock()
    }

    override fun onPause() {
        KeyboardWedgeRouter.reset()
        activityResumed = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (
            accessState == AppAccessState.UNLOCKED &&
            biometricLockConfigured &&
            !authenticationInProgress &&
            !isChangingConfigurations
        ) {
            sessionUnlocked = false
            enterLockedState()
        }
    }

    private fun observeAppLockConfiguration() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                observeAppLockConfigurationResults(
                    repository = appConfigurationRepository,
                    retryGeneration = appLockRetryGeneration,
                ).collect { result ->
                    when (result) {
                        is AppLockConfigurationResult.Available -> {
                            val enabled = result.biometricLockEnabled
                            biometricLockConfigured = enabled
                            if (!enabled || sessionUnlocked) {
                                sessionUnlocked = true
                                appContentEverMounted = true
                                accessState = AppAccessState.UNLOCKED
                                authenticationInProgress = false
                                activeBiometricPrompt?.cancelAuthentication()
                                activeBiometricPrompt = null
                            } else {
                                enterLockedState()
                                // La configuración se observa en STARTED, pero BiometricPrompt
                                // solo puede abrirse con la Activity RESUMED y sin estado guardado.
                                requestUnlock()
                            }
                        }

                        AppLockConfigurationResult.Unavailable ->
                            enterConfigurationUnavailableState()
                    }
                }
            }
        }
    }

    private fun retryAppLockConfiguration() {
        if (accessState != AppAccessState.CONFIGURATION_UNAVAILABLE) return
        accessState = AppAccessState.CHECKING
        // La UI raíz y AppGate observaron el mismo fallo. Reiniciarlas en el mismo gesto evita que
        // el StateFlow de AppGate conserve Unavailable y solicite un segundo toque al remontarse.
        retrySharedConfigurationSources(
            retrySharedObservation = appConfigurationRepository::retryObservation,
            retryAppGate = appGateViewModel::retry,
            retryRootGate = {
                appLockRetryGeneration.update { generation -> generation + 1L }
            },
        )
    }

    private fun requestUnlock() {
        if (
            accessState != AppAccessState.LOCKED ||
            !AppUnlockGate.shouldAuthenticate(
                lockConfigured = biometricLockConfigured,
                sessionUnlocked = sessionUnlocked,
                activityResumed = activityResumed,
                authenticationInProgress = authenticationInProgress,
            ) || supportFragmentManager.isStateSaved
        ) {
            return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            recoverFromUnavailableDeviceLock()
            return
        }
        authenticationInProgress = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    activeBiometricPrompt = null
                    authenticationInProgress = false
                    if (accessState != AppAccessState.LOCKED) return
                    sessionUnlocked = true
                    appContentEverMounted = true
                    accessState = AppAccessState.UNLOCKED
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    activeBiometricPrompt = null
                    authenticationInProgress = false
                    if (accessState != AppAccessState.LOCKED) return
                    sessionUnlocked = false
                    enterLockedState()
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_title))
            .setSubtitle(getString(R.string.app_lock_message))
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(true)
            .build()
        activeBiometricPrompt = prompt
        prompt.authenticate(promptInfo)
    }

    private fun recoverFromUnavailableDeviceLock() {
        authenticationInProgress = false
        biometricLockConfigured = false
        sessionUnlocked = true
        appContentEverMounted = true
        accessState = AppAccessState.UNLOCKED
        KeyboardWedgeRouter.reset()
        showLockRecoveryNotice = true
        lifecycleScope.launch {
            try {
                appConfigurationRepository.updateBiometricLockEnabled(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // La sesión queda accesible; el aviso explica que el dispositivo no puede
                // cumplir el bloqueo. DataStore volverá a intentarse si el usuario lo activa.
            }
        }
    }

    private fun enterLockedState() {
        KeyboardWedgeRouter.reset()
        accessState = AppAccessState.LOCKED
    }

    private fun enterConfigurationUnavailableState() {
        KeyboardWedgeRouter.reset()
        sessionUnlocked = false
        authenticationInProgress = false
        accessState = AppAccessState.CONFIGURATION_UNAVAILABLE
        activeBiometricPrompt?.cancelAuthentication()
        activeBiometricPrompt = null
    }

    private fun Intent.validInternalDeepLinkOrNull(): String? =
        dataString?.takeIf { InternalDeepLinks.resolve(it) != null }
}

private data class InternalDeepLinkRequest(
    val uri: String,
    val requestId: Long,
)

private const val INTERNAL_DEEP_LINK_URI_KEY = "main.internalDeepLink.uri"
private const val INTERNAL_DEEP_LINK_REQUEST_ID_KEY = "main.internalDeepLink.requestId"
private const val INTERNAL_DEEP_LINK_LAST_ID_KEY = "main.internalDeepLink.lastId"
private const val SUPPRESS_DEFERRED_STARTUP_EXTRA =
    "com.facturastock.app.performance.SUPPRESS_DEFERRED_STARTUP"

internal enum class AppAccessState {
    CHECKING,
    CONFIGURATION_UNAVAILABLE,
    LOCKED,
    UNLOCKED,
}

internal fun shouldMountAppContent(
    accessState: AppAccessState,
    contentEverMounted: Boolean,
): Boolean = contentEverMounted && (
    accessState == AppAccessState.LOCKED || accessState == AppAccessState.UNLOCKED
)

/** Los deep links válidos se encolan durante el bloqueo y solo se entregan tras autenticar. */
internal fun shouldExposeInternalDeepLink(accessState: AppAccessState): Boolean =
    accessState == AppAccessState.UNLOCKED

/** Un único gesto de recuperación reinicia a los dos consumidores del upstream compartido. */
internal fun retrySharedConfigurationSources(
    retrySharedObservation: () -> Unit,
    retryAppGate: () -> Unit,
    retryRootGate: () -> Unit,
) {
    retrySharedObservation()
    retryAppGate()
    retryRootGate()
}

@Composable
private fun DeferredStartupAfterRootFrameEffect(application: FacturaStockApplication) {
    LaunchedEffect(application) {
        // El primer pulso ejecuta el efecto tras aplicar la composición; el segundo garantiza que
        // la pantalla de bloqueo/error ya tuvo oportunidad de presentarse antes del trabajo I/O.
        withFrameNanos { }
        withFrameNanos { }
        application.onFirstAppFrameRendered()
    }
}

internal sealed interface AppLockConfigurationResult {
    data class Available(val biometricLockEnabled: Boolean) : AppLockConfigurationResult
    data object Unavailable : AppLockConfigurationResult
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeAppLockConfigurationResults(
    repository: AppConfigurationRepository,
    retryGeneration: Flow<Long>,
): Flow<AppLockConfigurationResult> = retryGeneration.flatMapLatest {
    repository.observe()
        .map { configuration -> configuration.biometricLockEnabled }
        .distinctUntilChanged()
        .map<Boolean, AppLockConfigurationResult>(AppLockConfigurationResult::Available)
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure !is Exception) throw failure
            emit(AppLockConfigurationResult.Unavailable)
        }
}

internal object AppUnlockGate {
    fun shouldAuthenticate(
        lockConfigured: Boolean,
        sessionUnlocked: Boolean,
        activityResumed: Boolean,
        authenticationInProgress: Boolean,
    ): Boolean =
        lockConfigured && !sessionUnlocked && activityResumed && !authenticationInProgress
}

@Composable
fun AppLockScreen(onUnlock: () -> Unit) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                            if (!change.isConsumed) change.consume()
                        }
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(
                    spacing.md,
                    Alignment.CenterVertically,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.app_lock_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.app_lock_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                FacturaStockPrimaryButton(
                    text = androidx.compose.ui.res.stringResource(R.string.app_lock_action),
                    onClick = onUnlock,
                )
            }
        }
    }
}
