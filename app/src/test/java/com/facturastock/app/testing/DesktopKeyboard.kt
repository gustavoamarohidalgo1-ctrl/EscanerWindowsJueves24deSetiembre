package com.facturastock.app.testing

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Teclado físico de escritorio para tests: envía la misma secuencia AWT que produce Windows
 * (KEY_PRESSED, KEY_TYPED, KEY_RELEASED) a la escena de Compose y, si ningún nodo consume el
 * evento, lo entrega a [onUnconsumed], igual que `ComposeWindow` llama a su `onKeyEvent`.
 *
 * `performKeyInput { pressKey(...) }` no sirve para esto en escritorio: sólo genera KeyDown/KeyUp
 * sin el KEY_TYPED con el que un campo de texto inserta caracteres y con el que el lector físico
 * recibe los suyos.
 */
class DesktopKeyboard(
    private val composeRule: ComposeContentTestRule,
    private val onUnconsumed: (KeyEvent) -> Boolean = { false },
) {
    /** Devuelve `true` si la escena o el manejador de ventana consumieron el evento. */
    fun send(event: AwtKeyEvent): Boolean = composeRule.runOnIdle { dispatch(event) }

    /**
     * En el hilo de UI. Como la escena real, entrega el evento a la capa (raíz) que tiene el foco;
     * sin foco, a la ventana principal. Un diálogo o menú abierto añade otra raíz.
     */
    private fun dispatch(event: AwtKeyEvent): Boolean {
        val composeEvent = event.toComposeKeyEvent()
        val focusedRoot = composeRule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.root
        val root = requireNotNull(
            focusedRoot ?: composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().firstOrNull()?.root,
        ) { "La composición no tiene raíz para recibir teclas" }
        return root.sendKeyEvent(composeEvent) || onUnconsumed(composeEvent)
    }

    /**
     * Escribe [text]. Con [interKeyDelayMillis] = 0 es una ráfaga de lector: todas las teclas se
     * entregan en una sola vuelta del hilo de UI, como llegan a la cola de AWT, sin que un equipo de
     * pruebas cargado las separe más que el umbral de `ScannerBurstDetector`. Con pausas imita a
     * una persona.
     */
    fun type(text: String, interKeyDelayMillis: Long = 0L) {
        if (interKeyDelayMillis <= 0L) {
            val events = text.flatMap { character ->
                keyStrokeEvents(AwtKeyEvent.getExtendedKeyCodeForChar(character.code), character)
            }
            composeRule.runOnIdle { events.forEach(::dispatch) }
            return
        }
        text.forEachIndexed { index, character ->
            if (index > 0) Thread.sleep(interKeyDelayMillis)
            keyStroke(AwtKeyEvent.getExtendedKeyCodeForChar(character.code), character)
        }
    }

    private fun keyStrokeEvents(keyCode: Int, character: Char): List<AwtKeyEvent> = listOf(
        awtKeyEvent(AwtKeyEvent.KEY_PRESSED, keyCode, character),
        awtKeyEvent(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, character),
        awtKeyEvent(AwtKeyEvent.KEY_RELEASED, keyCode, character),
    )

    fun enter() = keyStroke(AwtKeyEvent.VK_ENTER, '\n')

    fun tab() = keyStroke(AwtKeyEvent.VK_TAB, '\t')

    fun escape() = keyStroke(AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.CHAR_UNDEFINED, typed = false)

    /** Una pulsación completa. Devuelve si su KEY_PRESSED fue consumido. */
    fun keyStroke(keyCode: Int, character: Char, typed: Boolean = true): Boolean {
        val pressed = send(awtKeyEvent(AwtKeyEvent.KEY_PRESSED, keyCode, character))
        if (typed && character != AwtKeyEvent.CHAR_UNDEFINED) {
            send(awtKeyEvent(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, character))
        }
        send(awtKeyEvent(AwtKeyEvent.KEY_RELEASED, keyCode, character))
        return pressed
    }

    companion object {
        private val source by lazy { javax.swing.JPanel() }

        fun awtKeyEvent(
            id: Int,
            keyCode: Int,
            character: Char,
            whenMillis: Long = System.currentTimeMillis(),
        ): AwtKeyEvent = AwtKeyEvent(source, id, whenMillis, 0, keyCode, character)
    }
}

// `toComposeEvent` es interna de Compose; es la conversión exacta que usa la ventana real.
private val toComposeEventMethod by lazy {
    Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        .getMethod("toComposeEvent", AwtKeyEvent::class.java)
}

fun AwtKeyEvent.toComposeKeyEvent(): KeyEvent = KeyEvent(requireNotNull(toComposeEventMethod.invoke(null, this)))
