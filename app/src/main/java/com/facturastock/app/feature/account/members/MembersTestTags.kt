package com.facturastock.app.feature.account.members

object MembersTestTags {
    const val INVITE_ACTION = "members:invite-action"

    fun memberRemoveTag(uid: String): String = "members:remove:$uid"
}
