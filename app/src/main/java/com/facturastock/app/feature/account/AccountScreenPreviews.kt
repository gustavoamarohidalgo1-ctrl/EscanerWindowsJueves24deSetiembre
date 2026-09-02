package com.facturastock.app.feature.account

import androidx.compose.runtime.Composable
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID

@ThemePreviews
@Composable
private fun AccountScreenActivePreview() {
    FacturaStockTheme {
        AccountScreen(
            state = previewActiveState(),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun AccountScreenSignedOutPreview() {
    FacturaStockTheme {
        AccountScreen(
            state = AccountContract.State(
                session = AccountSession.SignedOut,
                activeBusinessId = previewLocalBusinessId,
            ),
            onAction = {},
        )
    }
}

private val previewLocalBusinessId: BusinessId = BusinessId.from(
    UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
)

private val previewCloudBusinessId: BusinessId = BusinessId.from(
    UUID.fromString("123e4567-e89b-42d3-a456-426614174001"),
)

private fun previewActiveState(): AccountContract.State = AccountContract.State(
    session = AccountSession.Active(
        uid = "uid-preview",
        email = "duena@bodega.pe",
        link = CloudBusinessLink(
            localBusinessId = previewLocalBusinessId,
            cloudBusinessId = previewCloudBusinessId,
            role = BusinessRole.OWNER,
        ),
    ),
    activeBusinessId = previewLocalBusinessId,
    memberships = listOf(
        CloudMembership(
            businessId = previewCloudBusinessId,
            businessDisplayName = "Bodega Vista Alegre",
            role = BusinessRole.OWNER,
        ),
        CloudMembership(
            businessId = BusinessId.from(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174002"),
            ),
            businessDisplayName = "Minimarket El Sol",
            role = BusinessRole.OPERATOR,
        ),
    ),
)
