package com.facturastock.app.feature.common

import androidx.compose.runtime.staticCompositionLocalOf

/** Consulted at event time so an open input connection cannot bypass the app lock. */
val LocalScannerInputPermission = staticCompositionLocalOf<() -> Boolean> { { true } }
