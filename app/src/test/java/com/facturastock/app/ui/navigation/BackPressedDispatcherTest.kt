package com.facturastock.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import com.facturastock.app.testing.SceneSystemBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Semántica de `OnBackPressedDispatcher` reproducida por el despachador de escritorio (Esc). */
class BackPressedDispatcherTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withoutHandlersTheFallbackRuns() {
        val dispatcher = BackPressedDispatcher()
        var fallbacks = 0
        dispatcher.fallback = { fallbacks += 1 }

        assertFalse(dispatcher.hasEnabledCallbacks())
        assertFalse(dispatcher.dispatch())
        dispatcher.onBackPressed()

        assertEquals(1, fallbacks)
    }

    @Test
    fun withoutHandlersOrFallbackBackIsANoOp() {
        val dispatcher = BackPressedDispatcher()
        dispatcher.onBackPressed()
        assertFalse(dispatcher.dispatch())
    }

    @Test
    fun lastEnabledHandlerWinsAndDisabledHandlersAreSkipped() {
        val dispatcher = BackPressedDispatcher()
        val calls = mutableListOf<String>()
        var fallbacks = 0
        dispatcher.fallback = { fallbacks += 1 }
        var outerEnabled by mutableStateOf(true)
        var innerEnabled by mutableStateOf(true)
        var showInner by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalBackPressedDispatcher provides dispatcher) {
                BackHandler(enabled = outerEnabled) { calls += "outer" }
                if (showInner) BackHandler(enabled = innerEnabled) { calls += "inner" }
            }
        }

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("inner"), calls) }

        composeRule.runOnIdle { innerEnabled = false }
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("inner", "outer"), calls) }

        composeRule.runOnIdle { innerEnabled = true }
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("inner", "outer", "inner"), calls) }

        // Al salir de composición el handler se desregistra.
        composeRule.runOnIdle { showInner = false }
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("inner", "outer", "inner", "outer"), calls) }

        composeRule.runOnIdle { outerEnabled = false }
        composeRule.runOnIdle {
            assertFalse(dispatcher.hasEnabledCallbacks())
            dispatcher.onBackPressed()
        }
        composeRule.runOnIdle {
            assertEquals(4, calls.size)
            assertEquals(1, fallbacks)
        }
    }

    @Test
    fun handlerAlwaysInvokesItsLatestLambda() {
        val dispatcher = BackPressedDispatcher()
        var label by mutableStateOf("first")
        val calls = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalBackPressedDispatcher provides dispatcher) {
                val current = label
                BackHandler { calls += current }
            }
        }

        composeRule.runOnIdle { label = "second" }
        composeRule.runOnIdle {
            assertTrue(dispatcher.dispatch())
            assertEquals(listOf("second"), calls)
        }
    }

    @Test
    fun handlerWithoutDispatcherIsIgnoredByTheTopBarPath() {
        var called = false
        composeRule.setContent { BackHandler { called = true } }
        composeRule.runOnIdle { assertFalse(called) }
    }

    /**
     * Esc de la ventana = Atrás de la escena (NavigationEventDispatcher). Un diálogo abierto lo
     * recibe antes que el BackHandler de la pantalla de debajo, y cada Esc tiene un solo efecto.
     */
    @Test
    fun sceneBackReachesAnOpenDialogFirstAndThenTheScreenHandler() {
        val dispatcher = BackPressedDispatcher()
        val sceneBack = SceneSystemBack()
        var dialogOpen by mutableStateOf(true)
        val calls = mutableListOf<String>()
        composeRule.setContent {
            sceneBack.capture()
            CompositionLocalProvider(LocalBackPressedDispatcher provides dispatcher) {
                BackHandler { calls += "screen" }
                if (dialogOpen) {
                    Dialog(onDismissRequest = {
                        calls += "dialog"
                        dialogOpen = false
                    }) { Text("Confirmar") }
                }
            }
        }

        sceneBack.pressBack(composeRule)
        composeRule.runOnIdle {
            assertEquals(listOf("dialog"), calls)
            assertFalse(dialogOpen)
        }
        sceneBack.pressBack(composeRule)
        composeRule.runOnIdle { assertEquals(listOf("dialog", "screen"), calls) }
    }

    @Test
    fun sceneBackHonoursEnabledStateAndRegistrationOrder() {
        val sceneBack = SceneSystemBack()
        var innerEnabled by mutableStateOf(true)
        val calls = mutableListOf<String>()
        composeRule.setContent {
            sceneBack.capture()
            BackHandler { calls += "outer" }
            BackHandler(enabled = innerEnabled) { calls += "inner" }
        }

        sceneBack.pressBack(composeRule)
        composeRule.runOnIdle {
            assertEquals(listOf("inner"), calls)
            innerEnabled = false
        }
        sceneBack.pressBack(composeRule)
        composeRule.runOnIdle { assertEquals(listOf("inner", "outer"), calls) }
    }
}
