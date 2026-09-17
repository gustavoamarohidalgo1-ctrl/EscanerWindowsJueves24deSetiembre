package com.facturastock.app.core.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** Un diálogo tiene su propia ventana; tampoco debe recibir el segundo Enter de un lector. */
fun Modifier.suppressScannerTrailingKeys(): Modifier =
    onPreviewKeyEvent { event ->
        var consumed = false
        event.nativeKeyEvent.toKeyboardWedgeEvents().forEach { key ->
            if (KeyboardWedgeRouter.consumeTrailingEvent(key)) consumed = true
        }
        consumed
    }
