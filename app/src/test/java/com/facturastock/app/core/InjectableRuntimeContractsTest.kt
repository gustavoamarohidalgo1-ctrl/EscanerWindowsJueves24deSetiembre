package com.facturastock.app.core

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InjectableRuntimeContractsTest {
    @Test
    fun `reloj y uuid pueden reemplazarse de forma determinista`() {
        val expectedTime = Instant.parse("2026-08-07T12:00:00Z")
        val expectedUuid = UUID.fromString("85bf7698-d48e-4b88-84ba-c66cfbe8f469")
        val clock = AppClock { expectedTime }
        val uuidGenerator = UuidGenerator { expectedUuid }

        assertEquals(expectedTime, clock.now())
        assertEquals(expectedUuid, uuidGenerator.newUuid())
    }

    @Test
    fun `dispatchers son sustituibles en pruebas`() {
        val testDispatcher = StandardTestDispatcher()
        val provider = object : DispatcherProvider {
            override val io = testDispatcher
            override val default = testDispatcher
            override val main = testDispatcher
        }

        assertSame(testDispatcher, provider.io)
        assertSame(testDispatcher, provider.default)
        assertSame(testDispatcher, provider.main)
    }
}
