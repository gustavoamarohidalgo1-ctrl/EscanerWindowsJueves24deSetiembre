package com.facturastock.app.data.spark

import com.facturastock.app.data.sync.FirebaseBackendMode
import com.facturastock.app.domain.error.AccountError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkModeAndIdentityTest {
    @Test
    fun `selector conserva callables y activa Spark solo de forma explicita`() {
        val callables = Any()
        val spark = Any()

        assertSame(
            callables,
            selectForBackendMode(FirebaseBackendMode.CALLABLES, callables, spark),
        )
        assertSame(
            spark,
            selectForBackendMode(FirebaseBackendMode.SPARK_DIRECT, callables, spark),
        )
    }

    @Test
    fun `business id es idempotente entre dispositivos y aislado por cuenta o nombre`() {
        val first = deterministicSparkBusinessId("uid-a", "Bodega Central")
        val replay = deterministicSparkBusinessId("uid-a", "Bodega Central")

        assertEquals(first, replay)
        assertNotEquals(first, deterministicSparkBusinessId("uid-b", "Bodega Central"))
        assertNotEquals(first, deterministicSparkBusinessId("uid-a", "Sucursal Norte"))
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                .matches(first.value),
        )
    }

    @Test
    fun `alta genera root miembro e indice owner con esquemas exactos`() {
        val businessId = deterministicSparkBusinessId("uid-a", "Bodega Central")
        val documents = sparkBusinessDocuments(
            businessId = businessId,
            ownerUid = "uid-a",
            ownerEmail = "owner@example.test",
            displayName = "Bodega Central",
            serverTimestamp = "SERVER_TIME",
        )

        assertEquals(
            setOf("schemaVersion", "businessId", "displayName", "ownerUid", "createdBy", "createdAt"),
            documents.business.keys,
        )
        assertEquals(
            setOf("schemaVersion", "uid", "email", "role", "addedAt", "addedVia", "ownerUid"),
            documents.member.keys,
        )
        assertEquals(
            setOf("schemaVersion", "businessId", "uid", "displayName", "role", "createdAt"),
            documents.membership.keys,
        )
        assertEquals("OWNER", documents.member["role"])
        assertEquals("spark_direct", documents.member["addedVia"])
    }

    @Test
    fun `errores Firestore se cierran sin mensajes remotos`() {
        assertEquals(AccountError.SessionExpired, SparkFirestoreErrorMapper.fromCode("UNAUTHENTICATED"))
        assertEquals(AccountError.PermissionDenied, SparkFirestoreErrorMapper.fromCode("PERMISSION_DENIED"))
        assertEquals(AccountError.NetworkUnavailable, SparkFirestoreErrorMapper.fromCode("UNAVAILABLE"))
        assertEquals(
            AccountError.NetworkUnavailable,
            SparkFirestoreErrorMapper.fromCode("RESOURCE_EXHAUSTED"),
        )
        assertEquals(AccountError.Conflict, SparkFirestoreErrorMapper.fromCode("ABORTED"))
        assertEquals(AccountError.Unexpected, SparkFirestoreErrorMapper.fromCode("mensaje libre"))
    }
}
