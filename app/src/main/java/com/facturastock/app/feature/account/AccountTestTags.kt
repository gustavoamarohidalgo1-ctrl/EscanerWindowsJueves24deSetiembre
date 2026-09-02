package com.facturastock.app.feature.account

object AccountTestTags {
    const val SIGN_OUT_ACTION = "account:sign-out-action"
    const val SIGN_OUT_DIALOG = "account:sign-out-dialog"
    const val OPEN_MEMBERS = "account:open-members"
    const val OPEN_INVITATIONS = "account:open-invitations"
    const val DELETE_ACCOUNT_ACTION = "account:delete-account-action"
    const val DELETE_ACCOUNT_DIALOG = "account:delete-account-dialog"
    const val DELETE_ACCOUNT_PASSWORD = "account:delete-account-password"
    const val DELETE_ACCOUNT_CLEANUP_RETRY = "account:delete-account-cleanup-retry"

    fun membership(businessId: String): String = "account:membership:$businessId"
}
