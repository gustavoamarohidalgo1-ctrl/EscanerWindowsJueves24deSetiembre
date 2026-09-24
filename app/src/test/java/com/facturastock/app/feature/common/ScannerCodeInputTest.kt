package com.facturastock.app.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facturastock.app.core.input.DesktopKeyboardWedge
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.testing.DesktopKeyboard
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Campo del lector en escritorio (OutlinedTextField + [ScannerCodeFieldState]). Las teclas llegan
 * con la secuencia AWT real mediante [DesktopKeyboard]; a ritmo de ráfaga imitan al lector USB y
 * con pausas a una persona. Sustituye a las pruebas de `ScannerCodeEditText`/InputConnection.
 */
class ScannerCodeInputTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val keyboard by lazy { DesktopKeyboard(composeRule) }

    @Before
    fun setUp() {
        KeyboardWedgeRouter.deactivate()
        DesktopKeyboardWedge.reset()
    }

    @After
    fun tearDown() {
        KeyboardWedgeRouter.deactivate()
        DesktopKeyboardWedge.reset()
    }

    @Test
    fun revokedPermissionImmediatelyRejectsInputAndRegrantRequiresAFreshScan() {
        val permission = mutableStateOf(true)
        val codes = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalScannerInputPermission provides { permission.value }) {
                FacturaStockTheme {
                    ScannerCodeInput(enabled = true, onCode = codes::add)
                }
            }
        }
        field().assertIsFocused()
        keyboard.type("001")
        composeRule.runOnIdle { permission.value = false }
        // Lo que llegue después de revocar no confirma ni deja rastro.
        keyboard.type("23")
        keyboard.enter()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertIsNotEnabled()
        field().assertIsNotEnabled()
        composeRule.runOnIdle {
            assertTrue(codes.isEmpty())
            assertEquals("", fieldText())
            permission.value = true
        }
        field().assertIsEnabled().assertIsFocused()
        composeRule.runOnIdle { assertEquals("", fieldText()) }
        keyboard.type("00042")
        keyboard.enter()
        composeRule.runOnIdle { assertEquals(listOf("00042"), codes) }
    }

    @Test
    fun resetIsAvailableWithEmptyCodeAndClearsPhysicalReaderWithoutSubmitting() {
        val enabled = mutableStateOf(true)
        val codes = mutableListOf<String>()
        var resetCount = 0
        content(codes = codes, enabled = enabled, onClearPhysicalInput = { resetCount += 1 })

        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertIsEnabled().performClick()
        field().assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(1, resetCount)
            assertEquals("", fieldText())
            assertTrue(codes.isEmpty())
            enabled.value = false
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertIsNotEnabled()
    }

    @Test
    fun resetClearsMalformedCodeAndErrorThenAcceptsTheNextScan() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        field().performTextInput("001\r\n002\r\n")
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).performClick()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertDoesNotExist()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertIsNotEnabled()
        field().assertIsFocused()
        composeRule.runOnIdle { assertEquals("", fieldText()) }
        keyboard.type("00042")
        keyboard.enter()
        composeRule.runOnIdle { assertEquals(listOf("00042"), codes) }
    }

    @Test
    fun focusesOnStartAndPreservesUnterminatedCodeUntilExplicitSubmit() {
        val codes = mutableListOf<String>()
        content(codes = codes)

        field().assertIsFocused()
        keyboard.type("000123")
        composeRule.runOnIdle {
            assertEquals("000123", fieldText())
            assertTrue(codes.isEmpty())
        }
        composeRule.mainClock.advanceTimeBy(5_000L)
        composeRule.runOnIdle { assertTrue(codes.isEmpty()) }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("000123"), codes)
            assertEquals("", fieldText())
        }
    }

    @Test
    fun enterNumpadEnterAndTabAfterABurstSubmitOnceWithoutMovingFocus() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        listOf<() -> Unit>(
            { keyboard.enter() },
            {
                keyboard.send(numpadEnter(java.awt.event.KeyEvent.KEY_PRESSED))
                keyboard.send(numpadEnter(java.awt.event.KeyEvent.KEY_RELEASED))
            },
            { keyboard.tab() },
        ).forEachIndexed { index, terminator ->
            keyboard.type("000$index")
            terminator()
            field().assertIsFocused()
            composeRule.runOnIdle { assertEquals("", fieldText()) }
        }
        composeRule.runOnIdle { assertEquals(listOf("0000", "0001", "0002"), codes) }
        // Tab con el campo vacío no confirma nada.
        keyboard.tab()
        composeRule.runOnIdle { assertEquals(3, codes.size) }
    }

    @Test
    fun insertedTextHandlesWholeCrLfFramesSplitSuffixAndManualEnter() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        field().performTextInput("00123\r\n")
        composeRule.runOnIdle { assertEquals(listOf("00123"), codes) }
        field().performTextInput("00456\r")
        field().performTextInput("\n")
        composeRule.runOnIdle { assertEquals(listOf("00123", "00456"), codes) }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertDoesNotExist()
        // Pegar un código completo con tabulador final (antes ACTION_MULTIPLE) también confirma.
        field().performTextInput("000123\t")
        // Escribir a mano y pulsar Enter (antes IME Done) confirma fuera del modo búsqueda.
        keyboard.type("00078", interKeyDelayMillis = 70L)
        keyboard.enter()
        composeRule.runOnIdle {
            assertEquals(listOf("00123", "00456", "000123", "00078"), codes)
            assertEquals("", fieldText())
        }
    }

    @Test
    fun malformedBulkCodesAreRejectedWholeWithoutSearchingForAValidPrefixOrSuffix() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        listOf("00123\r\n00456\r\n", "001\u001D23\r", "0".repeat(129) + "\r", "001Á23\t")
            .forEach { malformed ->
                composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).performClick()
                field().performTextInput(malformed)
                composeRule.runOnIdle {
                    assertEquals(malformed, fieldText())
                    assertTrue(codes.isEmpty())
                }
                composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertIsDisplayed()
                composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
                composeRule.runOnIdle { assertTrue(codes.isEmpty()) }
            }
    }

    @Test
    fun physicalPartialCodeCanBeConfirmedAndManualEditSurvivesResetRecomposition() {
        val physical = mutableStateOf("")
        val codes = mutableListOf<String>()
        var clearCount = 0
        content(
            codes = codes,
            physicalInput = physical,
            onClearPhysicalInput = {
                clearCount += 1
                physical.value = ""
            },
        )
        composeRule.runOnIdle { physical.value = "000123" }
        composeRule.runOnIdle { assertEquals("000123", fieldText()) }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        // Confirmar con el botón devuelve el foco al campo para la siguiente lectura.
        field().assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(listOf("000123"), codes)
            assertEquals("", physical.value)
            physical.value = "000"
        }
        composeRule.runOnIdle {
            assertEquals("000", fieldText())
            assertEquals(3, fieldSelection())
        }
        field().assertIsFocused()
        keyboard.type("789", interKeyDelayMillis = 70L)
        composeRule.runOnIdle {
            assertEquals("", physical.value)
            assertEquals("000789", fieldText())
            assertTrue(clearCount >= 2)
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        composeRule.runOnIdle { assertEquals(listOf("000123", "000789"), codes) }
    }

    @Test
    fun physicalCompletionClearsMirrorAndUnrelatedRecompositionKeepsTypedDraft() {
        val physical = mutableStateOf("")
        val codes = mutableListOf<String>()
        val redraw = mutableStateOf(0)
        composeRule.setContent {
            redraw.value
            FacturaStockTheme {
                ScannerCodeInput(enabled = true, onCode = codes::add, physicalInput = physical.value)
            }
        }
        composeRule.runOnIdle { physical.value = "00123" }
        composeRule.runOnIdle { assertEquals("00123", fieldText()) }
        composeRule.runOnIdle { physical.value = "" }
        composeRule.runOnIdle { assertEquals("", fieldText()) }
        keyboard.type("00987", interKeyDelayMillis = 70L)
        composeRule.runOnIdle { redraw.value += 1 }
        composeRule.runOnIdle {
            assertEquals("00987", fieldText())
            assertTrue(codes.isEmpty())
        }
    }

    /**
     * Regresión de doble captura: con el campo enfocado y el registro físico activo (Vender,
     * Inventario), cada lectura llega una sola vez, por el campo, y el lector global no acumula
     * ni completa la misma trama. Incluye dígitos repetidos en el mismo milisegundo.
     */
    @Test
    fun consecutiveScannerFramesWithActiveRegistrationDeliverEachCodeExactlyOnce() {
        val physical = mutableStateOf("")
        val physicalHistory = mutableListOf<String>()
        val redraw = mutableStateOf(0)
        val fieldCodes = mutableListOf<String>()
        val routerCodes = mutableListOf<String>()
        composeRule.setContent {
            redraw.value
            FacturaStockTheme {
                PhysicalScannerRegistration(
                    enabled = true,
                    onAvailabilityChanged = {},
                    onScan = {
                        routerCodes += it
                        redraw.value += 1
                    },
                    onInputChanged = {
                        physicalHistory += it
                        physical.value = it
                    },
                )
                ScannerCodeInput(
                    enabled = true,
                    onCode = {
                        fieldCodes += it
                        redraw.value += 1
                    },
                    physicalInput = physical.value,
                    onClearPhysicalInput = {
                        KeyboardWedgeRouter.reset()
                        physical.value = ""
                    },
                )
            }
        }
        val windowKeyboard = DesktopKeyboard(composeRule) { event ->
            val wedgeEvent = DesktopKeyboardWedge.toKeyboardWedgeEventOrNull(event)
            wedgeEvent != null && KeyboardWedgeRouter.route(wedgeEvent)
        }
        field().assertIsFocused()
        val frames = listOf("77529305", "7753176004930", "7753176004916", "7753176004930")
        // Dos pulsaciones completas del mismo dígito en el mismo milisegundo no son un duplicado.
        val timestamp = System.currentTimeMillis()
        repeat(2) {
            listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.KEY_RELEASED)
                .forEach { id ->
                    val code = if (id == java.awt.event.KeyEvent.KEY_TYPED) java.awt.event.KeyEvent.VK_UNDEFINED else java.awt.event.KeyEvent.VK_7
                    windowKeyboard.send(DesktopKeyboard.awtKeyEvent(id, code, '7', timestamp))
                }
        }
        composeRule.runOnIdle { assertEquals("77", fieldText()) }
        windowKeyboard.type(frames.first().drop(2))
        windowKeyboard.enter()
        frames.drop(1).forEach { frame ->
            windowKeyboard.type(frame)
            windowKeyboard.enter()
        }
        composeRule.runOnIdle {
            assertEquals(frames, fieldCodes)
            assertTrue("El lector global no debe completar la misma trama: $routerCodes", routerCodes.isEmpty())
            assertTrue("El lector global no debe acumular la trama: $physicalHistory", physicalHistory.all(String::isEmpty))
            assertEquals("", fieldText())
        }
        field().assertIsFocused()
    }

    @Test
    fun disabledFieldIgnoresClosingKeysAndReenableAcceptsOnlyANewScan() {
        val enabled = mutableStateOf(true)
        val codes = mutableListOf<String>()
        content(codes = codes, enabled = enabled)
        keyboard.type("00123")
        composeRule.runOnIdle { enabled.value = false }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertIsNotEnabled()
        field().assertIsNotEnabled()
        keyboard.tab()
        keyboard.type("99999")
        keyboard.enter()
        composeRule.runOnIdle {
            assertEquals("", fieldText())
            assertTrue(codes.isEmpty())
            enabled.value = true
        }
        field().assertIsFocused()
        composeRule.runOnIdle { assertEquals("", fieldText()) }
        keyboard.type("00042")
        keyboard.enter()
        composeRule.runOnIdle { assertEquals(listOf("00042"), codes) }
    }

    @Test
    fun captureFollowsStartedLifecycleButDoesNotStealFocusFromAnActiveUserForm() {
        val owner = ScannerLifecycleOwner()
        val enabled = mutableStateOf(true)
        val codes = mutableListOf<String>()
        var formText by mutableStateOf("")
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FacturaStockTheme {
                    Column {
                        ScannerCodeInput(enabled = enabled.value, onCode = codes::add)
                        OutlinedTextField(
                            value = formText,
                            onValueChange = { formText = it },
                            label = { Text("Nombre") },
                            modifier = Modifier.testTag(OTHER_FIELD),
                        )
                    }
                }
            }
        }
        field().assertIsNotEnabled()
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        field().assertIsEnabled().assertIsFocused()
        keyboard.type("001")
        // En escritorio pasar a otra ventana (STARTED) no borra lo escrito; detenerse sí.
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.runOnIdle { assertEquals("001", fieldText()) }
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        field().assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals("", fieldText())
            enabled.value = false
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.onNodeWithTag(OTHER_FIELD).performClick().performTextInput("Arroz")
        composeRule.onNodeWithTag(OTHER_FIELD).assertTextContains("Arroz").assertIsFocused()
        // Un campo deshabilitado no publica `Focused`; basta con que no lo tenga en true.
        field().assert(SemanticsMatcher("scanner field not focused") { it.config.getOrNull(SemanticsProperties.Focused) != true })
        composeRule.runOnIdle { assertTrue(codes.isEmpty()) }
    }

    @Test
    fun optionalSearchPublishesManualLettersAndAcceptsExternalQuery() {
        val query = mutableStateOf("")
        val changes = mutableListOf<String>()
        val codes = mutableListOf<String>()
        content(codes, searchQuery = query, onSearchQueryChange = changes::add)
        keyboard.type("ar", interKeyDelayMillis = 70L)
        composeRule.runOnIdle {
            assertEquals("ar", query.value)
            assertEquals("ar", fieldText())
            assertEquals(listOf("a", "ar"), changes)
            assertTrue(codes.isEmpty())
            query.value = "arroz"
        }
        composeRule.runOnIdle {
            assertEquals("arroz", fieldText())
            assertEquals(listOf("a", "ar"), changes)
        }
    }

    @Test
    fun physicalPreviewNeverSearchesAndDefersExternalQueryUntilFrameEnds() {
        val query = mutableStateOf("ar")
        val physical = mutableStateOf("")
        val changes = mutableListOf<String>()
        val codes = mutableListOf<String>()
        content(codes, physicalInput = physical, searchQuery = query, onSearchQueryChange = changes::add)
        composeRule.runOnIdle { physical.value = "000Ab12" }
        composeRule.runOnIdle {
            assertEquals("000Ab12", fieldText())
            query.value = "azucar"
        }
        keyboard.enter()
        composeRule.runOnIdle {
            assertEquals("000Ab12", fieldText())
            assertEquals("azucar", query.value)
            assertTrue(changes.isEmpty())
            assertTrue(codes.isEmpty())
            physical.value = ""
        }
        composeRule.runOnIdle {
            assertEquals("azucar", fieldText())
            assertTrue(changes.isEmpty())
        }
    }

    @Test
    fun humanTypingThenEnterInSearchModeSearchesInsteadOfSubmitting() {
        val query = mutableStateOf("")
        val codes = mutableListOf<String>()
        content(codes, searchQuery = query)
        keyboard.type("000Ab12", interKeyDelayMillis = 70L)
        keyboard.enter()
        composeRule.runOnIdle {
            assertEquals("000Ab12", query.value)
            assertEquals("000Ab12", fieldText())
            assertTrue(codes.isEmpty())
        }
        // El botón confirma explícitamente el código escrito y limpia la búsqueda.
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("000Ab12"), codes)
            assertEquals("", query.value)
            assertEquals("", fieldText())
        }
    }

    @Test
    fun scannerBurstThenEnterInSearchModeSubmitsTheCodeOnce() {
        val query = mutableStateOf("")
        val codes = mutableListOf<String>()
        content(codes, searchQuery = query)
        keyboard.type("7750001234567")
        keyboard.enter()
        composeRule.runOnIdle {
            assertEquals(listOf("7750001234567"), codes)
            assertEquals("", query.value)
            assertEquals("", fieldText())
        }
        // Un sufijo CR/LF insertado de una vez sigue confirmando el código exacto una vez.
        field().performTextInput("00123\r")
        field().performTextInput("\n")
        composeRule.runOnIdle {
            assertEquals(listOf("7750001234567", "00123"), codes)
            assertEquals("", fieldText())
            assertEquals("", query.value)
        }
    }

    @Test
    fun tabAfterABurstSubmitsButHumanTabMovesFocusWithoutSubmitting() {
        val codes = mutableListOf<String>()
        composeRule.setContent {
            FacturaStockTheme {
                Column {
                    ScannerCodeInput(enabled = true, onCode = codes::add, showActions = false)
                    OutlinedTextField(value = "", onValueChange = {}, modifier = Modifier.testTag(OTHER_FIELD))
                }
            }
        }
        field().assertIsFocused()
        keyboard.type("0099512300775")
        keyboard.tab()
        field().assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(listOf("0099512300775"), codes)
            assertEquals("", fieldText())
        }

        keyboard.type("00042", interKeyDelayMillis = 70L)
        Thread.sleep(ScannerBurstDetector.SCANNER_MAX_TERMINATOR_DELAY_MILLIS)
        keyboard.tab()
        composeRule.onNodeWithTag(OTHER_FIELD).assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(listOf("0099512300775"), codes)
            assertEquals("00042", fieldText())
        }
    }

    @Test
    fun optionalSearchResetClearsQueryButDisablingCannotChangeIt() {
        val query = mutableStateOf("ar")
        val enabled = mutableStateOf(true)
        val changes = mutableListOf<String>()
        val codes = mutableListOf<String>()
        content(codes, enabled = enabled, searchQuery = query, onSearchQueryChange = changes::add)
        composeRule.runOnIdle { assertEquals("ar", fieldText()) }
        composeRule.runOnIdle { enabled.value = false }
        keyboard.type("otro")
        keyboard.enter()
        composeRule.runOnIdle {
            assertEquals("", fieldText())
            assertEquals("ar", query.value)
            assertTrue(changes.isEmpty())
            enabled.value = true
        }
        composeRule.runOnIdle {
            assertEquals("ar", fieldText())
            assertEquals("ar", query.value)
            assertTrue(changes.isEmpty())
            assertTrue(codes.isEmpty())
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).performClick()
        field().assertIsFocused()
        composeRule.runOnIdle {
            assertEquals("", query.value)
            assertEquals(listOf(""), changes)
        }
    }

    @Test
    fun optionalSearchKeepsAnInvalidFrameAndItsErrorWithoutChangingTheQuery() {
        val query = mutableStateOf("")
        val codes = mutableListOf<String>()
        content(codes, searchQuery = query)
        field().performTextInput("00123\r")
        composeRule.runOnIdle {
            assertEquals(listOf("00123"), codes)
            assertEquals("", query.value)
        }
        field().performTextInput("001Á23\r")
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("001Á23\r", fieldText())
            assertEquals(listOf("00123"), codes)
            assertEquals("", query.value)
        }
    }

    @Test
    fun unifiedInputKeepsOtherFieldFocusedUntilItIsLeftThenResumesSearchTyping() {
        val query = mutableStateOf("")
        val otherText = mutableStateOf("")
        val otherFocused = mutableStateOf(false)
        val redraw = mutableStateOf(0)
        lateinit var focusManager: FocusManager
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            redraw.value
            FacturaStockTheme {
                Column {
                    ScannerCodeInput(
                        enabled = true,
                        onCode = {},
                        searchQuery = query.value,
                        onSearchQueryChange = { query.value = it },
                        isOtherTextInputFocused = otherFocused.value,
                    )
                    OutlinedTextField(
                        value = otherText.value,
                        onValueChange = { otherText.value = it },
                        label = { Text("Deudor") },
                        modifier = Modifier
                            .testTag(OTHER_FIELD)
                            .onFocusChanged { otherFocused.value = it.isFocused },
                    )
                }
            }
        }
        field().assertIsFocused()
        composeRule.onNodeWithTag(OTHER_FIELD).performClick().performTextInput("Cliente")
        composeRule.runOnIdle {
            assertTrue(otherFocused.value)
            redraw.value += 1
        }
        field().assertIsNotFocused().assertIsEnabled()
        composeRule.runOnIdle { focusManager.clearFocus(force = true) }
        composeRule.runOnIdle { assertTrue(!otherFocused.value) }
        field().assertIsFocused()
        keyboard.type("ar", interKeyDelayMillis = 70L)
        composeRule.runOnIdle {
            assertEquals("ar", query.value)
            assertEquals("Cliente", otherText.value)
        }
    }

    private fun content(
        codes: MutableList<String>,
        enabled: MutableState<Boolean> = mutableStateOf(true),
        physicalInput: MutableState<String> = mutableStateOf(""),
        onClearPhysicalInput: () -> Unit = {},
        searchQuery: MutableState<String>? = null,
        onSearchQueryChange: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            FacturaStockTheme {
                ScannerCodeInput(
                    enabled = enabled.value,
                    onCode = codes::add,
                    physicalInput = physicalInput.value,
                    onClearPhysicalInput = onClearPhysicalInput,
                    searchQuery = searchQuery?.value,
                    onSearchQueryChange =
                        searchQuery?.let { state ->
                            { value ->
                                state.value = value
                                onSearchQueryChange(value)
                            }
                        },
                )
            }
        }
    }

    private fun field() = composeRule.onNodeWithTag(ScannerCodeInputTestTags.FIELD)

    private fun fieldText(): String =
        field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun fieldSelection(): Int =
        requireNotNull(field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)).end

    private fun numpadEnter(id: Int) = java.awt.event.KeyEvent(
        javax.swing.JPanel(),
        id,
        System.currentTimeMillis(),
        0,
        java.awt.event.KeyEvent.VK_ENTER,
        java.awt.event.KeyEvent.CHAR_UNDEFINED,
        java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD,
    )

    private class ScannerLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }

    private companion object {
        const val OTHER_FIELD = "other_field"
    }
}
