package com.facturastock.app.feature.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.input.KeyboardWedgeAssembler
import com.facturastock.app.core.input.KeyboardWedgeKeyAction
import com.facturastock.app.core.input.KeyboardWedgeKeyEvent
import com.facturastock.app.core.input.KeyboardWedgeReadError
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.core.input.KeyboardWedgeTerminator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ciclo de vida y callbacks Compose con eventos sintéticos; no abre Room ni usa datos reales. */
@RunWith(AndroidJUnit4::class)
class PhysicalScannerRegistrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun resetRouter() {
        KeyboardWedgeRouter.deactivate()
    }

    @After
    fun releaseRouter() {
        KeyboardWedgeRouter.deactivate()
    }

    @Test
    fun recompositionUsesLatestScanAndAvailabilityCallbacksWithoutChangingOwnerOrEnabled() {
        val owner = ScannerLifecycleOwner()
        var revision by mutableStateOf(0)
        val scans = mutableListOf<Pair<Int, String>>()
        val availability = mutableListOf<Pair<Int, Boolean>>()
        composeRule.setContent {
            val callbackRevision = revision
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PhysicalScannerRegistration(
                    enabled = true,
                    onAvailabilityChanged = { availability += callbackRevision to it },
                    onScan = { scans += callbackRevision to it },
                )
            }
        }
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            scan("OLD", 1_000L)
            revision = 1
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            scan("NEW", 2_000L)
            owner.registry.currentState = Lifecycle.State.STARTED

            assertEquals(listOf(0 to "OLD", 1 to "NEW"), scans)
            assertEquals(1 to false, availability.last())
        }
    }

    @Test
    fun pauseDiscardsPartialScanAndDisabledRegistrationCannotDeliverInput() {
        val owner = ScannerLifecycleOwner()
        var enabled by mutableStateOf(true)
        val scans = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PhysicalScannerRegistration(
                    enabled = enabled,
                    onAvailabilityChanged = {},
                    onScan = scans::add,
                )
            }
        }
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertTrue(KeyboardWedgeRouter.route(character('A', 1_000L)))
            owner.registry.currentState = Lifecycle.State.STARTED
            assertFalse(KeyboardWedgeRouter.route(character('X', 1_100L)))
            assertFalse(KeyboardWedgeRouter.route(enter(1_110L)))
            owner.registry.currentState = Lifecycle.State.RESUMED
            scan("B", 1_200L)
            enabled = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(KeyboardWedgeRouter.route(character('C', 2_000L)))
            assertFalse(KeyboardWedgeRouter.route(enter(2_010L)))
            assertEquals(listOf("B"), scans)
            enabled = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            scan("D", 3_000L)
            assertEquals(listOf("B", "D"), scans)
        }
    }

    @Test
    fun readErrorsUseLatestCallbackAndNextScanStartsWithAnEmptyBuffer() {
        val owner = ScannerLifecycleOwner()
        var revision by mutableStateOf(0)
        val errors = mutableListOf<Pair<Int, KeyboardWedgeReadError>>()
        val scans = mutableListOf<String>()
        composeRule.setContent {
            val callbackRevision = revision
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PhysicalScannerRegistration(
                    enabled = true,
                    onAvailabilityChanged = {},
                    onScan = scans::add,
                    onReadError = { errors += callbackRevision to it },
                )
            }
        }
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertTrue(KeyboardWedgeRouter.route(character('A', 1_000L)))
            revision = 1
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(KeyboardWedgeRouter.route(character('B', 2_101L)))
            assertTrue(KeyboardWedgeRouter.route(enter(2_110L)))
            scan("OK", 3_000L)
            scan("A".repeat(KeyboardWedgeAssembler.MAX_INPUT_LENGTH + 1), 4_000L)
            scan("NEXT", 6_000L)

            assertEquals(
                listOf(1 to KeyboardWedgeReadError.INCOMPLETE, 1 to KeyboardWedgeReadError.TOO_LONG),
                errors,
            )
            assertEquals(listOf("OK", "NEXT"), scans)
        }
    }

    private fun scan(
        value: String,
        start: Long,
    ) {
        value.forEachIndexed { index, valueChar ->
            assertTrue(KeyboardWedgeRouter.route(character(valueChar, start + index * 2L)))
        }
        assertTrue(KeyboardWedgeRouter.route(enter(start + value.length * 2L)))
    }

    private fun character(
        value: Char,
        time: Long,
    ) = KeyboardWedgeKeyEvent(
        action = KeyboardWedgeKeyAction.DOWN,
        eventTimeMillis = time,
        deviceId = 7,
        keyCode = value.code,
        printableCharacter = value,
    )

    private fun enter(time: Long) =
        KeyboardWedgeKeyEvent(
            action = KeyboardWedgeKeyAction.DOWN,
            eventTimeMillis = time,
            deviceId = 7,
            keyCode = 66,
            terminator = KeyboardWedgeTerminator.ENTER,
        )

    private class ScannerLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
