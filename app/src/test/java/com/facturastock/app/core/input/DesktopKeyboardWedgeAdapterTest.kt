package com.facturastock.app.core.input

import com.facturastock.app.testing.toComposeKeyEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Versión de escritorio de `AndroidKeyboardWedgeAdapterTest`: ejercita la traducción de eventos
 * AWT reales (KEY_PRESSED/KEY_TYPED/KEY_RELEASED, convertidos con la misma función que usa la
 * ventana) al contrato del ensamblador. Los eventos son sintéticos; no certifican un lector real.
 */
class DesktopKeyboardWedgeAdapterTest {
    private val adapter = DesktopKeyboardWedgeAdapter()
    private val source = JPanel()

    @Test
    fun pressTypedReleasePreserveDigitsLeadingZerosAndCompleteOnEnter() {
        val assembler = KeyboardWedgeAssembler()
        val completed = mutableListOf<String>()
        val start = 1_000L
        "00123".forEachIndexed { index, digit ->
            val time = start + index * 10L
            val keyCode = KeyEvent.VK_0 + digit.digitToInt()
            // El KeyDown de una tecla imprimible no es un carácter del lector: el carácter llega
            // en el typed, que un campo de texto enfocado consume antes que la ventana.
            assertNull(adapter.map(KeyEvent.KEY_PRESSED, keyCode, digit, time))
            val typed = requireNotNull(adapter.map(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, digit, time))
            assertEquals(digit, typed.printableCharacter)
            assertEquals(KeyboardWedgeKeyAction.DOWN, typed.action)
            assertEquals(time, typed.eventTimeMillis)
            assertTrue(typed.isFromCharacterBatch)
            val released = requireNotNull(adapter.map(KeyEvent.KEY_RELEASED, keyCode, digit, time + 1L))
            assertEquals(KeyboardWedgeKeyAction.UP, released.action)
            assertNull(released.printableCharacter)
            listOf(typed, released).forEach { event ->
                val result = assembler.accept(event)
                if (result is KeyboardWedgeAssemblyResult.Completed) completed += result.value
            }
        }
        val enter = requireNotNull(adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', start + 60L))
        assertEquals(KeyboardWedgeTerminator.ENTER, enter.terminator)
        val result = assembler.accept(enter)
        if (result is KeyboardWedgeAssemblyResult.Completed) completed += result.value
        // El typed '\n' que AWT emite tras Enter no es un segundo terminador ni un carácter.
        assertNull(adapter.map(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\n', start + 60L))

        assertEquals(listOf("00123"), completed)
    }

    @Test
    fun controlCharactersAndDeleteAreNotScannerCharacters() {
        listOf('\u0008', '\u001B', '\u007F').forEach { character ->
            assertNull(adapter.map(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, character))
        }
        // Una tecla sin carácter (flecha) no participa en una lectura.
        assertNull(adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED))
    }

    @Test
    fun nonAsciiTypedCharacterIsForwardedSoTheWholeFrameIsRejected() {
        val assembler = KeyboardWedgeAssembler()
        val results = "0Á9".map { character ->
            assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, character)))
        } + assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n')))

        // El adaptador no borra caracteres para fabricar otro código ("09").
        assertEquals(KeyboardWedgeAssemblyResult.Rejected(KeyboardWedgeReadError.INVALID_CHARACTER), results.last())
    }

    @Test
    fun enterNumpadEnterAndTabKeyDownsAreTerminators() {
        listOf(
            Triple(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_STANDARD, KeyboardWedgeTerminator.ENTER),
            Triple(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_NUMPAD, KeyboardWedgeTerminator.NUMPAD_ENTER),
            Triple(KeyEvent.VK_TAB, KeyEvent.KEY_LOCATION_STANDARD, KeyboardWedgeTerminator.TAB),
        ).forEach { (keyCode, location, terminator) ->
            val event = KeyEvent(source, KeyEvent.KEY_PRESSED, 5_000L, 0, keyCode, KeyEvent.CHAR_UNDEFINED, location)
            val mapped = requireNotNull(DesktopKeyboardWedgeAdapter().toKeyboardWedgeEventOrNull(event.toComposeKeyEvent()))
            assertEquals(terminator, mapped.terminator)
            assertEquals(KeyboardWedgeKeyAction.DOWN, mapped.action)
            assertEquals(0, mapped.repeatCount)
        }
    }

    @Test
    fun repeatedDigitsTypedAtTheSameTimestampAreAllKept() {
        val assembler = KeyboardWedgeAssembler()
        val timestamp = 7_000L
        val completed = "0011".map { digit ->
            assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, digit, timestamp)))
        }.plus(assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', timestamp))))
            .filterIsInstance<KeyboardWedgeAssemblyResult.Completed>()
            .map(KeyboardWedgeAssemblyResult.Completed::value)

        assertEquals(listOf("0011"), completed)
    }

    @Test
    fun heldKeyAutoRepeatKeepsEveryTypedCharacter() {
        val assembler = KeyboardWedgeAssembler()
        val first = 9_000L
        repeat(2) { index ->
            val time = first + index * 30L
            val pressed = adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_7, '7', time)
            assertNull(pressed)
            assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '7', time)))
        }
        assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_RELEASED, KeyEvent.VK_7, '7', first + 61L)))
        val result = assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', first + 62L)))

        assertEquals(KeyboardWedgeAssemblyResult.Completed("77"), result)
    }

    @Test
    fun autoRepeatedTerminatorClosesTheReadOnlyOnce() {
        val assembler = KeyboardWedgeAssembler()
        assembler.accept(requireNotNull(adapter.map(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '5', 100L)))
        val first = requireNotNull(adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', 110L))
        val repeated = requireNotNull(adapter.map(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', 140L))
        assertEquals(0, first.repeatCount)
        assertEquals(1, repeated.repeatCount)
        assertEquals(first.downTimeMillis, repeated.downTimeMillis)

        assertEquals(KeyboardWedgeAssemblyResult.Completed("5"), assembler.accept(first))
        assertEquals(KeyboardWedgeAssemblyResult.Consumed, assembler.accept(repeated))
        val released = requireNotNull(adapter.map(KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER, '\n', 150L))
        assertTrue(assembler.consumeTrailingEvent(released))
    }

    @Test
    fun releaseWithoutPressStillMapsToAnUpEvent() {
        val released = adapter.map(KeyEvent.KEY_RELEASED, KeyEvent.VK_A, 'a', 20L)
        assertNotNull(released)
        assertEquals(KeyboardWedgeKeyAction.UP, released?.action)
        assertEquals(20L, released?.downTimeMillis)
    }

    private fun DesktopKeyboardWedgeAdapter.map(
        id: Int,
        keyCode: Int,
        character: Char,
        whenMillis: Long = System.currentTimeMillis(),
    ): KeyboardWedgeKeyEvent? =
        toKeyboardWedgeEventOrNull(KeyEvent(source, id, whenMillis, 0, keyCode, character).toComposeKeyEvent())
}
