package com.facturastock.app.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun signedOutShowsEmailAndPasswordFields() {
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = AccountContract.State(session = AccountSession.SignedOut),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.account_email_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.account_password_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.account_sign_in_action))
            .assertIsDisplayed()
    }

    @Test
    fun activeSessionShowsMembershipsAndDispatchesActions() {
        val actions = mutableListOf<AccountContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = activeState(
                        memberships = listOf(
                            CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
                            CloudMembership(OTHER_CLOUD_BUSINESS_ID, "Otro negocio", BusinessRole.ADMIN),
                        ),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.account_memberships_section))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AccountTestTags.membership(CLOUD_BUSINESS_ID.value))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AccountTestTags.membership(OTHER_CLOUD_BUSINESS_ID.value))
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag(AccountTestTags.OPEN_INVITATIONS)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(AccountContract.Action.OpenInvitations), actions)
        }
    }

    @Test
    fun signOutDialogStatesLocalPurchasesAreKept() {
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = activeState().copy(showSignOutDialog = true),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(AccountTestTags.SIGN_OUT_DIALOG)
            .assertIsDisplayed()
        // La promesa clave: cerrar sesión nunca borra las compras ni los borradores locales.
        composeRule.onNodeWithText(context.getString(R.string.account_sign_out_dialog_message))
            .assertIsDisplayed()
    }

    @Test
    fun signOutActionRequestsConfirmation() {
        val actions = mutableListOf<AccountContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = activeState(),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(AccountTestTags.SIGN_OUT_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(AccountContract.Action.SignOutRequested), actions)
        }
    }

    @Test
    fun accountDeletionActionRequestsConfirmation() {
        val actions = mutableListOf<AccountContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = activeState(),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(AccountTestTags.DELETE_ACCOUNT_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(AccountContract.Action.DeleteAccountRequested), actions)
        }
    }

    @Test
    fun accountDeletionDialogExplainsSharedAndLocalDataSemantics() {
        val actions = mutableListOf<AccountContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = activeState().copy(showAccountDeletionDialog = true),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(AccountTestTags.DELETE_ACCOUNT_DIALOG)
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.account_delete_dialog_message))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AccountTestTags.DELETE_ACCOUNT_PASSWORD)
            .assertIsDisplayed()
            .performTextInput("correct-secret")
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    AccountContract.Action.AccountDeletionPasswordChanged("correct-secret"),
                ),
                actions,
            )
        }
    }

    @Test
    fun accountDeletionDialogAllowsConfirmationAfterPasswordIsPresent() {
        val actions = mutableListOf<AccountContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = activeState().copy(
                        showAccountDeletionDialog = true,
                        accountDeletionPassword = "correct-secret",
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.account_delete_confirm))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(AccountContract.Action.DeleteAccountConfirmed), actions)
        }
    }

    @Test
    fun awaitingVerificationStillOffersAccountDeletion() {
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = AccountContract.State(
                        session = AccountSession.AwaitingVerification(
                            uid = "uid-ana",
                            email = "ana@example.com",
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(AccountTestTags.DELETE_ACCOUNT_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun deletedAccountCleanupOffersASeparateRetry() {
        val actions = mutableListOf<AccountContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                AccountScreen(
                    state = activeState().copy(
                        deletionPendingSignOut = true,
                        accountDeletionFailed = true,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(AccountTestTags.DELETE_ACCOUNT_CLEANUP_RETRY)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(AccountContract.Action.RetryDeletedAccountCleanup), actions)
        }
    }

    private fun activeState(
        memberships: List<CloudMembership> = emptyList(),
    ): AccountContract.State = AccountContract.State(
        session = AccountSession.Active(
            uid = "uid-ana",
            email = "ana@example.com",
            link = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                role = BusinessRole.OWNER,
            ),
        ),
        activeBusinessId = LOCAL_BUSINESS_ID,
        memberships = memberships,
    )

    private companion object {
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
        )
        val OTHER_CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
        )
    }
}
