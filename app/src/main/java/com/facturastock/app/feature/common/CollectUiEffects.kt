package com.facturastock.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Collects non-replayed UDF effects while the current UI is at least [Lifecycle.State.STARTED].
 *
 * Updating [onEffect] does not restart collection, while changing the source flow cancels the old
 * collector before starting the new one. Pausing collection while the destination is stopped keeps
 * background/back-stack entries from consuming navigation or snackbar effects; the buffered UDF
 * channel retains them until the destination becomes active again.
 */
@Composable
fun <E : UiEffect> CollectUiEffects(
    effects: Flow<E>,
    onEffect: (E) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEffect by rememberUpdatedState(onEffect)

    LaunchedEffect(effects, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effects.collect { effect ->
                currentOnEffect(effect)
            }
        }
    }
}
