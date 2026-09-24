package com.facturastock.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.resources.*
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

// Estado de acceso compartido con la ventana principal (antes vivía en MainActivity).


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
                .windowInsetsPadding(WindowInsets.safeDrawing)
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
                    text = stringResource(Res.string.app_lock_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(Res.string.app_lock_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                FacturaStockPrimaryButton(
                    text = stringResource(Res.string.app_lock_action),
                    onClick = onUnlock,
                )
            }
        }
    }
}

internal fun allowsDeferredStartupHarnessSuppression(buildType: String): Boolean =
    buildType == "benchmark" || buildType == "profile"

/** Ejecuta el bloque exactamente una vez aunque varias composiciones señalen el mismo proceso. */
internal class DeferredStartupRunOnce {
    private val started = AtomicBoolean(false)

    fun run(block: () -> Unit): Boolean {
        if (!started.compareAndSet(false, true)) return false
        block()
        return true
    }
}
