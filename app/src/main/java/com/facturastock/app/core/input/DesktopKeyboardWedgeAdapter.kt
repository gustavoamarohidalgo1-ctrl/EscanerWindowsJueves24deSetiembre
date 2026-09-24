package com.facturastock.app.core.input

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Traduce eventos de teclado de la ventana al contrato puro del ensamblador. En Windows el lector
 * USB es un teclado HID más, así que todos comparten un único `deviceId`; el ensamblador sigue
 * aplicando sus propias reglas de tiempo y longitud.
 *
 * Los caracteres se toman del evento "typed" de AWT (`KeyEventType.Unknown` en Compose), no del
 * KeyDown: un campo de texto enfocado sólo consume el typed y deja burbujear el KeyDown, así que
 * tomar el carácter del KeyDown duplicaba en el lector lo que se escribía en cualquier campo. El
 * typed sólo llega a la ventana cuando ningún campo lo consumió. Enter/Tab siguen llegando como
 * KeyDown y sólo cierran una lectura que ya tenga caracteres pendientes.
 */
class DesktopKeyboardWedgeAdapter {
    private val downTimes = mutableMapOf<Long, Long>()

    fun toKeyboardWedgeEventOrNull(event: KeyEvent): KeyboardWedgeKeyEvent? {
        // `nativeKeyEvent` es el evento interno de Compose; el de AWT (con su hora real) va dentro.
        val eventTime = event.awtEventOrNull?.`when` ?: System.currentTimeMillis()
        if (event.type == KeyEventType.Unknown) return typedCharacterEventOrNull(event, eventTime)
        val action = when (event.type) {
            KeyEventType.KeyDown -> KeyboardWedgeKeyAction.DOWN
            KeyEventType.KeyUp -> KeyboardWedgeKeyAction.UP
            else -> return null
        }
        val keyCode = event.key.keyCode
        val downTime = when (action) {
            KeyboardWedgeKeyAction.DOWN -> downTimes.getOrPut(keyCode) { eventTime }
            KeyboardWedgeKeyAction.UP -> downTimes.remove(keyCode) ?: eventTime
        }
        val terminator = when (event.key) {
            Key.Enter -> KeyboardWedgeTerminator.ENTER
            Key.NumPadEnter -> KeyboardWedgeTerminator.NUMPAD_ENTER
            Key.Tab -> KeyboardWedgeTerminator.TAB
            else -> null
        }
        // El carácter llega después, en el typed; el KeyDown de una tecla imprimible no se enruta.
        if (terminator == null && action != KeyboardWedgeKeyAction.UP) return null
        val repeat = action == KeyboardWedgeKeyAction.DOWN && downTimes[keyCode] != eventTime
        return KeyboardWedgeKeyEvent(
            action = action,
            eventTimeMillis = eventTime,
            downTimeMillis = downTime,
            deviceId = DESKTOP_KEYBOARD_DEVICE_ID,
            keyCode = keyCode.toInt(),
            repeatCount = if (repeat) 1 else 0,
            printableCharacter = null,
            terminator = terminator,
        )
    }

    /** Un typed es una pulsación completa: no tiene UP propio que el ensamblador deba esperar. */
    private fun typedCharacterEventOrNull(event: KeyEvent, eventTime: Long): KeyboardWedgeKeyEvent? {
        val character = event.utf16CodePoint.takeIf { it >= FIRST_PRINTABLE && it != DELETE }?.toChar()
            ?: return null
        return KeyboardWedgeKeyEvent(
            action = KeyboardWedgeKeyAction.DOWN,
            eventTimeMillis = eventTime,
            downTimeMillis = eventTime,
            deviceId = DESKTOP_KEYBOARD_DEVICE_ID,
            keyCode = TYPED_KEY_CODE,
            printableCharacter = character,
            isFromCharacterBatch = true,
        )
    }

    fun reset() {
        downTimes.clear()
    }

    private companion object {
        const val DESKTOP_KEYBOARD_DEVICE_ID = 1
        const val FIRST_PRINTABLE = 0x20
        const val DELETE = 0x7f

        /** `VK_UNDEFINED`: AWT no asocia tecla física al typed. */
        const val TYPED_KEY_CODE = 0
    }
}
