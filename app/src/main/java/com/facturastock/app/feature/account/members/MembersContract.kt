package com.facturastock.app.feature.account.members

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object MembersContract {
    @Immutable
    data class State(
        val session: AccountSession? = null,
        val members: List<CloudMember>? = null,
        val pendingInvitations: List<BusinessInvitation> = emptyList(),
        val isLoading: Boolean = false,
        val inviteEmail: String = "",
        val inviteRole: BusinessRole = BusinessRole.OPERATOR,
        val isInviting: Boolean = false,
        val roleEditUid: String? = null,
        val roleEditSelection: BusinessRole? = null,
        val isChangingRole: Boolean = false,
        val removeCandidate: CloudMember? = null,
        val isRemoving: Boolean = false,
        val feedback: Feedback? = null,
        val failure: AccountError? = null,
        /** Fallo del observador de sesión, separado de la carga de miembros. */
        val initialLoadFailure: AccountError? = null,
    ) : UiState {
        val link: CloudBusinessLink?
            get() = (session as? AccountSession.Active)?.link

        val myUid: String?
            get() = (session as? AccountSession.Active)?.uid

        val myRole: BusinessRole?
            get() = members?.firstOrNull { it.uid == myUid }?.role

        val canManage: Boolean
            get() = myRole?.canManageMembers == true

        val isInviteEmailValid: Boolean
            get() = inviteEmail.trim().let {
                it.isNotEmpty() && it.length <= INVITE_EMAIL_MAX_LENGTH && it.contains('@')
            }

        val canInvite: Boolean
            get() = canManage && !isInviting && isInviteEmailValid

        /** Roles que puedo otorgar: un ADMIN nunca ofrece OWNER. */
        val grantableRoles: List<BusinessRole>
            get() = when (myRole) {
                BusinessRole.OWNER -> BusinessRole.entries
                BusinessRole.ADMIN -> listOf(
                    BusinessRole.ADMIN,
                    BusinessRole.OPERATOR,
                    BusinessRole.READER,
                )

                else -> emptyList()
            }

        /**
         * La UI ni siquiera ofrece acciones de gestión sobre: uno mismo, los OWNER cuando soy
         * ADMIN, y el último OWNER visible. El servidor vuelve a rechazarlas de todas formas.
         */
        fun canActOn(member: CloudMember): Boolean {
            val role = myRole ?: return false
            if (!role.canManageMembers) return false
            if (member.uid == myUid) return false
            if (role == BusinessRole.ADMIN && member.role == BusinessRole.OWNER) return false
            val ownerCount = members.orEmpty().count { it.role == BusinessRole.OWNER }
            if (member.role == BusinessRole.OWNER && ownerCount <= 1) return false
            return true
        }
    }

    enum class Feedback {
        INVITED,
        ROLE_UPDATED,
        MEMBER_REMOVED,
    }

    sealed interface Action : UiAction {
        data class InviteEmailChanged(val value: String) : Action
        data class InviteRoleSelected(val role: BusinessRole) : Action
        data object SendInvitation : Action
        data class ChangeRoleRequested(val member: CloudMember) : Action
        data class ChangeRoleSelected(val role: BusinessRole) : Action
        data object ChangeRoleConfirmed : Action
        data object ChangeRoleDismissed : Action
        data class RemoveRequested(val member: CloudMember) : Action
        data object RemoveConfirmed : Action
        data object RemoveDismissed : Action
        data object Retry : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data object Back : Effect
    }

    const val INVITE_EMAIL_MAX_LENGTH: Int = 254
}
