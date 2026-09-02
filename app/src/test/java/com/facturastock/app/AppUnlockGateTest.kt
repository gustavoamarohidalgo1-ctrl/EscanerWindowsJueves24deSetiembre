package com.facturastock.app

import app.cash.turbine.test
import com.facturastock.app.testing.FakeAppConfigurationRepository
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUnlockGateTest {
    @Test
    fun `no autentica antes de resumed ni durante otro prompt`() {
        assertFalse(
            AppUnlockGate.shouldAuthenticate(
                lockConfigured = true,
                sessionUnlocked = false,
                activityResumed = false,
                authenticationInProgress = false,
            ),
        )
        assertFalse(
            AppUnlockGate.shouldAuthenticate(
                lockConfigured = true,
                sessionUnlocked = false,
                activityResumed = true,
                authenticationInProgress = true,
            ),
        )
    }

    @Test
    fun `autentica al volver resumed solo si la sesion sigue bloqueada`() {
        assertTrue(
            AppUnlockGate.shouldAuthenticate(
                lockConfigured = true,
                sessionUnlocked = false,
                activityResumed = true,
                authenticationInProgress = false,
            ),
        )
        assertFalse(
            AppUnlockGate.shouldAuthenticate(
                lockConfigured = true,
                sessionUnlocked = true,
                activityResumed = true,
                authenticationInProgress = false,
            ),
        )
    }

    @Test
    fun `el contenido pesado solo se monta tras el primer desbloqueo`() {
        assertFalse(shouldMountAppContent(AppAccessState.CHECKING, contentEverMounted = false))
        assertFalse(shouldMountAppContent(AppAccessState.LOCKED, contentEverMounted = false))
        assertFalse(
            shouldMountAppContent(
                AppAccessState.CONFIGURATION_UNAVAILABLE,
                contentEverMounted = true,
            ),
        )
        assertTrue(shouldMountAppContent(AppAccessState.UNLOCKED, contentEverMounted = true))
        assertTrue(shouldMountAppContent(AppAccessState.LOCKED, contentEverMounted = true))
    }

    @Test
    fun `un deep link encolado no se entrega mientras la sesion esta bloqueada`() {
        assertFalse(shouldExposeInternalDeepLink(AppAccessState.CHECKING))
        assertFalse(shouldExposeInternalDeepLink(AppAccessState.CONFIGURATION_UNAVAILABLE))
        assertFalse(shouldExposeInternalDeepLink(AppAccessState.LOCKED))
        assertTrue(shouldExposeInternalDeepLink(AppAccessState.UNLOCKED))
    }

    @Test
    fun `un fallo de configuracion cierra el acceso y un retry reinicia la fuente`() = runTest {
        val repository = FakeAppConfigurationRepository().apply {
            observeFailure = IOException("fallo de lectura simulado")
        }
        val retries = MutableStateFlow(0L)

        observeAppLockConfigurationResults(repository, retries).test {
            assertEquals(AppLockConfigurationResult.Unavailable, awaitItem())

            repository.observeFailure = null
            retries.value = 1L

            assertEquals(
                AppLockConfigurationResult.Available(biometricLockEnabled = false),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `un solo gesto de retry reinicia la compuerta raiz y AppGate`() {
        var sharedObservationRetries = 0
        var appGateRetries = 0
        var rootGateRetries = 0

        retrySharedConfigurationSources(
            retrySharedObservation = { sharedObservationRetries += 1 },
            retryAppGate = { appGateRetries += 1 },
            retryRootGate = { rootGateRetries += 1 },
        )

        assertEquals(1, sharedObservationRetries)
        assertEquals(1, appGateRetries)
        assertEquals(1, rootGateRetries)
    }
}
