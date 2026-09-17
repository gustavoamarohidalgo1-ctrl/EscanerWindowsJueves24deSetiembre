package com.facturastock.app.feature.common

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.core.input.toKeyboardWedgeEvents
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Usa la InputConnection nativa del campo; no sustituye una prueba del lector USB real. */
@RunWith(AndroidJUnit4::class)
class ScannerCodeInputTest {
    @get:Rule
    val composeRule = createComposeRule()
    private lateinit var root: View

    @Test
    fun revokedPermissionImmediatelyRejectsOpenConnectionAndUnlockRequiresANewConnection() {
        val permission = mutableStateOf(true)
        val codes = mutableListOf<String>()
        lateinit var oldConnection: InputConnection
        composeRule.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalScannerInputPermission provides { permission.value }) {
                FacturaStockTheme {
                    ScannerCodeInput(enabled = true, onCode = codes::add)
                }
            }
        }
        composeRule.runOnIdle {
            oldConnection = connection(field())
            assertTrue(oldConnection.commitText("001", 1))
            permission.value = false
            // El evento llega en el mismo bloque, antes de que Compose aplique active=false.
            assertTrue(oldConnection.commitText("23\r", 1))
            assertTrue(oldConnection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertTrue(codes.isEmpty())
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertFalse(field().isEnabled)
            assertEquals("", field().text.toString())
            permission.value = true
        }
        composeRule.runOnIdle {
            assertTrue(field().isEnabled)
            assertTrue(field().hasFocus())
            assertTrue(oldConnection.commitText("99999\r", 1))
            assertTrue(codes.isEmpty())
            assertEquals("", field().text.toString())
            assertTrue(connection(field()).commitText("00042\r", 1))
            assertEquals(listOf("00042"), codes)
        }
    }

    @Test
    fun resetIsAvailableWithEmptyCodeAndClearsPhysicalReaderWithoutSubmitting() {
        val enabled = mutableStateOf(true)
        val codes = mutableListOf<String>()
        var resetCount = 0
        content(codes = codes, enabled = enabled, onClearPhysicalInput = { resetCount += 1 })

        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, resetCount)
            assertEquals("", field().text.toString())
            assertTrue(field().hasFocus())
            assertFalse(field().showSoftInputOnFocus)
            assertTrue(codes.isEmpty())
            enabled.value = false
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertIsNotEnabled()
    }

    @Test
    fun resetClearsMalformedCodeAndErrorThenAcceptsTheNextScan() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        composeRule.runOnIdle { connection(field()).commitText("001\r\n002\r\n", 1) }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).performClick()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertDoesNotExist()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals("", field().text.toString())
            assertTrue(field().hasFocus())
            connection(field()).commitText("00042\r", 1)
            assertEquals(listOf("00042"), codes)
        }
    }

    @Test
    fun focusesWithoutOpeningKeyboardAndPreservesUnterminatedCodeUntilExplicitSubmit() {
        val codes = mutableListOf<String>()
        content(codes = codes)

        composeRule.runOnIdle {
            val field = field()
            assertTrue(field.hasFocus())
            assertFalse(field.showSoftInputOnFocus)
            val connection = connection(field)
            assertTrue(connection.commitText("000123", 1))
            assertEquals("000123", field.text.toString())
            assertTrue(codes.isEmpty())
        }
        composeRule.mainClock.advanceTimeBy(5_000L)
        composeRule.runOnIdle { assertTrue(codes.isEmpty()) }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("000123"), codes)
            assertEquals("", field().text.toString())
        }
    }

    @Test
    fun virtualEnterNumpadEnterAndTabConsumeBothActionsWithoutMovingFocusOrDuplicatingCode() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        composeRule.runOnIdle {
            val field = field()
            val connection = connection(field)
            listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB)
                .forEachIndexed { index, key ->
                    assertTrue(connection.commitText("000$index", 1))
                    val down = virtualKey(KeyEvent.ACTION_DOWN, key)
                    assertTrue(field.dispatchKeyEvent(down))
                    assertTrue(field.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
                    assertTrue(field.hasFocus())
                    assertEquals("", field.text.toString())
                }
            assertEquals(listOf("0000", "0001", "0002"), codes)
            val emptyTab = virtualKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)
            assertTrue(field.dispatchKeyEvent(emptyTab))
            assertTrue(field.dispatchKeyEvent(KeyEvent.changeAction(emptyTab, KeyEvent.ACTION_UP)))
            assertEquals(3, codes.size)
        }
    }

    @Test
    fun actualCommitTextHandlesWholeCrLfFramesSplitSuffixAndImeDone() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        composeRule.runOnIdle {
            val connection = connection(field())
            assertTrue(connection.commitText("00123\r\n", 1))
            assertEquals(listOf("00123"), codes)
            assertTrue(connection.commitText("00456\r", 1))
            assertTrue(connection.commitText("\n", 1))
            assertEquals(listOf("00123", "00456"), codes)
            assertTrue(connection.commitText("00078", 1))
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertEquals(listOf("00123", "00456", "00078"), codes)
            assertEquals("", field().text.toString())
        }
    }

    @Test
    fun finishedCompositionSubmitsEachTerminatedFrameOnceAndAcceptsTheNextRead() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        composeRule.runOnIdle {
            val connection = connection(field())
            assertTrue(connection.setComposingText("001", 1))
            assertTrue(connection.setComposingText("00123\r", 1))
            assertTrue(codes.isEmpty())
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("00123"), codes)
            assertEquals("", field().text.toString())
            assertTrue(connection.finishComposingText())
            assertTrue(connection.commitText("\n", 1))
            assertEquals(listOf("00123"), codes)
            assertTrue(connection.setComposingText("00123\r\n", 1))
            assertTrue(connection.finishComposingText())
            assertTrue(connection.commitText("00078\t", 1))
            assertEquals(listOf("00123", "00123", "00078"), codes)
            assertEquals("", field().text.toString())
        }
    }

    @Test
    fun finishedCompositionWithoutSuffixWaitsForExplicitSubmit() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        composeRule.runOnIdle {
            val connection = connection(field())
            assertTrue(connection.setComposingText("000123", 1))
            assertTrue(connection.finishComposingText())
            assertTrue(connection.finishComposingText())
            assertEquals("000123", field().text.toString())
            assertTrue(codes.isEmpty())
        }
        composeRule.mainClock.advanceTimeBy(5_000L)
        composeRule.runOnIdle { assertTrue(codes.isEmpty()) }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("000123"), codes)
            assertEquals("", field().text.toString())
        }
    }

    @Test
    fun finishedMalformedCompositionRejectsTheWholeFrameAndResetAllowsAnotherRead() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        listOf("00123\r\n00456\r\n", "001\u001D23\r", "0".repeat(129) + "\r", "001Á23\t")
            .forEach { malformed ->
                composeRule.runOnIdle {
                    val connection = connection(field())
                    assertTrue(connection.setComposingText(malformed, 1))
                    assertTrue(connection.finishComposingText())
                    assertEquals(malformed, field().text.toString())
                    assertTrue(codes.isEmpty())
                }
                composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertIsDisplayed()
                composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).performClick()
                composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertDoesNotExist()
            }
        composeRule.runOnIdle {
            val connection = connection(field())
            assertTrue(connection.setComposingText("00042\r", 1))
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("00042"), codes)
        }
    }

    @Test
    fun obsoleteConnectionCannotFinishOrSubmitTheNewConnectionsComposition() {
        val enabled = mutableStateOf(true)
        val codes = mutableListOf<String>()
        lateinit var oldConnection: InputConnection
        content(codes = codes, enabled = enabled)
        composeRule.runOnIdle {
            oldConnection = connection(field())
            assertTrue(oldConnection.setComposingText("00123\r", 1))
            enabled.value = false
        }
        composeRule.runOnIdle {
            assertFalse(field().isEnabled)
            assertTrue(oldConnection.finishComposingText())
            assertTrue(codes.isEmpty())
            assertEquals("", field().text.toString())
            enabled.value = true
        }
        composeRule.runOnIdle {
            val connection = connection(field())
            assertTrue(connection.setComposingText("00042\r", 1))
            val composingStart = BaseInputConnection.getComposingSpanStart(field().text)
            val composingEnd = BaseInputConnection.getComposingSpanEnd(field().text)
            assertTrue(composingStart >= 0)
            assertTrue(oldConnection.finishComposingText())
            assertEquals(composingStart, BaseInputConnection.getComposingSpanStart(field().text))
            assertEquals(composingEnd, BaseInputConnection.getComposingSpanEnd(field().text))
            assertTrue(codes.isEmpty())
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("00042"), codes)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun virtualMultipleCharacterEventSubmitsTheWholeCode() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        composeRule.runOnIdle {
            assertTrue(
                field().dispatchKeyEvent(
                    KeyEvent(SystemClock.uptimeMillis(), "000123\t", KeyCharacterMap.VIRTUAL_KEYBOARD, 0),
                ),
            )
            assertEquals(listOf("000123"), codes)
        }
    }

    @Test
    fun malformedBulkCodesAreRejectedWholeWithoutSearchingForAValidPrefixOrSuffix() {
        val codes = mutableListOf<String>()
        content(codes = codes)
        listOf("00123\r\n00456\r\n", "001\u001D23\r", "0".repeat(129) + "\r", "001Á23\t")
            .forEach { malformed ->
                composeRule.runOnIdle {
                    val field = field()
                    field.setText("")
                    assertTrue(connection(field).commitText(malformed, 1))
                    assertEquals(malformed, field.text.toString())
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
        composeRule.runOnIdle { assertEquals("000123", field().text.toString()) }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("000123"), codes)
            assertEquals("", physical.value)
            physical.value = "000"
        }

        fun describeInput(phase: String): String {
            val input = field()
            return "$phase: text='${input.text}', physical='${physical.value}', " +
                "selection=${input.selectionStart}..${input.selectionEnd}, focused=${input.hasFocus()}, " +
                "enabled=${input.isEnabled}, composing=" +
                "${BaseInputConnection.getComposingSpanStart(input.text)}.." +
                "${BaseInputConnection.getComposingSpanEnd(input.text)}, clears=$clearCount"
        }
        composeRule.runOnIdle {
            val beforeConnection = describeInput("before creating connection")
            assertEquals(beforeConnection, "000", field().text.toString())
            assertEquals(beforeConnection, 3, field().selectionStart)
            assertEquals(beforeConnection, 3, field().selectionEnd)
            assertTrue(beforeConnection, field().hasFocus())
            val connection = connection(field())
            val beforeCommit = describeInput("before commitText")
            assertEquals(beforeCommit, "000", field().text.toString())
            assertEquals(beforeCommit, 3, field().selectionStart)
            assertEquals(beforeCommit, 3, field().selectionEnd)
            assertEquals(beforeCommit, -1, BaseInputConnection.getComposingSpanStart(field().text))
            assertTrue(beforeCommit, connection.commitText("789", 1))
            assertEquals(describeInput("immediately after commitText"), "000789", field().text.toString())
        }
        composeRule.runOnIdle {
            assertEquals("", physical.value)
            assertEquals(describeInput("after reset recomposition"), "000789", field().text.toString())
            assertTrue(clearCount >= 2)
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).performClick()
        composeRule.runOnIdle { assertEquals(listOf("000123", "000789"), codes) }
    }

    @Test
    fun genuineCompositionReplacesItsOwnRangeAndPreservesThePhysicalPrefix() {
        val physical = mutableStateOf("")
        val codes = mutableListOf<String>()
        content(codes = codes, physicalInput = physical, onClearPhysicalInput = { physical.value = "" })
        composeRule.runOnIdle { physical.value = "000" }
        composeRule.runOnIdle {
            assertEquals("000", field().text.toString())
            val connection = connection(field())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(field().text))
            assertTrue(connection.setComposingText("789", 1))
            assertEquals("000789", field().text.toString())
            assertEquals(3, BaseInputConnection.getComposingSpanStart(field().text))
            assertEquals(6, BaseInputConnection.getComposingSpanEnd(field().text))
        }
        composeRule.runOnIdle {
            // Volver a crear una conexión conserva la composición real, aunque el espejo se limpió.
            val connection = connection(field())
            assertEquals(3, BaseInputConnection.getComposingSpanStart(field().text))
            assertEquals(6, BaseInputConnection.getComposingSpanEnd(field().text))
            assertTrue(connection.setComposingText("456", 1))
            assertEquals("000456", field().text.toString())
            assertTrue(connection.finishComposingText())
            assertTrue(codes.isEmpty())
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertEquals(listOf("000456"), codes)
        }
    }

    @Test
    fun physicalCompletionClearsMirrorAndUnrelatedRecompositionKeepsImeDraft() {
        val physical = mutableStateOf("")
        val codes = mutableListOf<String>()
        val redraw = mutableStateOf(0)
        composeRule.setContent {
            root = LocalView.current
            redraw.value
            FacturaStockTheme {
                ScannerCodeInput(enabled = true, onCode = codes::add, physicalInput = physical.value)
            }
        }
        composeRule.runOnIdle { physical.value = "00123" }
        composeRule.runOnIdle { assertEquals("00123", field().text.toString()) }
        composeRule.runOnIdle { physical.value = "" }
        composeRule.runOnIdle {
            assertEquals("", field().text.toString())
            connection(field()).commitText("00987", 1)
            redraw.value += 1
        }
        composeRule.runOnIdle {
            assertEquals("00987", field().text.toString())
            assertTrue(codes.isEmpty())
        }
    }

    @Test
    fun consecutivePhysicalFramesPreserveRepeatedDigitsWithoutWaitingForPreviewRecomposition() {
        val physical = mutableStateOf("")
        val redraw = mutableStateOf(0)
        val codes = mutableListOf<String>()
        var resetCount = 0
        val onCode: (String) -> Unit = { code ->
            codes += code
            redraw.value += 1
        }
        val keyboard =
            requireNotNull(
                InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().firstOrNull {
                    !it.isVirtual && it.supportsSource(InputDevice.SOURCE_KEYBOARD)
                },
            )
        composeRule.setContent {
            root = LocalView.current
            redraw.value
            FacturaStockTheme {
                PhysicalScannerRegistration(
                    enabled = true,
                    onAvailabilityChanged = {},
                    onScan = onCode,
                    onInputChanged = { physical.value = it },
                )
                ScannerCodeInput(
                    enabled = true,
                    onCode = onCode,
                    physicalInput = physical.value,
                    onClearPhysicalInput = {
                        resetCount += 1
                        KeyboardWedgeRouter.reset()
                        physical.value = ""
                    },
                )
            }
        }
        val timestamp = SystemClock.uptimeMillis()

        fun dispatch(keyCode: Int) {
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                val event =
                    KeyEvent(
                        timestamp,
                        timestamp,
                        action,
                        keyCode,
                        0,
                        0,
                        keyboard.id,
                        0,
                        0,
                        InputDevice.SOURCE_KEYBOARD,
                    )
                var consumed = false
                event.toKeyboardWedgeEvents().forEach { wedgeEvent ->
                    if (KeyboardWedgeRouter.route(wedgeEvent)) consumed = true
                }
                if (!consumed) consumed = field().dispatchKeyEvent(event)
                assertTrue("Debe consumirse el evento físico $keyCode/$action", consumed)
            }
        }
        val frames = listOf("77529305", "7753176004930", "7753176004916", "7753176004930")
        // Dos teclas completas pueden compartir milisegundo y valor sin ser el mismo evento.
        composeRule.runOnIdle {
            repeat(2) { dispatch(KeyEvent.KEYCODE_7) }
        }
        composeRule.runOnIdle {
            assertEquals("77", field().text.toString())
            frames.first().drop(2).forEach { dispatch(KeyEvent.KEYCODE_0 + it.digitToInt()) }
            dispatch(KeyEvent.KEYCODE_ENTER)
            // Las siguientes lecturas y sus callbacks llegan antes de aplicar el clear visual.
            frames.drop(1).forEach { frame ->
                frame.forEach { dispatch(KeyEvent.KEYCODE_0 + it.digitToInt()) }
                dispatch(KeyEvent.KEYCODE_ENTER)
            }
            assertEquals(frames, codes)
            assertEquals(0, resetCount)
            assertTrue(field().hasFocus())
        }
        composeRule.runOnIdle {
            assertEquals("", field().text.toString())
            assertEquals(frames, codes)
            assertEquals(0, resetCount)
            assertTrue(field().hasFocus())
        }
    }

    @Test
    fun disabledFieldConsumesClosingKeysAndRejectsOldConnectionsAfterReenable() {
        val enabled = mutableStateOf(true)
        val codes = mutableListOf<String>()
        lateinit var oldConnection: InputConnection
        content(codes = codes, enabled = enabled)
        composeRule.runOnIdle {
            oldConnection = connection(field())
            oldConnection.commitText("00123", 1)
            enabled.value = false
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertIsNotEnabled()
        composeRule.runOnIdle {
            val field = field()
            assertFalse(field.isEnabled)
            val down = virtualKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)
            assertTrue(field.dispatchKeyEvent(down))
            assertTrue(field.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
            oldConnection.commitText("99999\r", 1)
            assertEquals("", field.text.toString())
            assertTrue(codes.isEmpty())
            enabled.value = true
        }
        composeRule.runOnIdle {
            assertTrue(field().hasFocus())
            oldConnection.commitText("88888\r", 1)
            assertTrue(codes.isEmpty())
            connection(field()).commitText("00042\r", 1)
            assertEquals(listOf("00042"), codes)
        }
    }

    @Test
    fun resumesCaptureButDoesNotStealFocusFromAnActiveUserFormDuringRecomposition() {
        val owner = ScannerLifecycleOwner()
        val enabled = mutableStateOf(true)
        val codes = mutableListOf<String>()
        var formText by mutableStateOf("")
        composeRule.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FacturaStockTheme {
                    Column {
                        ScannerCodeInput(enabled = enabled.value, onCode = codes::add)
                        OutlinedTextField(
                            value = formText,
                            onValueChange = { formText = it },
                            label = { androidx.compose.material3.Text("Nombre") },
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle {
            assertFalse(field().isEnabled)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.runOnIdle {
            assertTrue(field().hasFocus())
            connection(field()).commitText("001", 1)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        composeRule.runOnIdle {
            assertEquals("", field().text.toString())
            enabled.value = false
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.onNodeWithText("Nombre").performClick().performTextInput("Arroz")
        composeRule.onNodeWithText("Nombre").assertTextContains("Arroz")
        composeRule.runOnIdle {
            assertFalse(field().hasFocus())
            assertTrue(codes.isEmpty())
        }
    }

    @Test
    fun optionalSearchPublishesManualLettersAndPreservesSelectionAndComposition() {
        val query = mutableStateOf("")
        val changes = mutableListOf<String>()
        val codes = mutableListOf<String>()
        content(codes, searchQuery = query, onSearchQueryChange = changes::add)
        composeRule.runOnIdle {
            val editor = EditorInfo()
            val connection = requireNotNull(field().onCreateInputConnection(editor))
            assertFalse(field().showSoftInputOnFocus)
            assertEquals(EditorInfo.IME_ACTION_SEARCH, field().imeOptions and EditorInfo.IME_MASK_ACTION)
            assertEquals(EditorInfo.IME_ACTION_SEARCH, editor.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertEquals(0, editor.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            assertTrue(connection.setComposingText("ar", 1))
            field().setSelection(1)
            assertEquals("ar", query.value)
        }
        composeRule.runOnIdle {
            assertEquals("ar", field().text.toString())
            assertEquals(1, field().selectionStart)
            assertEquals(0, BaseInputConnection.getComposingSpanStart(field().text))
            assertEquals(2, BaseInputConnection.getComposingSpanEnd(field().text))
            assertEquals(listOf("ar"), changes)
            assertTrue(codes.isEmpty())
            query.value = "arroz"
        }
        composeRule.runOnIdle {
            assertEquals("arroz", field().text.toString())
            assertEquals(listOf("ar"), changes)
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
            assertEquals("000Ab12", field().text.toString())
            query.value = "azucar"
            assertTrue(connection(field()).performEditorAction(EditorInfo.IME_ACTION_SEARCH))
        }
        composeRule.runOnIdle {
            assertEquals("000Ab12", field().text.toString())
            assertEquals("azucar", query.value)
            assertTrue(changes.isEmpty())
            assertTrue(codes.isEmpty())
            physical.value = ""
        }
        composeRule.runOnIdle {
            assertEquals("azucar", field().text.toString())
            assertTrue(changes.isEmpty())
        }
    }

    @Test
    fun optionalSearchActionDoesNotSubmitButDoneAndSuffixStillDeliverExactCodesOnce() {
        val query = mutableStateOf("")
        val codes = mutableListOf<String>()
        content(codes, searchQuery = query)
        composeRule.runOnIdle {
            val connection = connection(field())
            assertTrue(connection.commitText("000Ab12", 1))
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH))
            assertEquals("000Ab12", query.value)
            assertTrue(codes.isEmpty())
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertEquals(listOf("000Ab12"), codes)
            assertEquals("", query.value)
            assertTrue(connection.commitText("00123\r", 1))
            assertTrue(connection.commitText("\n", 1))
            assertEquals(listOf("000Ab12", "00123"), codes)
            assertEquals("", field().text.toString())
        }
        composeRule.runOnIdle {
            assertEquals("", field().text.toString())
            assertEquals("", query.value)
        }
    }

    @Test
    fun optionalSearchResetClearsQueryButDisablingAndOldImeCannotChangeIt() {
        val query = mutableStateOf("ar")
        val enabled = mutableStateOf(true)
        val changes = mutableListOf<String>()
        val codes = mutableListOf<String>()
        lateinit var oldConnection: InputConnection
        content(codes, enabled = enabled, searchQuery = query, onSearchQueryChange = changes::add)
        composeRule.runOnIdle {
            oldConnection = connection(field())
            enabled.value = false
        }
        composeRule.runOnIdle {
            assertEquals("", field().text.toString())
            assertEquals("ar", query.value)
            assertTrue(changes.isEmpty())
            assertTrue(oldConnection.commitText("otro", 1))
            assertTrue(oldConnection.performEditorAction(EditorInfo.IME_ACTION_SEARCH))
            enabled.value = true
        }
        composeRule.runOnIdle {
            assertEquals("ar", field().text.toString())
            assertTrue(oldConnection.commitText("99999\r", 1))
            assertTrue(oldConnection.performEditorAction(EditorInfo.IME_ACTION_SEARCH))
            assertEquals("ar", query.value)
            assertTrue(changes.isEmpty())
            assertTrue(codes.isEmpty())
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).performClick()
        composeRule.runOnIdle {
            assertEquals("", query.value)
            assertEquals(listOf(""), changes)
            assertTrue(field().hasFocus())
        }
    }

    @Test
    fun optionalSearchDoesNotEraseComposedSuffixOrInvalidFrameDuringRecomposition() {
        val query = mutableStateOf("")
        val codes = mutableListOf<String>()
        lateinit var imeConnection: InputConnection
        content(codes, searchQuery = query)
        composeRule.runOnIdle {
            imeConnection = connection(field())
            assertTrue(imeConnection.setComposingText("00123\r", 1))
        }
        composeRule.runOnIdle {
            assertEquals("00123\r", field().text.toString())
            assertTrue(codes.isEmpty())
            assertEquals("", query.value)
            assertTrue(imeConnection.finishComposingText())
            assertEquals(listOf("00123"), codes)
            assertTrue(imeConnection.commitText("001Á23\r", 1))
        }
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.ERROR).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("001Á23\r", field().text.toString())
            assertEquals(listOf("00123"), codes)
            assertEquals("", query.value)
        }
    }

    @Test
    fun unifiedInputKeepsOtherFieldFocusedUntilExplicitResumeRestoresItsConnection() {
        val query = mutableStateOf("")
        val otherText = mutableStateOf("")
        val otherFocused = mutableStateOf(false)
        val redraw = mutableStateOf(0)
        lateinit var focusManager: FocusManager
        composeRule.setContent {
            root = LocalView.current
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
                        label = { androidx.compose.material3.Text("Deudor") },
                        modifier = Modifier.onFocusChanged { otherFocused.value = it.isFocused },
                    )
                }
            }
        }
        composeRule.runOnIdle { assertTrue(field().hasFocus()) }
        composeRule.onNodeWithText("Deudor").performClick().performTextInput("Cliente")
        composeRule.runOnIdle {
            assertTrue(otherFocused.value)
            assertFalse(field().hasFocus())
            assertTrue(field().isEnabled)
            redraw.value += 1
        }
        composeRule.runOnIdle {
            assertFalse(field().hasFocus())
            focusManager.clearFocus(force = true)
        }
        composeRule.runOnIdle {
            assertFalse(otherFocused.value)
            assertTrue(field().hasFocus())
            assertTrue(connection(field()).commitText("ar", 1))
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
            root = LocalView.current
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

    private fun field(): ScannerCodeEditText = checkNotNull(findField(root.rootView))

    private fun findField(view: View): ScannerCodeEditText? {
        if (view is ScannerCodeEditText) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findField(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun connection(field: ScannerCodeEditText): InputConnection {
        val connection = field.onCreateInputConnection(EditorInfo())
        assertNotNull("El campo enfocado debe exponer una InputConnection", connection)
        return requireNotNull(connection)
    }

    private fun virtualKey(
        action: Int,
        keyCode: Int,
    ): KeyEvent {
        val time = SystemClock.uptimeMillis()
        return KeyEvent(
            time,
            time,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
    }

    private class ScannerLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
