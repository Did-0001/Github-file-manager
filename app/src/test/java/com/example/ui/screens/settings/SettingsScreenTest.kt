package com.example.ui.screens.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.RateLimitTracker
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testRepo = SelectedRepoInfo(
        owner = "octocat",
        name = "Hello-World",
        branch = "main",
        defaultBranch = "main",
        isPrivate = false
    )

    private fun scrollToTag(tag: String) {
        composeTestRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag(tag))
    }

    @After
    fun tearDown() {
        RateLimitTracker.remaining = null
        RateLimitTracker.limit = null
        RateLimitTracker.resetEpochSeconds = null
    }

    @Test
    fun unauthenticatedState_displaysNotSignedIn_andSignInButton() {
        var openAuthClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = false,
                    authUser = null,
                    selectedRepo = null,
                    onOpenAuth = { openAuthClicked = true },
                    onSignOut = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_account_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("You are not currently signed in with a GitHub account.").assertIsDisplayed()

        val signInBtn = composeTestRule.onNodeWithTag("settings_sign_in_btn")
        signInBtn.assertIsDisplayed()
        signInBtn.assertTextContains("Sign In with GitHub")
        signInBtn.performClick()
        assertTrue(openAuthClicked)
    }

    @Test
    fun authenticatedState_displaysUsernameAndEmail() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", "octocat@github.com"),
                    selectedRepo = null,
                    onOpenAuth = {},
                    onSignOut = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_account_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat@github.com").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sign_out_button").assertIsDisplayed()
    }

    @Test
    fun signOutFlow_confirmDialogDismissesOnCancel() {
        var signOutInvoked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", "octocat@github.com"),
                    selectedRepo = null,
                    onOpenAuth = {},
                    onSignOut = { signOutInvoked = true },
                    onOpenRepoSelector = {}
                )
            }
        }

        // Open sign out confirm dialog
        composeTestRule.onNodeWithTag("sign_out_button").performClick()

        composeTestRule.onNodeWithTag("sign_out_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign Out of GitHub?").assertIsDisplayed()
        composeTestRule.onNodeWithText("This will purge the encrypted token from Android KeyStore and clear local cached repository metadata.").assertIsDisplayed()

        // Cancel
        composeTestRule.onNodeWithTag("cancel_sign_out_button").performClick()
        composeTestRule.onNodeWithTag("sign_out_confirm_dialog").assertDoesNotExist()
        assertFalse(signOutInvoked)
    }

    @Test
    fun signOutFlow_confirmDialogInvokesOnSignOut() {
        var signOutInvoked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", "octocat@github.com"),
                    selectedRepo = null,
                    onOpenAuth = {},
                    onSignOut = { signOutInvoked = true },
                    onOpenRepoSelector = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("sign_out_button").performClick()
        composeTestRule.onNodeWithTag("confirm_sign_out_button").performClick()

        composeTestRule.onNodeWithTag("sign_out_confirm_dialog").assertDoesNotExist()
        assertTrue(signOutInvoked)
    }

    @Test
    fun unselectedRepo_displaysNoRepoChosen() {
        var repoSelectorClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = null,
                    onOpenAuth = {},
                    onSignOut = {},
                    onOpenRepoSelector = { repoSelectorClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_repo_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("No repository chosen yet.").assertIsDisplayed()

        val changeBtn = composeTestRule.onNodeWithTag("settings_switch_repo_btn")
        changeBtn.assertIsDisplayed()
        changeBtn.performClick()
        assertTrue(repoSelectorClicked)
    }

    @Test
    fun selectedRepo_displaysFullNameAndBranch() {
        var repoSelectorClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    onOpenAuth = {},
                    onSignOut = {},
                    onOpenRepoSelector = { repoSelectorClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_repo_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat/Hello-World").assertIsDisplayed()
        composeTestRule.onNodeWithText("Branch: main • Public").assertIsDisplayed()

        val changeBtn = composeTestRule.onNodeWithTag("settings_switch_repo_btn")
        changeBtn.assertIsDisplayed()
        changeBtn.performClick()
        assertTrue(repoSelectorClicked)
    }

    @Test
    fun securityCard_displaysHardwareBackedKeyStoreInfo() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    onOpenAuth = {},
                    onSignOut = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        scrollToTag("settings_security_card")
        composeTestRule.onNodeWithTag("settings_security_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hardware-Backed Security").assertIsDisplayed()
    }

    @Test
    fun rateLimitCard_displaysRemainingCount() {
        RateLimitTracker.remaining = 4750
        RateLimitTracker.limit = 5000

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    onOpenAuth = {},
                    onSignOut = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        scrollToTag("settings_rate_limit_card")
        composeTestRule.onNodeWithTag("settings_rate_limit_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("GitHub API Rate Limits").assertIsDisplayed()
        composeTestRule.onNodeWithText("4750 of 5000 requests remaining in this cycle.").assertIsDisplayed()
    }
}
