package com.facturastock.app.feature.common

import com.facturastock.app.resources.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign

/** Keeps feature routes consistent while their initial repository snapshot is loading. */
@Composable
fun FeatureLoadContent(
    isLoading: Boolean,
    hasContent: Boolean,
    hasFailure: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            hasFailure && !hasContent -> RecoverableError(
                title = stringResource(Res.string.feature_load_error_title),
                message = stringResource(Res.string.feature_load_error_message),
                actionLabel = stringResource(Res.string.action_retry),
                onAction = onRetry,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(FacturaStockDesign.spacing.lg),
            )

            isLoading && !hasContent -> LoadingState(
                message = stringResource(Res.string.feature_loading_message),
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                if (hasFailure) {
                    RecoverableError(
                        title = stringResource(Res.string.feature_stale_error_title),
                        message = stringResource(Res.string.feature_stale_error_message),
                        actionLabel = stringResource(Res.string.action_retry),
                        onAction = onRetry,
                        modifier = Modifier.padding(
                            horizontal = FacturaStockDesign.spacing.lg,
                            vertical = FacturaStockDesign.spacing.sm,
                        ),
                    )
                } else if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Box(modifier = Modifier.weight(1f)) {
                    content()
                }
            }
        }
    }
}
