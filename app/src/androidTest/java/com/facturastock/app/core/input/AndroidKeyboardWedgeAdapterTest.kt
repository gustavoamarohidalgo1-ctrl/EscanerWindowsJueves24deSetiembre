package com.facturastock.app.core.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ejercita KeyEvent y el mapa de teclado Android sin abrir la app ni acceder a datos comerciales.
 * Los eventos son sintéticos; esta prueba no certifica un modelo de lector USB o Bluetooth.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeyboardWedgeAdapterTest {
    private lateinit var keyboard: InputDevice

    @Before
    fun findAndroidKeyboard() {
        val available =
            InputDevice
                .getDeviceIds()
                .map(InputDevice::getDevice)
                .filterNotNull()
                .firstOrNull { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_KEYBOARD) }
        assumeTrue("La prueba necesita un dispositivo Android de teclado no virtual", available != null)
        keyboard = requireNotNull(available)
    }

    @Test
    fun downAndUpPreserveDigitsLeadingZerosAndDeviceIdentity() {
        val assembler = KeyboardWedgeAssembler()
        val completed = mutableListOf<String>()
        val start = SystemClock.uptimeMillis()
        "00123".forEachIndexed { index, digit ->
            val downTime = start + index * 10L
            val keyCode = KeyEvent.KEYCODE_0 + digit.digitToInt()
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                val events =
                    event(
                        action = action,
                        keyCode = keyCode,
                        downTime = downTime,
                        eventTime = downTime + if (action == KeyEvent.ACTION_UP) 1L else 0L,
                    ).toKeyboardWedgeEvents()
                val mapped = events.single()
                assertEquals(digit, mapped.printableCharacter)
                assertEquals(keyboard.id, mapped.deviceId)
                assertEquals(downTime, mapped.downTimeMillis)
                assertFalse(mapped.isFromCharacterBatch)
                assertEquals(
                    if (action == KeyEvent.ACTION_DOWN) KeyboardWedgeKeyAction.DOWN else KeyboardWedgeKeyAction.UP,
                    mapped.action,
                )
                val result = assembler.accept(mapped)
                if (result is KeyboardWedgeAssemblyResult.Completed) completed += result.value
            }
        }
        event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, start + 60L)
            .toKeyboardWedgeEvents()
            .forEach { mapped ->
                val result = assembler.accept(mapped)
                if (result is KeyboardWedgeAssemblyResult.Completed) completed += result.value
            }

        assertEquals(listOf("00123"), completed)
    }

    @Test
    fun virtualKeyboardAndNonKeyboardSourcesAreNotPhysicalScans() {
        assertTrue(
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD)
                .toKeyboardWedgeEvents()
                .isEmpty(),
        )
        assertTrue(
            KeyEvent(SystemClock.uptimeMillis(), "00123\r", KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
                .toKeyboardWedgeEvents()
                .isEmpty(),
        )
        assertTrue(
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, source = InputDevice.SOURCE_TOUCHSCREEN)
                .toKeyboardWedgeEvents()
                .isEmpty(),
        )
    }

    @Test
    fun multipleTextPreservesUnicodeAndGroupSeparatorForWholeFrameValidation() {
        val text = "0Á\u001D9\uD83D\uDE00"
        val events = KeyEvent(SystemClock.uptimeMillis(), text, keyboard.id, 0).toKeyboardWedgeEvents()

        // El adaptador no debe borrar separadores ni unidades UTF-16 para fabricar otro código.
        assertEquals(text.toList(), events.mapNotNull(KeyboardWedgeKeyEvent::printableCharacter))
        assertEquals(events.size, events.map(KeyboardWedgeKeyEvent::sourceSequence).distinct().size)
        assertTrue(events.all { it.action == KeyboardWedgeKeyAction.DOWN && it.deviceId == keyboard.id })
        assertTrue(events.all(KeyboardWedgeKeyEvent::isFromCharacterBatch))
    }

    @Test
    fun multipleTextMapsCrLfAndTabToTerminators() {
        listOf(
            '\r' to KeyboardWedgeTerminator.ENTER,
            '\n' to KeyboardWedgeTerminator.ENTER,
            '\t' to KeyboardWedgeTerminator.TAB,
        ).forEach { (character, terminator) ->
            val events =
                KeyEvent(SystemClock.uptimeMillis(), character.toString(), keyboard.id, 0)
                    .toKeyboardWedgeEvents()
            assertEquals(terminator, events.single().terminator)
        }
    }

    @Test
    fun multipleTextRepeatedDigitsAndCrLfDeliverExactlyOneCompleteScan() {
        val assembler = KeyboardWedgeAssembler()
        val completed =
            KeyEvent(SystemClock.uptimeMillis(), "0011\r\n", keyboard.id, 0)
                .toKeyboardWedgeEvents()
                .map(assembler::accept)
                .filterIsInstance<KeyboardWedgeAssemblyResult.Completed>()
                .map(KeyboardWedgeAssemblyResult.Completed::value)

        assertEquals(listOf("0011"), completed)
    }

    @Test
    fun consecutiveSingleCharacterTextBatchesAtSameTimestampPreserveRepeatedDigits() {
        val assembler = KeyboardWedgeAssembler()
        val timestamp = SystemClock.uptimeMillis()
        val completed =
            "0011\r"
                .flatMap { digit ->
                    KeyEvent(timestamp, digit.toString(), keyboard.id, 0).toKeyboardWedgeEvents()
                }.map(assembler::accept)
                .filterIsInstance<KeyboardWedgeAssemblyResult.Completed>()
                .map(KeyboardWedgeAssemblyResult.Completed::value)

        assertEquals(listOf("0011"), completed)
    }

    @Test
    fun consecutiveSingleKeyRepeatBatchesAtSameTimestampPreserveRepeatedDigits() {
        val assembler = KeyboardWedgeAssembler()
        val timestamp = SystemClock.uptimeMillis()
        repeat(2) {
            event(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_7, timestamp, repeatCount = 1)
                .toKeyboardWedgeEvents()
                .forEach(assembler::accept)
        }
        val result =
            event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, timestamp + 1L)
                .toKeyboardWedgeEvents()
                .map(assembler::accept)
                .single()

        assertEquals(KeyboardWedgeAssemblyResult.Completed("77"), result)
    }

    @Test
    fun multipleRepeatedTerminatorProducesOneClosingEvent() {
        listOf(
            KeyEvent.KEYCODE_ENTER to KeyboardWedgeTerminator.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to KeyboardWedgeTerminator.NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB to KeyboardWedgeTerminator.TAB,
        ).forEach { (keyCode, terminator) ->
            val events =
                event(KeyEvent.ACTION_MULTIPLE, keyCode, repeatCount = 5)
                    .toKeyboardWedgeEvents()

            assertEquals(1, events.size)
            assertEquals(KeyboardWedgeKeyAction.DOWN, events.single().action)
            assertEquals(0, events.single().repeatCount)
            assertEquals(terminator, events.single().terminator)
        }
    }

    private fun event(
        action: Int,
        keyCode: Int,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = downTime,
        repeatCount: Int = 0,
        deviceId: Int = keyboard.id,
        source: Int = InputDevice.SOURCE_KEYBOARD,
    ) = KeyEvent(downTime, eventTime, action, keyCode, repeatCount, 0, deviceId, 0, 0, source)
}
