package com.facturastock.app.data.account

import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.testing.FakeAccountRepository
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CloudPurchaseOverrideAuthorizationRepositoryTest {
    @Test
    fun `usa uid real y mapea owner a owner`() = runTest {
        val repository = repositoryFor(BusinessRole.OWNER)

        assertEquals(
            PurchaseOverrideActor(UID, PurchaseOverrideRole.OWNER),
            repository.currentActor(LOCAL_BUSINESS_ID),
        )
    }

    @Test
    fun `mapea admin a manager`() = runTest {
        val repository = repositoryFor(BusinessRole.ADMIN)

        assertEquals(
            PurchaseOverrideActor(UID, PurchaseOverrideRole.MANAGER),
            repository.currentActor(LOCAL_BUSINESS_ID),
        )
    }

    @Test
    fun `operator y reader no pueden autorizar excepcion`() = runTest {
        listOf(BusinessRole.OPERATOR, BusinessRole.READER).forEach { businessRole ->
            val actor = repositoryFor(businessRole).currentActor(LOCAL_BUSINESS_ID)

            assertEquals(PurchaseOverrideRole.OPERATOR, actor?.role)
            assertFalse(requireNotNull(actor).canOverrideDuplicate)
        }
    }

    @Test
    fun `sin sesion activa enlace o negocio coincidente falla cerrado`() = runTest {
        val signedOut = CloudPurchaseOverrideAuthorizationRepository(FakeAccountRepository())
        val withoutLink = CloudPurchaseOverrideAuthorizationRepository(
            FakeAccountRepository(AccountSession.Active(UID, EMAIL, null)),
        )
        val linked = repositoryFor(BusinessRole.OWNER)

        assertNull(signedOut.currentActor(LOCAL_BUSINESS_ID))
        assertNull(withoutLink.currentActor(LOCAL_BUSINESS_ID))
        assertNull(linked.currentActor(OTHER_LOCAL_BUSINESS_ID))
    }

    private fun repositoryFor(role: BusinessRole): CloudPurchaseOverrideAuthorizationRepository =
        CloudPurchaseOverrideAuthorizationRepository(
            FakeAccountRepository(
                AccountSession.Active(
                    uid = UID,
                    email = EMAIL,
                    link = CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = CLOUD_BUSINESS_ID,
                        role = role,
                    ),
                ),
            ),
        )

    private companion object {
        const val UID = "firebase-uid-123"
        const val EMAIL = "owner@example.test"
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
        )
        val OTHER_LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
        )
    }
}
