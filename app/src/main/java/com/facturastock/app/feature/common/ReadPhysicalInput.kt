package com.facturastock.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Lee el texto parcial del lector físico en su propio ámbito de recomposición.
 *
 * El lector escribe un carácter tras otro. Si la ruta leyera ese estado directamente, cada
 * carácter recompondría la pantalla completa; aquí sólo se recompone [content], que contiene
 * el campo del escáner.
 */
@Composable
internal fun ReadPhysicalInput(
    physicalInput: State<String>,
    content: @Composable (String) -> Unit,
) {
    content(physicalInput.value)
}
