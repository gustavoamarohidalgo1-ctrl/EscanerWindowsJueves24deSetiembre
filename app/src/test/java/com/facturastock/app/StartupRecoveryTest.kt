package com.facturastock.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StartupRecoveryTest {
    @Test
    fun `la supresion del harness solo existe en variantes no distribuibles`() {
        assertTrue(allowsDeferredStartupHarnessSuppression("benchmark"))
        assertTrue(allowsDeferredStartupHarnessSuppression("profile"))
        assertFalse(allowsDeferredStartupHarnessSuppression("debug"))
        assertFalse(allowsDeferredStartupHarnessSuppression("release"))
        assertFalse(allowsDeferredStartupHarnessSuppression(""))
    }

    @Test
    fun `el trabajo diferido no corre antes de la senal y solo arranca una vez`() {
        val deferredStartup = DeferredStartupRunOnce()
        var runs = 0

        assertEquals(0, runs)
        assertTrue(deferredStartup.run { runs += 1 })
        assertFalse(deferredStartup.run { runs += 1 })
        assertFalse(deferredStartup.run { runs += 1 })

        assertEquals(1, runs)
    }

    @Test
    fun `cada fallo ordinario conserva el orden y no omite las demas redes de seguridad`() =
        runTest {
            val expected = listOf("cleanup", "recover", "regular", "privacy")
            expected.indices.forEach { failingIndex ->
                val calls = mutableListOf<String>()
                val steps: List<suspend () -> Unit> = expected.mapIndexed { index, name ->
                    {
                        calls += name
                        if (index == failingIndex) throw IllegalStateException("fallo $name")
                    }
                }

                runIndependentStartupRecovery(
                    cleanOrphanedImports = steps[0],
                    recoverOutboxClaims = steps[1],
                    enqueueRegularBackup = steps[2],
                    enqueuePrivacyPurge = steps[3],
                )

                assertEquals("fallo en índice $failingIndex", expected, calls)
            }
        }

    @Test
    fun `cancelacion estructurada detiene la secuencia y se propaga`() = runTest {
        val calls = mutableListOf<String>()

        try {
            runIndependentStartupRecovery(
                cleanOrphanedImports = {
                    calls += "cleanup"
                    throw CancellationException("cancelado")
                },
                recoverOutboxClaims = { calls += "recover" },
                enqueueRegularBackup = { calls += "regular" },
                enqueuePrivacyPurge = { calls += "privacy" },
            )
            fail("La cancelación debía propagarse")
        } catch (_: CancellationException) {
            // Esperado: no se transforma en un fallo recuperable.
        }

        assertEquals(listOf("cleanup"), calls)
    }
}
