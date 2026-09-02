package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccountTest {
    private val localBusiness = BusinessId.parse("11111111-1111-4111-8111-111111111111")!!
    private val cloudBusiness = BusinessId.parse("22222222-2222-4222-8222-222222222222")!!
    private val otherLocalBusiness = BusinessId.parse("33333333-3333-4333-8333-333333333333")!!

    @Test
    fun `matriz de permisos por rol`() {
        // Solo READER no puede respaldar; solo OWNER/ADMIN anulan y gestionan miembros.
        assertTrue(BusinessRole.OWNER.canSyncPurchases)
        assertTrue(BusinessRole.ADMIN.canSyncPurchases)
        assertTrue(BusinessRole.OPERATOR.canSyncPurchases)
        assertFalse(BusinessRole.READER.canSyncPurchases)

        assertTrue(BusinessRole.OWNER.canVoidSyncedPurchases)
        assertTrue(BusinessRole.ADMIN.canVoidSyncedPurchases)
        assertFalse(BusinessRole.OPERATOR.canVoidSyncedPurchases)
        assertFalse(BusinessRole.READER.canVoidSyncedPurchases)

        assertTrue(BusinessRole.OWNER.canManageMembers)
        assertTrue(BusinessRole.ADMIN.canManageMembers)
        assertFalse(BusinessRole.OPERATOR.canManageMembers)
        assertFalse(BusinessRole.READER.canManageMembers)
    }

    @Test
    fun `parse de rol acepta mayúsculas y minúsculas y rechaza desconocidos`() {
        assertEquals(BusinessRole.OWNER, BusinessRole.parse("OWNER"))
        assertEquals(BusinessRole.ADMIN, BusinessRole.parse("admin"))
        assertEquals(BusinessRole.OPERATOR, BusinessRole.parse(" Operator "))
        assertEquals(BusinessRole.READER, BusinessRole.parse("reader"))
        assertNull(BusinessRole.parse("SUPERUSER"))
        assertNull(BusinessRole.parse(""))
        assertNull(BusinessRole.parse(null))
    }

    @Test
    fun `enlace sin configurar resuelve NotLinked`() {
        assertEquals(
            CloudBusinessResolution.NotLinked,
            resolveCloudBusiness(null, localBusiness),
        )
    }

    @Test
    fun `enlace de la misma empresa local resuelve el negocio nube`() {
        val link = CloudBusinessLink(
            localBusinessId = localBusiness,
            cloudBusinessId = cloudBusiness,
            role = BusinessRole.OWNER,
        )

        assertEquals(
            CloudBusinessResolution.Linked(cloudBusiness),
            resolveCloudBusiness(link, localBusiness),
        )
    }

    @Test
    fun `enlace de otra empresa local es Mismatch y nunca reencamina`() {
        val link = CloudBusinessLink(
            localBusinessId = localBusiness,
            cloudBusinessId = cloudBusiness,
            role = BusinessRole.OWNER,
        )

        assertEquals(
            CloudBusinessResolution.Mismatch,
            resolveCloudBusiness(link, otherLocalBusiness),
        )
    }

    @Test
    fun `sesión activa exige uid y email no vacíos`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountSession.Active(uid = "", email = "cuenta@example.com", link = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountSession.Active(uid = "uid-1", email = "", link = null)
        }
    }

    @Test
    fun `invitación exige email y caducidad positiva`() {
        assertThrows(IllegalArgumentException::class.java) {
            BusinessInvitation(
                businessId = cloudBusiness,
                businessDisplayName = null,
                email = " ",
                role = BusinessRole.OPERATOR,
                expiresAtEpochMilli = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BusinessInvitation(
                businessId = cloudBusiness,
                businessDisplayName = null,
                email = "invitado@example.com",
                role = BusinessRole.OPERATOR,
                expiresAtEpochMilli = 0L,
            )
        }
    }
}
