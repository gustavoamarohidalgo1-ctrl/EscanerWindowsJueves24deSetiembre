package com.facturastock.app.feature.common

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TextView can search its parent's focus tree while building an IME connection. Inside an
 * AndroidView update that search reenters Compose lazy layout before composition is applied.
 * Probe that boundary directly, without relying on keyboard timing or crashing the process.
 */
@RunWith(AndroidJUnit4::class)
class ScannerInputConnectionRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inputConnectionAvoidsParentFocusSearchAndPreservesEditorActionsAndNormalTraversal() {
        lateinit var parent: FocusSearchProbe
        lateinit var scanner: ScannerCodeEditText
        lateinit var frameworkEditor: EditText
        lateinit var nextFocus: View
        composeRule.setContent {
            AndroidView(
                factory = { context ->
                    FocusSearchProbe(context).also { container ->
                        parent = container
                        scanner =
                            ScannerCodeEditText(context).apply {
                                captureAllowed = { true }
                            }
                        frameworkEditor =
                            EditText(context).apply {
                                // Positive control uses the same native editor configuration.
                                inputType = scanner.inputType
                                imeOptions = scanner.imeOptions
                                maxLines = 1
                                setHorizontallyScrolling(true)
                                showSoftInputOnFocus = false
                                isFocusableInTouchMode = true
                            }
                        nextFocus = View(context).apply { isFocusableInTouchMode = true }
                        container.addView(scanner)
                        container.addView(frameworkEditor)
                        container.addView(nextFocus)
                        container.nextFocus = nextFocus
                    }
                },
            )
        }

        composeRule.runOnIdle {
            assertTrue(frameworkEditor.requestFocus())
            parent.record {
                assertNotNull(frameworkEditor.onCreateInputConnection(EditorInfo()))
            }
            assertTrue(
                "Positive control must exercise the native TextView focus-search path",
                parent.directions.isNotEmpty(),
            )

            assertTrue(scanner.requestFocus())
            for (searchEnabled in listOf(false, true)) {
                scanner.setSearchEnabled(searchEnabled)
                val editorInfo = EditorInfo()
                val connection = parent.record { scanner.onCreateInputConnection(editorInfo) }
                assertNotNull("The scanner must still expose its native input connection", connection)
                assertTrue(
                    "Creating the IME connection must not reenter the parent focus tree",
                    parent.directions.isEmpty(),
                )
                assertEquals(
                    if (searchEnabled) EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_DONE,
                    editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION,
                )
                assertTrue(editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                assertTrue(editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
                if (searchEnabled) {
                    assertEquals(0, editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
                }
                assertFalse(scanner.showSoftInputOnFocus)

                // Suppression must end with connection creation; normal navigation survives.
                val target = parent.record { scanner.focusSearch(View.FOCUS_DOWN) }
                assertSame(nextFocus, target)
                assertEquals(listOf(View.FOCUS_DOWN), parent.directions)
            }
        }
    }

    private class FocusSearchProbe(
        context: Context,
    ) : LinearLayout(context) {
        val directions = mutableListOf<Int>()
        var nextFocus: View? = null
        private var recording = false

        init {
            orientation = VERTICAL
        }

        fun <T> record(block: () -> T): T {
            directions.clear()
            recording = true
            return try {
                block()
            } finally {
                recording = false
            }
        }

        override fun focusSearch(
            focused: View?,
            direction: Int,
        ): View? {
            if (!recording) return super.focusSearch(focused, direction)
            directions += direction
            return nextFocus
        }
    }
}
