package com.facturastock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun FacturaStockScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    navigationRail: @Composable () -> Unit = {},
    useNavigationRail: Boolean = false,
    contentMaxWidth: Dp? = null,
    contentPaneTitle: String? = null,
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val resolvedContentMaxWidth = contentMaxWidth ?: spacing.contentMaxWidth

    Row(modifier = modifier.fillMaxSize()) {
        if (useNavigationRail) {
            navigationRail()
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = {
                snackbarHostState?.let { SnackbarHost(hostState = it) }
            },
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = FabPosition.End,
        ) { contentPadding ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = resolvedContentMaxWidth)
                        .fillMaxWidth()
                        .semantics {
                            isTraversalGroup = true
                            contentPaneTitle?.let { paneTitle = it }
                        },
                ) {
                    content(contentPadding)
                }
            }
        }
    }
}
