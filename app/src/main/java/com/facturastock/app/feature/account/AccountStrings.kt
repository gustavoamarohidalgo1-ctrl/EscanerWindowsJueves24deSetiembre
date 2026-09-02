package com.facturastock.app.feature.account

import androidx.annotation.StringRes
import com.facturastock.app.R
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.model.BusinessRole

/**
 * Mapeo cerrado de errores de cuenta a mensajes visibles. Los errores del puerto nunca
 * transportan detalle sensible; cualquier error ajeno al dominio de cuenta cae en el
 * mensaje genérico.
 */
@StringRes
fun accountErrorMessageRes(error: FacturaStockError): Int = when (error) {
    AccountError.Unavailable -> R.string.account_error_unavailable
    AccountError.NotAuthenticated -> R.string.account_error_not_authenticated
    AccountError.InvalidCredentials -> R.string.account_error_invalid_credentials
    AccountError.InvalidEmail -> R.string.account_error_invalid_email
    AccountError.EmailInUse -> R.string.account_error_email_in_use
    AccountError.WeakPassword -> R.string.account_error_weak_password
    AccountError.EmailNotVerified -> R.string.account_error_email_not_verified
    AccountError.SessionExpired -> R.string.account_error_session_expired
    AccountError.PermissionDenied -> R.string.account_error_permission_denied
    AccountError.NotFound -> R.string.account_error_not_found
    AccountError.Conflict -> R.string.account_error_conflict
    AccountError.InvitationExpired -> R.string.account_error_invitation_expired
    AccountError.LastOwnerRequired -> R.string.account_error_last_owner
    AccountError.DeletionScopeTooLarge -> R.string.account_error_deletion_scope_too_large
    AccountError.CloudBusinessAlreadyBound -> R.string.account_error_cloud_business_bound
    AccountError.LegacySyncDestinationUnknown ->
        R.string.account_error_legacy_sync_destination_unknown
    AccountError.NetworkUnavailable -> R.string.account_error_network
    else -> R.string.account_error_generic
}

/** Etiqueta visible de un rol de negocio en la nube. */
@StringRes
fun businessRoleLabelRes(role: BusinessRole): Int = when (role) {
    BusinessRole.OWNER -> R.string.role_owner
    BusinessRole.ADMIN -> R.string.role_admin
    BusinessRole.OPERATOR -> R.string.role_operator
    BusinessRole.READER -> R.string.role_reader
}
