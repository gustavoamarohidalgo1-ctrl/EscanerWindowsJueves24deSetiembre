package com.facturastock.app.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.facturastock.app.feature.common.ScannerCodeInputTestTags
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import com.facturastock.app.AppAccessState
import com.facturastock.app.handleWindowKeyEvent
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.DesktopKeyboard
import com.facturastock.app.testing.toComposeKeyEvent
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Apoyos de escritorio comunes a los recorridos con lector: sustituyen las consultas al
 * `EditText` de la Activity (`findViewWithTag`, `hasFocus`, `text`) por la semántica del campo
 * Compose de [ScannerCodeInputTestTags.FIELD].
 */
internal fun ComposeContentTestRule.scannerFieldText(): String =
    onNodeWithTag(ScannerCodeInputTestTags.FIELD)
        .fetchSemanticsNode()
        .config[SemanticsProperties.EditableText]
        .text

/** El campo del lector está habilitado y tiene el foco del teclado (recibe la ráfaga). */
internal fun ComposeContentTestRule.scannerFieldIsReady(): Boolean =
    onAllNodesWithTag(ScannerCodeInputTestTags.FIELD).fetchSemanticsNodes().size == 1 &&
        runCatching {
            onNodeWithTag(ScannerCodeInputTestTags.FIELD).assertIsEnabled().assertIsFocused()
        }.isSuccess

internal fun ComposeContentTestRule.waitForFocusedScannerField(timeoutMillis: Long = 15_000L) {
    waitUntil(timeoutMillis) { scannerFieldIsReady() }
}

/** Sufijos que un lector USB puede enviar al final de la lectura. */
internal enum class ScanTerminator { ENTER, TAB, NUMPAD_ENTER }

private val awtSource by lazy { javax.swing.JPanel() }

/**
 * Teclado de la ventana para los recorridos. Igual que [DesktopKeyboard], pero entrega cada
 * evento a la capa (raíz) que tiene el foco, como hace la escena real: una ventana emergente
 * no enfocable (menú, aviso) abierta al mismo tiempo no recibe las teclas del lector.
 */
internal class JourneyKeyboard(
    private val composeRule: ComposeContentTestRule,
    private val onUnconsumed: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
) {
    fun send(event: AwtKeyEvent): Boolean = composeRule.runOnIdle { dispatch(event) }

    /** En el hilo de UI: la capa con el foco o, si no hay foco, la ventana principal. */
    private fun dispatch(event: AwtKeyEvent): Boolean {
        val composeEvent = event.toComposeKeyEvent()
        val focusedRoot = composeRule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.root
        val root = focusedRoot ?: requireNotNull(
            composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().firstOrNull()?.root,
        ) { "La composición no tiene raíz para recibir teclas" }
        return root.sendKeyEvent(composeEvent) || onUnconsumed(composeEvent)
    }

    fun type(text: String, interKeyDelayMillis: Long = 0L) {
        text.forEachIndexed { index, character ->
            if (index > 0 && interKeyDelayMillis > 0L) Thread.sleep(interKeyDelayMillis)
            keyStroke(AwtKeyEvent.getExtendedKeyCodeForChar(character.code), character)
        }
    }

    fun enter() = keyStroke(AwtKeyEvent.VK_ENTER, '\n')

    fun tab() = keyStroke(AwtKeyEvent.VK_TAB, '\t')

    fun numPadEnter() {
        keyStroke(AwtKeyEvent.VK_ENTER, '\n', location = AwtKeyEvent.KEY_LOCATION_NUMPAD)
    }

    fun press(terminator: ScanTerminator) {
        when (terminator) {
            ScanTerminator.ENTER -> enter()
            ScanTerminator.TAB -> tab()
            ScanTerminator.NUMPAD_ENTER -> numPadEnter()
        }
    }

    /**
     * Lectura del lector USB: la ráfaga de caracteres y su sufijo. Como el helper Android, los
     * sufijos Enter se envían dobles (CR/LF); Tab se envía una vez porque un segundo Tab sobre el
     * campo vacío es navegación normal del foco.
     */
    fun scan(value: String, terminator: ScanTerminator?) {
        val suffix = when (terminator) {
            null -> emptyList()
            ScanTerminator.TAB -> listOf(terminator)
            else -> listOf(terminator, terminator)
        }
        burst(value, suffix)
    }

    /**
     * Ráfaga del lector: todas las teclas se entregan seguidas en una sola vuelta del hilo de UI,
     * como la cola de eventos de AWT recibe una lectura de pocos milisegundos, sin que una
     * recomposición intermedia (o un equipo de pruebas cargado) separe las teclas más que el
     * umbral de ráfaga de `ScannerBurstDetector`.
     */
    fun burst(value: String, terminators: List<ScanTerminator>) {
        val events = value.flatMap { keyStrokeEvents(AwtKeyEvent.getExtendedKeyCodeForChar(it.code), it) } +
            terminators.flatMap { terminator ->
                when (terminator) {
                    ScanTerminator.ENTER -> keyStrokeEvents(AwtKeyEvent.VK_ENTER, '\n')
                    ScanTerminator.TAB -> keyStrokeEvents(AwtKeyEvent.VK_TAB, '\t')
                    ScanTerminator.NUMPAD_ENTER ->
                        keyStrokeEvents(AwtKeyEvent.VK_ENTER, '\n', AwtKeyEvent.KEY_LOCATION_NUMPAD)
                }
            }
        composeRule.runOnIdle { events.forEach(::dispatch) }
    }

    private fun keyStroke(keyCode: Int, character: Char, location: Int = AwtKeyEvent.KEY_LOCATION_STANDARD) {
        keyStrokeEvents(keyCode, character, location).forEach(::send)
    }

    private fun keyStrokeEvents(
        keyCode: Int,
        character: Char,
        location: Int = AwtKeyEvent.KEY_LOCATION_STANDARD,
    ): List<AwtKeyEvent> {
        val now = System.currentTimeMillis()
        return listOf(
            AwtKeyEvent(awtSource, AwtKeyEvent.KEY_PRESSED, now, 0, keyCode, character, location),
            AwtKeyEvent(awtSource, AwtKeyEvent.KEY_TYPED, now, 0, AwtKeyEvent.VK_UNDEFINED, character, AwtKeyEvent.KEY_LOCATION_UNKNOWN),
            AwtKeyEvent(awtSource, AwtKeyEvent.KEY_RELEASED, now, 0, keyCode, character, location),
        )
    }
}

internal fun DesktopAppHarness.journeyKeyboard(composeRule: ComposeContentTestRule): JourneyKeyboard =
    JourneyKeyboard(composeRule) { event ->
        handleWindowKeyEvent(event, AppAccessState.UNLOCKED, backPressedDispatcher)
    }
