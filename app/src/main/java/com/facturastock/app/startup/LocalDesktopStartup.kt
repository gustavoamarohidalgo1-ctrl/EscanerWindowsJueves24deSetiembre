package com.facturastock.app.startup

import androidx.compose.runtime.staticCompositionLocalOf

/** Arranque diferido del proceso; nulo en pruebas de navegación sin grafo de dependencias. */
val LocalDesktopStartup = staticCompositionLocalOf<DesktopStartup?> { null }
