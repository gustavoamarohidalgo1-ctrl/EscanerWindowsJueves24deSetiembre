package com.facturastock.app.data.repository

import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.id.BusinessId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOwnerPurchaseOverrideAuthorizationRepositoryTest {
    @Test
    fun `flavor local conserva propietario explicito`() = runTest {
        val businessId = BusinessId.from(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
        )

        assertEquals(
            PurchaseOverrideActor(businessId.value, PurchaseOverrideRole.OWNER),
            LocalOwnerPurchaseOverrideAuthorizationRepository().currentActor(businessId),
        )
    }
}
