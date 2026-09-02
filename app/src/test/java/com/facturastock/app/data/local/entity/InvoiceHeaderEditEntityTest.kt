package com.facturastock.app.data.local.entity

import com.facturastock.app.data.local.codec.InvoiceHeaderEditCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvoiceHeaderEditEntityTest {
    @Test
    fun `acepta metadata valida y compara el blob por contenido`() {
        val payload = byteArrayOf(1, 2, 3)
        val first = entity(payload)
        val copy = entity(payload.copyOf())

        assertEquals(first, copy)
        assertEquals(first.hashCode(), copy.hashCode())
        assertArrayEquals(payload, first.payload)
        assertNotEquals(first, copy.copy(revision = 2))
    }

    @Test
    fun `rechaza revision timestamp codec hash y payload invalidos`() {
        assertThrows(IllegalArgumentException::class.java) { entity().copy(revision = -1) }
        assertThrows(IllegalArgumentException::class.java) { entity().copy(updatedAt = -1) }
        assertThrows(IllegalArgumentException::class.java) { entity().copy(payloadCodecVersion = 0) }
        assertThrows(IllegalArgumentException::class.java) { entity().copy(payloadSha256 = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { entity().copy(payload = byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            entity().copy(payload = ByteArray(InvoiceHeaderEditCodec.MAX_PAYLOAD_BYTES + 1))
        }
    }

    private fun entity(payload: ByteArray = byteArrayOf(1)) = InvoiceHeaderEditEntity(
        draftId = "00000000-0000-0000-0000-000000000023",
        revision = 1,
        payloadCodecVersion = InvoiceHeaderEditCodec.VERSION,
        payloadSha256 = "a".repeat(64),
        payload = payload,
        updatedAt = 1_000L,
    )
}
