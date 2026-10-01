package com.example.ui.screens.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
import com.example.data.local.entity.TransferEntity
import com.example.data.remote.RateLimitTracker
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeScreenTest {

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
        composeTestRule.onNodeWithTag("home_lazy_column").performScrollToNode(hasTestTag(tag))
    }

    @After
    fun tearDown() {
        RateLimitTracker.remaining = null
        RateLimitTracker.limit = null
        RateLimitTracker.resetEpochSeconds = null
    }

    @Test
    fun unauthenticatedState_displaysSignInAndHelpText() {
        var authClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = false,
                    authUser = null,
                    selectedRepo = null,
                    recentTransfers = emptyList(),
                    onOpenAuth = { authClicked = true },
                    onOpenRepoSelector = {},
                    onNavigateUpload = {},
                    onNavigateDownload = {},
                    onNavigateRepository = {},
                    onNavigateTransfers = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("home_auth_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not Authenticated").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect via Personal Access Token or Device Flow").assertIsDisplayed()

        val authButton = composeTestRule.onNodeWithTag("home_auth_button")
        authButton.assertIsDisplayed()
        authButton.assertTextContains("Sign In")
        authButton.performClick()
        assertTrue(authClicked)
    }

    @Test
    fun authenticatedState_displaysUsernameAndManageButton() {
        var authClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", "octocat@github.com"),
                    selectedRepo = null,
                    recentTransfers = emptyList(),
                    onOpenAuth = { authClicked = true },
                    onOpenRepoSelector = {},
                    onNavigateUpload = {},
                    onNavigateDownload = {},
                    onNavigateRepository = {},
                    onNavigateTransfers = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("home_auth_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeTestRule.onNodeWithText("Secure Keystore connection active").assertIsDisplayed()

        val authButton = composeTestRule.onNodeWithTag("home_auth_button")
        authButton.assertIsDisplayed()
        authButton.assertTextContains("Manage")
        authButton.performClick()
        assertTrue(authClicked)
    }

    @Test
    fun noRepositorySelected_displaysEmptyPromptAndSelectButton() {
        var repoSelectorClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = null,
                    recentTransfers = emptyList(),
                    onOpenAuth = {},
                    onOpenRepoSelector = { repoSelectorClicked = true },
                    onNavigateUpload = {},
                    onNavigateDownload = {},
                    onNavigateRepository = {},
                    onNavigateTransfers = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("home_repo_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("No repository chosen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select a repository to explore files, upload changes, or download content.").assertIsDisplayed()

        val selectButton = composeTestRule.onNodeWithTag("home_select_repo_button")
        selectButton.assertIsDisplayed()
        selectButton.assertTextContains("Select Repository")
        selectButton.performClick()
        assertTrue(repoSelectorClicked)
    }

    @Test
    fun repositorySelected_displaysRepoDetailsAndBranch() {
        var repoSelectorClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    recentTransfers = emptyList(),
                    onOpenAuth = {},
                    onOpenRepoSelector = { repoSelectorClicked = true },
                    onNavigateUpload = {},
                    onNavigateDownload = {},
                    onNavigateRepository = {},
                    onNavigateTransfers = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("home_repo_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat/Hello-World").assertIsDisplayed()
        composeTestRule.onNodeWithText("main").assertIsDisplayed()
        composeTestRule.onNodeWithText("Public").assertIsDisplayed()

        val switchButton = composeTestRule.onNodeWithTag("home_select_repo_button")
        switchButton.assertIsDisplayed()
        switchButton.assertTextContains("Switch Repository / Branch")
        switchButton.performClick()
        assertTrue(repoSelectorClicked)
    }

    @Test
    fun streamlinedHomeScreen_omitsRedundantQuickActions() {
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    recentTransfers = emptyList(),
                    onOpenAuth = {},
                    onOpenRepoSelector = {},
                    onNavigateTransfers = {}
                )
            }
        }

        // Quick Actions cards must not exist on the streamlined HomeScreen
        composeTestRule.onNodeWithTag("quick_upload_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("quick_download_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("quick_browser_card").assertDoesNotExist()
        composeTestRule.onNodeWithText("Quick Actions").assertDoesNotExist()

        // Core cards remain cleanly visible
        composeTestRule.onNodeWithTag("home_auth_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_repo_card").assertIsDisplayed()
    }

    @Test
    fun emptyRecentTransfers_displaysEmptyPrompt() {
        var transfersNavigated = false

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    recentTransfers = emptyList(),
                    onOpenAuth = {},
                    onOpenRepoSelector = {},
                    onNavigateUpload = {},
                    onNavigateDownload = {},
                    onNavigateRepository = {},
                    onNavigateTransfers = { transfersNavigated = true }
                )
            }
        }

        scrollToTag("home_empty_transfers_card")
        composeTestRule.onNodeWithTag("home_empty_transfers_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("No transfers yet").assertIsDisplayed()

        composeTestRule.onNodeWithTag("view_all_transfers_button").performClick()
        assertTrue(transfersNavigated)
    }

    @Test
    fun populatedRecentTransfers_displaysTransfersAndNavigates() {
        var transfersNavigated = false
        val sampleTransfers = listOf(
            TransferEntity(
                id = "tx-1",
                type = "UPLOAD",
                repoOwner = "octocat",
                repoName = "Hello-World",
                branch = "main",
                sourcePath = "/storage/test",
                destPath = "src/docs",
                status = "COMPLETED",
                totalFiles = 10,
                processedFiles = 10
            ),
            TransferEntity(
                id = "tx-2",
                type = "DOWNLOAD",
                repoOwner = "octocat",
                repoName = "Hello-World",
                branch = "main",
                sourcePath = "",
                destPath = "/storage/download",
                status = "UPLOADING",
                totalFiles = 25,
                processedFiles = 12
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    recentTransfers = sampleTransfers,
                    onOpenAuth = {},
                    onOpenRepoSelector = {},
                    onNavigateUpload = {},
                    onNavigateDownload = {},
                    onNavigateRepository = {},
                    onNavigateTransfers = { transfersNavigated = true }
                )
            }
        }

        scrollToTag("recent_transfer_item_tx-1")
        composeTestRule.onNodeWithTag("recent_transfer_item_tx-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("UPLOAD • octocat/Hello-World").assertIsDisplayed()
        composeTestRule.onNodeWithText("10/10 files • src/docs").assertIsDisplayed()

        scrollToTag("recent_transfer_item_tx-2")
        composeTestRule.onNodeWithTag("recent_transfer_item_tx-2").assertIsDisplayed()
        composeTestRule.onNodeWithText("DOWNLOAD • octocat/Hello-World").assertIsDisplayed()

        composeTestRule.onNodeWithTag("recent_transfer_item_tx-1").performClick()
        assertTrue(transfersNavigated)
    }

    @Test
    fun rateLimitTrackerDisplay_showsRemainingLimit() {
        RateLimitTracker.remaining = 4850
        RateLimitTracker.limit = 5000

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = testRepo,
                    recentTransfers = emptyList(),
                    onOpenAuth = {},
                    onOpenRepoSelector = {},
                    onNavigateUpload = {},
                    onNavigateDownload = {},
                    onNavigateRepository = {},
                    onNavigateTransfers = {}
                )
            }
        }

        scrollToTag("home_rate_limit_card")
        composeTestRule.onNodeWithTag("home_rate_limit_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("GitHub API Rate Limit").assertIsDisplayed()
        composeTestRule.onNodeWithText("4850 / 5000 remaining").assertIsDisplayed()
    }
}
