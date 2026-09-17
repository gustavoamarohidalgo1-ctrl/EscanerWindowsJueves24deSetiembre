package com.facturastock.app.core.input

import android.view.InputDevice
import android.view.KeyEvent

/** Convierte únicamente eventos de un teclado físico en el contrato puro del ensamblador. */
internal fun KeyEvent.toKeyboardWedgeKeyEventOrNull(): KeyboardWedgeKeyEvent? {
    if (!isFromSource(InputDevice.SOURCE_KEYBOARD) || device?.isVirtual != false) return null

    val keyAction = when (action) {
        KeyEvent.ACTION_DOWN -> KeyboardWedgeKeyAction.DOWN
        KeyEvent.ACTION_UP -> KeyboardWedgeKeyAction.UP
        else -> return null
    }
    val wedgeTerminator = keyboardWedgeTerminator(keyCode)
    val wedgeCharacter = if (wedgeTerminator == null) {
        // Un acento muerto lleva COMBINING_ACCENT en el bit alto. Mantenerlo como carácter
        // inválido permite rechazar el código entero, en vez de borrar parte de la lectura.
        unicodeChar.takeIf { it != 0 }?.let { (it and 0xffff).toChar() }
    } else {
        null
    }
    if (wedgeCharacter == null && wedgeTerminator == null && action != KeyEvent.ACTION_UP) return null

    return KeyboardWedgeKeyEvent(
        action = keyAction,
        eventTimeMillis = eventTime,
        downTimeMillis = downTime,
        deviceId = deviceId,
        keyCode = keyCode,
        repeatCount = repeatCount,
        printableCharacter = wedgeCharacter,
        terminator = wedgeTerminator,
    )
}

/** Algunos drivers HID entregan texto agrupado, en lugar de un DOWN/UP por carácter. */
@Suppress("DEPRECATION")
internal fun KeyEvent.toKeyboardWedgeEvents(): List<KeyboardWedgeKeyEvent> {
    if (!isFromSource(InputDevice.SOURCE_KEYBOARD) || device?.isVirtual != false) return emptyList()
    if (action != KeyEvent.ACTION_MULTIPLE) return listOfNotNull(toKeyboardWedgeKeyEventOrNull())
    val text = characters
    if (!text.isNullOrEmpty()) {
        return text.mapIndexed { index, character ->
            val terminator = when (character) {
                '\r', '\n' -> KeyboardWedgeTerminator.ENTER
                '\t' -> KeyboardWedgeTerminator.TAB
                else -> null
            }
            KeyboardWedgeKeyEvent(
                action = KeyboardWedgeKeyAction.DOWN,
                eventTimeMillis = eventTime,
                downTimeMillis = downTime,
                deviceId = deviceId,
                keyCode = keyCode,
                printableCharacter = character.takeIf { terminator == null },
                terminator = terminator,
                sourceSequence = index,
                isFromCharacterBatch = true,
            )
        }
    }
    val terminator = keyboardWedgeTerminator(keyCode)
    val character = unicodeChar.takeIf { it != 0 }?.let { (it and 0xffff).toChar() }
    if (character == null && terminator == null) return emptyList()
    val count = if (terminator != null) 1 else repeatCount.coerceIn(1, KeyboardWedgeAssembler.MAX_INPUT_LENGTH + 1)
    return List(count) { index ->
        KeyboardWedgeKeyEvent(
            action = KeyboardWedgeKeyAction.DOWN,
            eventTimeMillis = eventTime,
            downTimeMillis = downTime,
            deviceId = deviceId,
            keyCode = keyCode,
            printableCharacter = character.takeIf { terminator == null },
            terminator = terminator,
            sourceSequence = index,
            isFromCharacterBatch = true,
        )
    }
}

private fun keyboardWedgeTerminator(keyCode: Int): KeyboardWedgeTerminator? = when (keyCode) {
    KeyEvent.KEYCODE_ENTER -> KeyboardWedgeTerminator.ENTER
    KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyboardWedgeTerminator.NUMPAD_ENTER
    KeyEvent.KEYCODE_TAB -> KeyboardWedgeTerminator.TAB
    else -> null
}
