package com.example.ui.screens.repository

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.remote.dto.BranchCommitDto
import com.example.data.remote.dto.GitHubBranchDto
import com.example.data.remote.dto.GitHubRepoDto
import com.example.data.remote.dto.RepoOwnerDto
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepoSelectorDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleOwner = RepoOwnerDto(login = "octocat", avatarUrl = null)

    private val repo1 = GitHubRepoDto(
        id = 101L,
        name = "Hello-World",
        fullName = "octocat/Hello-World",
        isPrivate = false,
        owner = sampleOwner,
        description = "My first repository",
        defaultBranch = "main",
        updatedAt = "2026-09-01T00:00:00Z"
    )

    private val repo2 = GitHubRepoDto(
        id = 102L,
        name = "Spoon-Knife",
        fullName = "octocat/Spoon-Knife",
        isPrivate = true,
        owner = sampleOwner,
        description = "Fork demonstration repository",
        defaultBranch = "main",
        updatedAt = "2026-09-02T00:00:00Z"
    )

    private val branchesForRepo1 = listOf(
        GitHubBranchDto(name = "main", commit = BranchCommitDto(sha = "a1b2c3d4e5f6"), isProtected = true),
        GitHubBranchDto(name = "dev", commit = BranchCommitDto(sha = "f6e5d4c3b2a1"), isProtected = false)
    )

    @Test
    fun emptyRepoList_displaysEmptyNotice() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = emptyList(),
                    isLoading = false,
                    onDismiss = {},
                    onRefreshRepos = {},
                    onSelectRepo = { _, _ -> },
                    onFetchBranches = { _, _ -> emptyList() },
                    onCreateRepo = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("repo_list_empty_view").assertIsDisplayed()
        composeTestRule.onNodeWithText("No repositories found.\nEnsure your token has 'repo' permissions.").assertIsDisplayed()
    }

    @Test
    fun displaysRepositories_andFiltersBySearch() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = listOf(repo1, repo2),
                    isLoading = false,
                    onDismiss = {},
                    onRefreshRepos = {},
                    onSelectRepo = { _, _ -> },
                    onFetchBranches = { _, _ -> emptyList() },
                    onCreateRepo = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithText("octocat/Hello-World").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat/Spoon-Knife").assertIsDisplayed()

        // Filter by typing 'spoon'
        val searchField = composeTestRule.onNodeWithTag("repo_search_input")
        searchField.performTextInput("spoon")

        composeTestRule.onNodeWithText("octocat/Spoon-Knife").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat/Hello-World").assertDoesNotExist()
    }

    @Test
    fun clickRepo_loadsAndDisplaysBranches() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = listOf(repo1),
                    isLoading = false,
                    onDismiss = {},
                    onRefreshRepos = {},
                    onSelectRepo = { _, _ -> },
                    onFetchBranches = { _, _ -> branchesForRepo1 },
                    onCreateRepo = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("repo_item_Hello-World").performClick()

        // Header updates
        composeTestRule.onNodeWithText("Select Branch").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat/Hello-World").assertIsDisplayed()

        // Default branch card
        composeTestRule.onNodeWithTag("branch_default_main").assertIsDisplayed()
        composeTestRule.onNodeWithText("main (Default Branch)").assertIsDisplayed()

        // Other branch item
        composeTestRule.onNodeWithTag("branch_item_dev").assertIsDisplayed()
        composeTestRule.onNodeWithText("dev").assertIsDisplayed()
    }

    @Test
    fun selectDefaultBranch_invokesCallbackAndDismisses() {
        var selectedRepo: GitHubRepoDto? = null
        var selectedBranch: String? = null
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = listOf(repo1),
                    isLoading = false,
                    onDismiss = { dismissed = true },
                    onRefreshRepos = {},
                    onSelectRepo = { repo, branch ->
                        selectedRepo = repo
                        selectedBranch = branch
                    },
                    onFetchBranches = { _, _ -> branchesForRepo1 },
                    onCreateRepo = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("repo_item_Hello-World").performClick()
        composeTestRule.onNodeWithTag("branch_default_main").performClick()

        assertEquals("Hello-World", selectedRepo?.name)
        assertEquals("main", selectedBranch)
        assertTrue(dismissed)
    }

    @Test
    fun selectOtherBranch_invokesCallbackAndDismisses() {
        var selectedRepo: GitHubRepoDto? = null
        var selectedBranch: String? = null
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = listOf(repo1),
                    isLoading = false,
                    onDismiss = { dismissed = true },
                    onRefreshRepos = {},
                    onSelectRepo = { repo, branch ->
                        selectedRepo = repo
                        selectedBranch = branch
                    },
                    onFetchBranches = { _, _ -> branchesForRepo1 },
                    onCreateRepo = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("repo_item_Hello-World").performClick()
        composeTestRule.onNodeWithTag("branch_item_dev").performClick()

        assertEquals("Hello-World", selectedRepo?.name)
        assertEquals("dev", selectedBranch)
        assertTrue(dismissed)
    }

    @Test
    fun backToReposButton_returnsToRepoList() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = listOf(repo1, repo2),
                    isLoading = false,
                    onDismiss = {},
                    onRefreshRepos = {},
                    onSelectRepo = { _, _ -> },
                    onFetchBranches = { _, _ -> branchesForRepo1 },
                    onCreateRepo = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("repo_item_Hello-World").performClick()
        composeTestRule.onNodeWithText("Select Branch").assertIsDisplayed()

        // Click Back
        composeTestRule.onNodeWithTag("back_to_repos_button").performClick()
        composeTestRule.onNodeWithText("Select Repository").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat/Hello-World").assertIsDisplayed()
    }

    @Test
    fun refreshAndCloseButtons_invokeCallbacks() {
        var refreshed = false
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = listOf(repo1),
                    isLoading = false,
                    onDismiss = { dismissed = true },
                    onRefreshRepos = { refreshed = true },
                    onSelectRepo = { _, _ -> },
                    onFetchBranches = { _, _ -> emptyList() },
                    onCreateRepo = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("refresh_repos_button").performClick()
        assertTrue(refreshed)

        composeTestRule.onNodeWithTag("repo_selector_close_button").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun createRepoFlow_opensDialogAndInvokesCallback() {
        var createdName: String? = null
        var createdDesc: String? = null
        var createdPrivate: Boolean? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoSelectorDialog(
                    repos = listOf(repo1),
                    isLoading = false,
                    onDismiss = {},
                    onRefreshRepos = {},
                    onSelectRepo = { _, _ -> },
                    onFetchBranches = { _, _ -> emptyList() },
                    onCreateRepo = { name, desc, isPrivate, cb ->
                        createdName = name
                        createdDesc = desc
                        createdPrivate = isPrivate
                        cb(true, null)
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("open_create_repo_button").performClick()
        composeTestRule.onNodeWithTag("create_repo_dialog").assertIsDisplayed()

        composeTestRule.onNodeWithTag("new_repo_name_input").performTextInput("my-new-app")
        composeTestRule.onNodeWithTag("new_repo_private_switch").performClick()

        composeTestRule.onNodeWithTag("confirm_create_repo_button").performClick()

        assertEquals("my-new-app", createdName)
        assertEquals(true, createdPrivate)
        composeTestRule.onNodeWithTag("create_repo_dialog").assertDoesNotExist()
    }
}
