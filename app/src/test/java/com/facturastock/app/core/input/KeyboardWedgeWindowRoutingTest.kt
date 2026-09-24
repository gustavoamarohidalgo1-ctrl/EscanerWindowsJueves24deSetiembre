package com.facturastock.app.core.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.facturastock.app.AppAccessState
import com.facturastock.app.handleWindowKeyEvent
import com.facturastock.app.testing.DesktopKeyboard
import com.facturastock.app.ui.navigation.BackPressedDispatcher
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Comprueba el enrutamiento por burbujeo de la ventana (`handleWindowKeyEvent` de Main.kt) con la
 * secuencia AWT real: un lector que escribe sin campo enfocado llega al [KeyboardWedgeRouter]; lo
 * que se escribe en un campo de texto enfocado se queda en el campo y nunca completa una lectura.
 */
class KeyboardWedgeWindowRoutingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scans = mutableListOf<String>()
    private val partialInputs = mutableListOf<String>()
    private val errors = mutableListOf<KeyboardWedgeReadError>()
    private var accessState by mutableStateOf(AppAccessState.UNLOCKED)
    private val backDispatcher = BackPressedDispatcher()
    private var backFallbacks = 0
    private var registration: KeyboardWedgeRegistration? = null
    private var fieldText by mutableStateOf("")
    private var quantityText by mutableStateOf("")
    private var quantityDone = 0

    private val keyboard by lazy {
        DesktopKeyboard(composeRule) { event -> handleWindowKeyEvent(event, accessState, backDispatcher) }
    }

    @Before
    fun setUp() {
        KeyboardWedgeRouter.deactivate()
        DesktopKeyboardWedge.reset()
        backDispatcher.fallback = { backFallbacks += 1 }
        registration = KeyboardWedgeRouter.activate(
            onScan = { scans += it },
            onReadError = { errors += it },
            onInputChanged = { partialInputs += it },
        )
        partialInputs.clear()
        composeRule.setContent {
            FacturaStockTheme {
                Column {
                    OutlinedTextField(
                        value = fieldText,
                        onValueChange = { fieldText = it },
                        singleLine = true,
                        modifier = Modifier.testTag(FIELD),
                    )
                    // Como la cantidad del carrito: numérico, una línea, con acción Done.
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { quantityDone += 1 }),
                        modifier = Modifier.testTag(QUANTITY),
                    )
                    Box(Modifier.size(24.dp).focusable().testTag(NON_TEXT_FOCUSABLE))
                }
            }
        }
    }

    @After
    fun tearDown() {
        registration?.close()
        KeyboardWedgeRouter.deactivate()
        DesktopKeyboardWedge.reset()
    }

    @Test
    fun scanWithNoFocusedNodeIsRoutedToTheWedge() {
        keyboard.type("7750001234567")
        keyboard.enter()

        composeRule.runOnIdle {
            assertEquals(listOf("7750001234567"), scans)
            assertEquals("7750001234567", partialInputs.last { it.isNotEmpty() })
            assertEquals("", fieldText)
            assertEquals("", quantityText)
        }
    }

    @Test
    fun scanWhileANonTextNodeIsFocusedBubblesToTheWedge() {
        composeRule.onNodeWithTag(NON_TEXT_FOCUSABLE).requestFocus()

        keyboard.type("0099512300775")
        keyboard.enter()

        composeRule.runOnIdle { assertEquals(listOf("0099512300775"), scans) }
    }

    @Test
    fun typingIntoAFocusedTextFieldIsNotRoutedToTheWedge() {
        composeRule.onNodeWithTag(FIELD).requestFocus()

        // Tanto a ritmo de lector como a ritmo de persona: el campo consume el KEY_TYPED.
        keyboard.type("456")
        keyboard.enter()
        keyboard.type("78", interKeyDelayMillis = 80L)
        keyboard.enter()

        composeRule.runOnIdle {
            assertEquals("45678", fieldText)
            assertTrue("El lector no debe completar lecturas: $scans", scans.isEmpty())
            assertTrue("El lector no debe acumular lo escrito: $partialInputs", partialInputs.all(String::isEmpty))
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun enterInAFocusedQuantityFieldDoesNotProduceASpuriousWedgeResult() {
        composeRule.onNodeWithTag(QUANTITY).requestFocus()

        keyboard.type("3")
        keyboard.enter()
        keyboard.tab()

        composeRule.runOnIdle {
            assertEquals("3", quantityText)
            assertEquals(1, quantityDone)
            assertTrue(scans.isEmpty())
            assertTrue(errors.isEmpty())
            assertTrue(partialInputs.all(String::isEmpty))
        }
    }

    @Test
    fun scanAfterLeavingTheTextFieldIsRoutedAgain() {
        composeRule.onNodeWithTag(FIELD).requestFocus()
        keyboard.type("12")
        composeRule.onNodeWithTag(NON_TEXT_FOCUSABLE).requestFocus()

        keyboard.type("0011")
        keyboard.enter()

        composeRule.runOnIdle {
            assertEquals("12", fieldText)
            assertEquals(listOf("0011"), scans)
        }
    }

    @Test
    fun lockedWindowNeitherRoutesScansNorLetsEscapeNavigate() {
        composeRule.runOnIdle { accessState = AppAccessState.LOCKED }

        keyboard.type("7750001234567")
        keyboard.enter()
        // Consumido por la ventana: no llega como Atrás a la escena.
        assertTrue(keyboard.keyStroke(java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED, typed = false))

        composeRule.runOnIdle {
            assertTrue(scans.isEmpty())
            assertEquals(0, backFallbacks)
        }
    }

    @Test
    fun escapeIsLeftForTheSceneBackAndDoesNotTouchAPartialScan() {
        keyboard.type("123")
        // La ventana no consume Esc: ComposeSceneMediator lo entrega como Atrás a la escena.
        assertFalse(keyboard.keyStroke(java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED, typed = false))
        composeRule.runOnIdle { assertEquals(0, backFallbacks) }
        keyboard.enter()

        composeRule.runOnIdle { assertEquals(listOf("123"), scans) }
    }

    @Test
    fun wedgeWithoutActiveDestinationLeavesKeysUnconsumed() {
        composeRule.runOnIdle { registration?.close() }

        val consumed = listOf('1', '2').map { character ->
            keyboard.send(DesktopKeyboard.awtKeyEvent(java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.VK_UNDEFINED, character))
        }

        assertFalse(consumed.any { it })
        composeRule.runOnIdle { assertTrue(scans.isEmpty()) }
    }

    private companion object {
        const val FIELD = "field"
        const val QUANTITY = "quantity"
        const val NON_TEXT_FOCUSABLE = "non_text_focusable"
    }
}
