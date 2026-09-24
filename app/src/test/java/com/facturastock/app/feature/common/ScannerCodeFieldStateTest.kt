package com.facturastock.app.feature.common

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reglas del campo del lector en escritorio, con reloj controlado. */
class ScannerCodeFieldStateTest {
    private var now = 10_000L
    private val codes = mutableListOf<String>()
    private val queries = mutableListOf<String>()
    private var physicalClears = 0

    private fun field(search: Boolean = false, allowed: () -> Boolean = { true }) =
        ScannerCodeFieldState(clockMillis = { now }).apply {
            captureAllowed = allowed
            onCode = { codes += it }
            onClearPhysicalInput = { physicalClears += 1 }
            onSearchQueryChange = if (search) {
                { queries += it }
            } else {
                null
            }
            setSearchEnabled(search)
            setCaptureEnabled(true)
        }

    private fun ScannerCodeFieldState.typeAppending(text: String, gapMillis: Long) {
        text.forEach { character ->
            now += gapMillis
            onValueChange(TextFieldValue(value.text + character, TextRange(value.text.length + 1)))
        }
    }

    @Test
    fun burstThenEnterSubmitsTheCode() {
        val field = field()
        field.typeAppending("7750001234567", gapMillis = 5L)
        now += 10L
        field.onTerminatorKey()

        assertEquals(listOf("7750001234567"), codes)
        assertEquals("", field.value.text)
        assertFalse(field.invalid)
    }

    @Test
    fun humanTypingThenEnterOutsideSearchModeStillSubmits() {
        val field = field()
        field.typeAppending("00042", gapMillis = 200L)
        now += 500L
        field.onTerminatorKey()

        assertEquals(listOf("00042"), codes)
    }

    @Test
    fun burstThenEnterInSearchModeSubmitsAndClearsTheQuery() {
        val field = field(search = true)
        field.typeAppending("7750001234567", gapMillis = 5L)
        now += 20L
        field.onTerminatorKey()

        assertEquals(listOf("7750001234567"), codes)
        assertEquals("", queries.last())
        assertEquals("", field.value.text)
    }

    @Test
    fun humanTypingThenEnterInSearchModeRunsTheSearchInsteadOfSubmitting() {
        val field = field(search = true)
        field.typeAppending("arroz", gapMillis = 150L)
        now += 300L
        field.onTerminatorKey()

        assertTrue(codes.isEmpty())
        assertEquals("arroz", queries.last())
        assertEquals("arroz", field.value.text)
    }

    @Test
    fun slowEnterAfterAFastBurstIsTreatedAsHumanInSearchMode() {
        val field = field(search = true)
        field.typeAppending("12345", gapMillis = 5L)
        now += ScannerBurstDetector.SCANNER_MAX_TERMINATOR_DELAY_MILLIS + 1L
        field.onTerminatorKey()

        assertTrue(codes.isEmpty())
        assertEquals("12345", queries.last())
    }

    @Test
    fun oneSlowGapInsideTheBurstMakesItHumanTyping() {
        val field = field(search = true)
        field.typeAppending("123", gapMillis = 5L)
        field.typeAppending("4", gapMillis = ScannerBurstDetector.SCANNER_MAX_INTER_KEY_GAP_MILLIS + 1L)
        field.typeAppending("56", gapMillis = 5L)
        now += 5L
        field.onTerminatorKey()

        assertTrue(codes.isEmpty())
        assertEquals("123456", queries.last())
    }

    @Test
    fun pastedTextIsNotABurst() {
        val field = field(search = true)
        now += 5L
        field.onValueChange(TextFieldValue("7750001234567"))
        now += 5L
        field.onTerminatorKey()

        assertTrue(codes.isEmpty())
        assertEquals("7750001234567", queries.last())
    }

    @Test
    fun tabAfterABurstSubmitsButHumanTabMovesFocus() {
        val scanned = field()
        scanned.typeAppending("0099512300775", gapMillis = 4L)
        now += 5L
        assertTrue(scanned.onTabKey())
        assertEquals(listOf("0099512300775"), codes)

        codes.clear()
        val typed = field()
        typed.typeAppending("0099512300775", gapMillis = 120L)
        now += 5L
        assertFalse(typed.onTabKey())
        assertTrue(codes.isEmpty())
        assertEquals("0099512300775", typed.value.text)
    }

    @Test
    fun tabWithEmptyFieldOrTooShortBurstDoesNotSubmit() {
        val field = field()
        assertFalse(field.onTabKey())
        field.typeAppending("12", gapMillis = 3L)
        assertFalse(field.onTabKey())
        assertTrue(codes.isEmpty())
    }

    @Test
    fun embeddedTerminatorSubmitsImmediately() {
        val field = field()
        field.onValueChange(TextFieldValue("00042\r"))

        assertEquals(listOf("00042"), codes)
        assertEquals("", field.value.text)
    }

    @Test
    fun invalidCodeKeepsTextAndFlagsErrorUntilNextEdit() {
        val field = field()
        field.onValueChange(TextFieldValue("001\r\n002\r\n"))

        assertTrue(field.invalid)
        assertTrue(codes.isEmpty())
        field.reset()
        assertFalse(field.invalid)
        assertEquals("", field.value.text)
        field.onValueChange(TextFieldValue("00042\r"))
        assertEquals(listOf("00042"), codes)
    }

    @Test
    fun loneTerminatorIsDiscardedWithoutAnError() {
        val field = field()
        field.onValueChange(TextFieldValue("\r\n"))
        assertFalse(field.invalid)
        assertEquals("", field.value.text)
        assertTrue(codes.isEmpty())
    }

    @Test
    fun revokedCaptureRejectsEditsAndSubmits() {
        var allowed = true
        val field = field(allowed = { allowed })
        field.typeAppending("001", gapMillis = 5L)
        allowed = false
        field.onValueChange(TextFieldValue("00123"))
        field.submit()

        assertTrue(codes.isEmpty())
        assertEquals("", field.value.text)
    }

    @Test
    fun disablingCaptureClearsContentAndError() {
        val field = field()
        field.onValueChange(TextFieldValue("001\r\n002\r\n"))
        assertTrue(field.invalid)
        field.setCaptureEnabled(false)

        assertEquals("", field.value.text)
        assertFalse(field.invalid)
    }

    @Test
    fun physicalScannerPreviewIsShownAndNotOverwrittenBySearchSync() {
        val field = field(search = true)
        field.syncSearchQuery("arroz")
        assertEquals("arroz", field.value.text)

        field.updatePhysicalInput("775")
        assertEquals("775", field.value.text)
        field.syncSearchQuery("otra")
        assertEquals("775", field.value.text)
        // Con la trama física en pantalla, Enter/Buscar no lanza una búsqueda con sus dígitos.
        field.handleSearchAction()
        assertTrue(queries.none { it == "775" })
    }

    @Test
    fun editingTheFieldClearsThePhysicalFrame() {
        val field = field()
        field.updatePhysicalInput("12")
        field.onValueChange(TextFieldValue("123"))

        assertEquals(1, physicalClears)
        // El vaciado que provoca ese clear no borra lo que la persona escribió.
        field.updatePhysicalInput("")
        assertEquals("123", field.value.text)
    }

    @Test
    fun burstIsMeasuredWithKeyPressTimesEvenWhenTheUiThreadProcessesThemLate() {
        val field = field(search = true)
        val pressedAt = 50_000L
        "7750001234567".forEachIndexed { index, character ->
            // El hilo de UI llega 120 ms tarde a cada tecla, pero el lector las pulsó cada 3 ms.
            now += 120L
            field.onKeyEventTime(pressedAt + index * 3L)
            field.onValueChange(TextFieldValue(field.value.text + character))
        }
        now += 500L
        field.onKeyEventTime(pressedAt + 13 * 3L + 5L)
        field.onTerminatorKey()

        assertEquals(listOf("7750001234567"), codes)
    }

    @Test
    fun humanKeyPressTimesStayHumanEvenIfProcessedInABatch() {
        val field = field(search = true)
        "arroz".forEachIndexed { index, character ->
            field.onKeyEventTime(80_000L + index * 150L)
            field.onValueChange(TextFieldValue(field.value.text + character))
        }
        field.onKeyEventTime(80_000L + 5 * 150L)
        field.onTerminatorKey()

        assertTrue(codes.isEmpty())
        assertEquals("arroz", queries.last())
    }

    @Test
    fun aKeyTimeIsUsedOnceSoALaterPasteFallsBackToTheClock() {
        val field = field(search = true)
        field.onKeyEventTime(1L)
        field.onValueChange(TextFieldValue("1"))
        now += 5L
        // Sin tecla: pegar un texto no reutiliza la hora de la tecla anterior.
        field.onValueChange(TextFieldValue("12345"))
        now += 5L
        field.onTerminatorKey()

        assertTrue(codes.isEmpty())
        assertEquals("12345", queries.last())
    }

    @Test
    fun onlyAWholeUnconfirmedBurstCountsAsAPendingScannerRead() {
        val scanned = field()
        scanned.typeAppending("00990011", gapMillis = 4L)
        assertTrue(scanned.holdsUnconfirmedScan())
        now += 5_000L
        // Pasado el tiempo sigue siendo una ráfaga sin sufijo (la limpieza la decide el campo).
        assertTrue(scanned.holdsUnconfirmedScan())

        val typed = field()
        typed.typeAppending("arroz", gapMillis = 150L)
        assertFalse(typed.holdsUnconfirmedScan())
        typed.reset()
        assertFalse(typed.holdsUnconfirmedScan())
    }

    @Test
    fun humanSearchTypingPublishesEveryQuery() {
        val field = field(search = true)
        field.typeAppending("ab", gapMillis = 200L)

        assertEquals(listOf("a", "ab"), queries)
    }

    @Test
    fun burstDetectorRequiresAppendOnlyFastInputOfMinimumLength() {
        val detector = ScannerBurstDetector()
        detector.onTextChanged("", "1", 0L)
        detector.onTextChanged("1", "12", 10L)
        assertFalse(detector.looksLikeScanner("12", 20L))
        detector.onTextChanged("12", "123", 20L)
        assertTrue(detector.looksLikeScanner("123", 30L))
        assertFalse(detector.looksLikeScanner("123", 20L + ScannerBurstDetector.SCANNER_MAX_TERMINATOR_DELAY_MILLIS + 1L))

        // Borrar rompe la ráfaga aunque sea rápido.
        detector.onTextChanged("123", "12", 25L)
        detector.onTextChanged("12", "124", 26L)
        assertFalse(detector.looksLikeScanner("124", 27L))

        detector.reset()
        assertFalse(detector.looksLikeScanner("", 0L))
    }
}
