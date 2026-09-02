package com.facturastock.app.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStartupTest {
    @Test
    fun `mantenimiento espera dashboard o error recuperable`() {
        assertFalse(shouldSignalHomeContentReady(hasDashboard = false, hasFailure = false))
        assertTrue(shouldSignalHomeContentReady(hasDashboard = true, hasFailure = false))
        assertTrue(shouldSignalHomeContentReady(hasDashboard = false, hasFailure = true))
        assertTrue(shouldSignalHomeContentReady(hasDashboard = true, hasFailure = true))
    }
}
