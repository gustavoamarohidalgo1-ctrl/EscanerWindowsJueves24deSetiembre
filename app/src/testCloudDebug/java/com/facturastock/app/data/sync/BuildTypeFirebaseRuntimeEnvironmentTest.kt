package com.facturastock.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BuildTypeFirebaseRuntimeEnvironmentTest {
    @Test
    fun `cloudDebug conserva la configuracion demo solo en su source set`() {
        val config = BuildTypeFirebaseRuntimeEnvironment().config

        assertNotNull(config)
        assertEquals("demo-facturastock", config.projectId)
        assertEquals("AIza00000000000000000000000000000000000", config.apiKey)
        assertEquals("demo-facturastock.appspot.com", config.storageBucket)
    }
}
