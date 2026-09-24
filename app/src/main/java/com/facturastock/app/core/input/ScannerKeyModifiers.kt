package com.facturastock.app.core.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** Adaptador compartido por la ventana principal y los diálogos, con un único estado DOWN/UP. */
val DesktopKeyboardWedge = DesktopKeyboardWedgeAdapter()

/** Un diálogo tiene su propia ventana; tampoco debe recibir el segundo Enter de un lector. */
fun Modifier.suppressScannerTrailingKeys(): Modifier =
    onPreviewKeyEvent { event ->
        val key = DesktopKeyboardWedge.toKeyboardWedgeEventOrNull(event) ?: return@onPreviewKeyEvent false
        KeyboardWedgeRouter.consumeTrailingEvent(key)
    }
