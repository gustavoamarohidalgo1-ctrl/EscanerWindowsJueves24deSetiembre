package com.facturastock.app.core.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Carpetas privadas visibles para la UI (miniaturas de páginas de factura). */
val LocalAppDirectories = staticCompositionLocalOf { AppDirectories.default() }
