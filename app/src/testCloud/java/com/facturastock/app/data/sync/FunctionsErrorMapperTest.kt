package com.facturastock.app.data.sync

import com.facturastock.app.domain.repository.BackupTransportResult
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionsErrorMapperTest {

    @Test
    fun `fallos recuperables reintentan con codigo cerrado`() {
        listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "RESOURCE_EXHAUSTED").forEach { code ->
            assertEquals(BackupTransportResult.TransientFailure(code), FunctionsErrorMapper.fromCode(code))
        }
    }

    @Test
    fun `estados incompatibles del destino son conflicto`() {
        listOf("ALREADY_EXISTS", "ABORTED", "FAILED_PRECONDITION").forEach { code ->
            assertEquals(BackupTransportResult.Conflict(code), FunctionsErrorMapper.fromCode(code))
        }
    }

    @Test
    fun `fallos definitivos quedan para accion manual`() {
        listOf("INVALID_ARGUMENT", "PERMISSION_DENIED", "UNAUTHENTICATED", "NOT_FOUND").forEach { code ->
            assertEquals(BackupTransportResult.PermanentFailure(code), FunctionsErrorMapper.fromCode(code))
        }
    }

    @Test
    fun `corte de red es transitorio y las razones nunca llevan detalle`() {
        assertEquals(
            BackupTransportResult.TransientFailure("NETWORK_UNAVAILABLE"),
            FunctionsErrorMapper.fromException(IOException("timeout en socket 10.0.2.2:5001")),
        )
        assertEquals(
            BackupTransportResult.TransientFailure("UNEXPECTED_TRANSPORT_ERROR"),
            FunctionsErrorMapper.fromException(IllegalStateException("detalle interno")),
        )
    }

    @Test
    fun `el codigo del callable viaja cerrado desde el SDK`() {
        // FirebaseFunctionsException tiene constructor interno; el transporte extrae
        // `code.name` y pasa por el mismo mapa público.
        assertEquals(
            BackupTransportResult.Conflict("FAILED_PRECONDITION"),
            FunctionsErrorMapper.fromCode(FirebaseFunctionsException.Code.FAILED_PRECONDITION.name),
        )
    }

    @Test
    fun `target de override aun no sincronizado conserva causa y reintenta`() {
        assertEquals(
            BackupTransportResult.TransientFailure("DUPLICATE_TARGET_NOT_SYNCED"),
            FunctionsErrorMapper.fromCode("UNAVAILABLE", "DUPLICATE_TARGET_NOT_SYNCED"),
        )
        assertEquals(
            BackupTransportResult.TransientFailure("UNAVAILABLE"),
            FunctionsErrorMapper.fromCode("UNAVAILABLE", "detalle-no-permitido"),
        )
    }
}
