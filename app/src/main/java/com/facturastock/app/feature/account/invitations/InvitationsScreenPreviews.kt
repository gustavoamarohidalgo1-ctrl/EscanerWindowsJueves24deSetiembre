package com.facturastock.app.feature.account.invitations

import androidx.compose.runtime.Composable
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID

@ThemePreviews
@Composable
private fun InvitationsScreenListPreview() {
    FacturaStockTheme {
        InvitationsScreen(
            state = InvitationsContract.State(
                invitations = listOf(
                    BusinessInvitation(
                        businessId = BusinessId.from(
                            UUID.fromString("123e4567-e89b-42d3-a456-426614174001"),
                        ),
                        businessDisplayName = "Bodega Vista Alegre",
                        email = "duena@bodega.pe",
                        role = BusinessRole.ADMIN,
                        expiresAtEpochMilli = 1786900000000L,
                    ),
                    BusinessInvitation(
                        businessId = BusinessId.from(
                            UUID.fromString("123e4567-e89b-42d3-a456-426614174002"),
                        ),
                        businessDisplayName = null,
                        email = "duena@bodega.pe",
                        role = BusinessRole.READER,
                        expiresAtEpochMilli = 1787000000000L,
                    ),
                ),
            ),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun InvitationsScreenEmptyPreview() {
    FacturaStockTheme {
        InvitationsScreen(
            state = InvitationsContract.State(invitations = emptyList()),
            onAction = {},
        )
    }
}
