package com.facturastock.app.navigation

import com.facturastock.app.feature.root.GateState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacturaStockAppStartupTest {
    @Test
    fun `loading y unavailable no habilitan el DraftFlowViewModel`() {
        assertFalse(shouldCreateDraftFlowViewModel(GateState.Loading, true))
        assertFalse(shouldCreateDraftFlowViewModel(GateState.Unavailable, true))
    }

    @Test
    fun `onboarding no abre DraftFlow y el negocio configurado si lo habilita`() {
        assertFalse(shouldCreateDraftFlowViewModel(GateState.Incomplete, true))
        assertTrue(shouldCreateDraftFlowViewModel(GateState.Complete, true))
    }

    @Test
    fun `las pruebas sin inyeccion nunca crean el DraftFlowViewModel`() {
        listOf(
            GateState.Loading,
            GateState.Unavailable,
            GateState.Incomplete,
            GateState.Complete,
        ).forEach { state ->
            assertFalse(shouldCreateDraftFlowViewModel(state, false))
        }
    }

    @Test
    fun `el alias antiguo espera la redireccion y ventas usa su primer frame`() {
        assertFalse(shouldSignalDeferredStartupFromDestination(null))
        assertFalse(shouldSignalDeferredStartupFromDestination(AppRoutes.HOME))
        assertTrue(shouldSignalDeferredStartupFromDestination(AppRoutes.SALES))
        assertTrue(shouldSignalDeferredStartupFromDestination(AppRoutes.ONBOARDING))
        assertTrue(shouldSignalDeferredStartupFromDestination(AppRoutes.PURCHASES))
    }
}
