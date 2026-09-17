package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountMappersTest {

    @Test
    fun `membresia valida se parsea con rol y nombre`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        val parsed = AccountMappers.membership(
            mapOf(
                "businessId" to businessId.value,
                "displayName" to "Taller Central",
                "role" to "ADMIN",
            ),
        )
        assertEquals(
            CloudMembership(businessId, "Taller Central", BusinessRole.ADMIN),
            parsed,
        )
    }

    @Test
    fun `membresia acepta rol en minusculas y nombre de respaldo`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        val parsed = AccountMappers.membership(
            mapOf("businessId" to businessId.value, "role" to "owner"),
            displayNameFallback = "Taller Central",
        )
        assertEquals(
            CloudMembership(businessId, "Taller Central", BusinessRole.OWNER),
            parsed,
        )
    }

    @Test
    fun `membresia sin nombre queda con nombre nulo`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        val parsed = AccountMappers.membership(
            mapOf("businessId" to businessId.value, "role" to "READER"),
        )
        assertNull(parsed.businessDisplayName)
        assertEquals(BusinessRole.READER, parsed.role)
    }

    @Test
    fun `membresia con negocio o rol invalidos es inesperada`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        assertUnexpected {
            AccountMappers.membership(mapOf("businessId" to RAW_INVALID, "role" to "OWNER"))
        }
        assertUnexpected {
            AccountMappers.membership(mapOf("businessId" to businessId.value, "role" to "SUPERUSER"))
        }
        assertUnexpected { AccountMappers.membership(mapOf("role" to "OWNER")) }
        assertUnexpected { AccountMappers.membership(mapOf("businessId" to businessId.value)) }
    }

    @Test
    fun `lista de membresias exige la clave y entradas bien formadas`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        val parsed = AccountMappers.membershipList(
            mapOf(
                "memberships" to listOf(
                    mapOf("businessId" to businessId.value, "role" to "OPERATOR"),
                ),
            ),
        )
        assertEquals(listOf(CloudMembership(businessId, null, BusinessRole.OPERATOR)), parsed)
        assertEquals(emptyList<CloudMembership>(), AccountMappers.membershipList(mapOf("memberships" to emptyList<Any>())))
        assertUnexpected { AccountMappers.membershipList(emptyMap<String, Any>()) }
        assertUnexpected { AccountMappers.membershipList(mapOf("memberships" to listOf("no-es-mapa"))) }
    }

    @Test
    fun `miembro valido tolera correo ausente`() {
        val withEmail = AccountMappers.member(
            mapOf("uid" to "uid-1", "email" to "a@example.com", "role" to "READER"),
        )
        assertEquals(CloudMember("uid-1", "a@example.com", BusinessRole.READER), withEmail)

        val withoutEmail = AccountMappers.member(
            mapOf("uid" to "uid-2", "email" to null, "role" to "ADMIN"),
        )
        assertEquals(CloudMember("uid-2", null, BusinessRole.ADMIN), withoutEmail)
    }

    @Test
    fun `miembro sin uid o con rol invalido es inesperado`() {
        assertUnexpected { AccountMappers.member(mapOf("email" to "a@example.com", "role" to "ADMIN")) }
        assertUnexpected { AccountMappers.member(mapOf("uid" to "  ", "role" to "ADMIN")) }
        assertUnexpected { AccountMappers.member(mapOf("uid" to "uid-1", "role" to "ROOT")) }
        assertUnexpected { AccountMappers.memberList(mapOf("members" to listOf(7))) }
    }

    @Test
    fun `invitacion valida completa campos desde el payload`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        val parsed = AccountMappers.invitation(
            mapOf(
                "businessId" to businessId.value,
                "displayName" to "Taller Central",
                "email" to "invitado@example.com",
                "role" to "OPERATOR",
                "expiresAtMillis" to 1_800_000_000_000L,
            ),
        )
        assertEquals(
            BusinessInvitation(
                businessId = businessId,
                businessDisplayName = "Taller Central",
                email = "invitado@example.com",
                role = BusinessRole.OPERATOR,
                expiresAtEpochMilli = 1_800_000_000_000L,
            ),
            parsed,
        )
    }

    @Test
    fun `invitacion usa los respaldos de negocio y correo`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        // listBusinessInvitations: el businessId es el del parámetro, no viaja en cada entrada.
        val forBusiness = AccountMappers.invitation(
            mapOf("email" to "invitado@example.com", "role" to "READER", "expiresAtMillis" to 123L),
            businessId = businessId,
        )
        assertEquals(businessId, forBusiness.businessId)

        // listMyInvitations: el correo es el de la cuenta actual.
        val forMe = AccountMappers.invitation(
            mapOf("businessId" to businessId.value, "role" to "READER", "expiresAtMillis" to 123L),
            email = "cuenta@example.com",
        )
        assertEquals("cuenta@example.com", forMe.email)
    }

    @Test
    fun `invitacion con campos invalidos es inesperada`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        // Sin negocio ni respaldo.
        assertUnexpected {
            AccountMappers.invitation(
                mapOf("email" to "a@example.com", "role" to "READER", "expiresAtMillis" to 123L),
            )
        }
        // Sin correo ni respaldo.
        assertUnexpected {
            AccountMappers.invitation(
                mapOf("businessId" to businessId.value, "role" to "READER", "expiresAtMillis" to 123L),
            )
        }
        // Caducidad ausente, cero, negativa o con tipo de texto.
        assertUnexpected {
            AccountMappers.invitation(mapOf("businessId" to businessId.value, "email" to "a@example.com", "role" to "READER"))
        }
        listOf(0L, -5L, "mañana").forEach { rawExpiry ->
            assertUnexpected {
                AccountMappers.invitation(
                    mapOf(
                        "businessId" to businessId.value,
                        "email" to "a@example.com",
                        "role" to "READER",
                        "expiresAtMillis" to rawExpiry,
                    ),
                )
            }
        }
    }

    @Test
    fun `caducidad numerica se normaliza a epoch millis`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        val asInt = AccountMappers.invitation(
            mapOf(
                "businessId" to businessId.value,
                "email" to "a@example.com",
                "role" to "READER",
                "expiresAtMillis" to 123,
            ),
        )
        assertEquals(123L, asInt.expiresAtEpochMilli)
    }

    @Test
    fun `lista de invitaciones exige la clave y entradas bien formadas`() {
        assertEquals(
            emptyList<BusinessInvitation>(),
            AccountMappers.invitationList(mapOf("invitations" to emptyList<Any>())),
        )
        assertUnexpected { AccountMappers.invitationList(emptyMap<String, Any>()) }
        assertUnexpected { AccountMappers.invitationList(mapOf("invitations" to "no-es-lista")) }
    }

    @Test
    fun `resumen de borrado de cuenta valido se parsea con conteo entero`() {
        val businessId = BusinessId.from(UUID.randomUUID())
        val parsed = AccountMappers.accountDeletionSummary(
            mapOf(
                "businessesDeleted" to listOf(businessId.value),
                "membershipsRemoved" to 2,
            ),
        )
        assertEquals(AccountDeletionSummary(listOf(businessId.value), 2), parsed)

        // Sin negocios propios ni membresías también es una respuesta válida (vacia).
        val empty = AccountMappers.accountDeletionSummary(
            mapOf("businessesDeleted" to emptyList<Any>(), "membershipsRemoved" to 0L),
        )
        assertEquals(AccountDeletionSummary(emptyList(), 0), empty)
    }

    @Test
    fun `resumen pendiente no afirma que termino el borrado`() {
        val parsed = AccountMappers.accountDeletionSummary(
            mapOf(
                "businessesDeleted" to emptyList<String>(),
                "membershipsRemoved" to 0L,
                "status" to "PENDING",
            ),
        )
        assertEquals(AccountDeletionSummary(emptyList(), 0, isPending = true), parsed)
        assertUnexpected {
            AccountMappers.accountDeletionSummary(
                mapOf(
                    "businessesDeleted" to emptyList<String>(),
                    "membershipsRemoved" to 0L,
                    "status" to "NOT_RECEIVED",
                ),
            )
        }
    }

    @Test
    fun `resumen de borrado con forma desviada es inesperado`() {
        val businessId = BusinessId.from(UUID.randomUUID()).value
        // Claves ausentes o con tipo distinto.
        assertUnexpected { AccountMappers.accountDeletionSummary(emptyMap<String, Any>()) }
        assertUnexpected {
            AccountMappers.accountDeletionSummary(
                mapOf("businessesDeleted" to "no-es-lista", "membershipsRemoved" to 1),
            )
        }
        assertUnexpected {
            AccountMappers.accountDeletionSummary(
                mapOf("businessesDeleted" to listOf(businessId)),
            )
        }
        // Entradas que no son UUID canónico o ni siquiera texto.
        assertUnexpected {
            AccountMappers.accountDeletionSummary(
                mapOf("businessesDeleted" to listOf(RAW_INVALID), "membershipsRemoved" to 1),
            )
        }
        assertUnexpected {
            AccountMappers.accountDeletionSummary(
                mapOf("businessesDeleted" to listOf(7), "membershipsRemoved" to 1),
            )
        }
        // Contador negativo, fraccionario o de texto.
        listOf(-1, 1.5, "dos").forEach { rawCount ->
            assertUnexpected {
                AccountMappers.accountDeletionSummary(
                    mapOf("businessesDeleted" to listOf(businessId), "membershipsRemoved" to rawCount),
                )
            }
        }
    }

    @Test
    fun `el error nunca contiene el contenido crudo del payload`() {
        val failure = runCatching {
            AccountMappers.membership(mapOf("businessId" to RAW_INVALID, "role" to "OWNER"))
        }.exceptionOrNull()
        assertTrue(failure is AccountException)
        assertFalse(failure?.message.orEmpty().contains(RAW_INVALID))
    }

    private fun assertUnexpected(block: () -> Unit) {
        try {
            block()
            fail("Se esperaba AccountException")
        } catch (expected: AccountException) {
            assertEquals(AccountError.Unexpected, expected.error)
        }
    }

    private companion object {
        const val RAW_INVALID = "negocio-con-dato-sensible-xyz"
    }
}
