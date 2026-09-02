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
    val wedgeTerminator = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> KeyboardWedgeTerminator.ENTER
        KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyboardWedgeTerminator.NUMPAD_ENTER
        KeyEvent.KEYCODE_TAB -> KeyboardWedgeTerminator.TAB
        else -> null
    }
    val wedgeCharacter = if (wedgeTerminator == null) {
        unicodeChar.takeIf { it in 1..Char.MAX_VALUE.code }?.toChar()
    } else {
        null
    }
    if (wedgeCharacter == null && wedgeTerminator == null) return null

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
