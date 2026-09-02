package com.facturastock.app.feature.account.members

import androidx.compose.runtime.Composable
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID

@ThemePreviews
@Composable
private fun MembersScreenOwnerPreview() {
    FacturaStockTheme {
        MembersScreen(
            state = previewState(myRole = BusinessRole.OWNER),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun MembersScreenReaderPreview() {
    FacturaStockTheme {
        MembersScreen(
            state = previewState(myRole = BusinessRole.READER),
            onAction = {},
        )
    }
}

private fun previewState(myRole: BusinessRole): MembersContract.State {
    val localBusinessId = BusinessId.from(
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
    )
    val cloudBusinessId = BusinessId.from(
        UUID.fromString("123e4567-e89b-42d3-a456-426614174001"),
    )
    return MembersContract.State(
        session = AccountSession.Active(
            uid = "uid-propio",
            email = "duena@bodega.pe",
            link = CloudBusinessLink(
                localBusinessId = localBusinessId,
                cloudBusinessId = cloudBusinessId,
                role = BusinessRole.OWNER,
            ),
        ),
        members = listOf(
            CloudMember(uid = "uid-propio", email = "duena@bodega.pe", role = myRole),
            CloudMember(uid = "uid-socia", email = "socia@bodega.pe", role = BusinessRole.OWNER),
            CloudMember(uid = "uid-cajero", email = "cajero@bodega.pe", role = BusinessRole.OPERATOR),
        ),
        pendingInvitations = listOf(
            BusinessInvitation(
                businessId = cloudBusinessId,
                businessDisplayName = "Bodega Vista Alegre",
                email = "nuevo@bodega.pe",
                role = BusinessRole.READER,
                expiresAtEpochMilli = 1786900000000L,
            ),
        ),
    )
}
