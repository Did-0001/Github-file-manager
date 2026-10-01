package com.example.ui.screens.repository

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.dto.BranchCommitDto
import com.example.data.remote.dto.GitHubBranchDto
import com.example.data.repository.GitHubRepository
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BranchManagementTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleRepo = SelectedRepoInfo(
        owner = "octocat",
        name = "Hello-World",
        branch = "main",
        defaultBranch = "main",
        isPrivate = false
    )

    private val sampleBranches = listOf(
        GitHubBranchDto(
            name = "main",
            commit = BranchCommitDto(sha = "a1b2c3d4e5f67890"),
            isProtected = true
        ),
        GitHubBranchDto(
            name = "feature/login",
            commit = BranchCommitDto(sha = "f6e5d4c3b2a10987"),
            isProtected = false
        ),
        GitHubBranchDto(
            name = "staging",
            commit = BranchCommitDto(sha = "1234567890abcdef"),
            isProtected = false
        )
    )

    // ==========================================
    // 1. Branch Name Validation Rules Unit Tests
    // ==========================================

    @Test
    fun validateBranchName_validNamesPass() {
        val existing = listOf("main", "dev")
        assertNull(GitHubRepository.validateBranchName("feature/new-ui", existing))
        assertNull(GitHubRepository.validateBranchName("bugfix_123", existing))
        assertNull(GitHubRepository.validateBranchName("v1.0.0-release", existing))
        assertNull(GitHubRepository.validateBranchName("user/feature-1/subtask", existing))
    }

    @Test
    fun validateBranchName_emptyOrBlankFails() {
        val existing = listOf("main")
        val emptyErr = GitHubRepository.validateBranchName("", existing)
        assertNotNull(emptyErr)
        assertTrue(emptyErr!!.contains("cannot be empty", ignoreCase = true))

        val blankErr = GitHubRepository.validateBranchName("   ", existing)
        assertNotNull(blankErr)
        assertTrue(blankErr!!.contains("cannot be empty", ignoreCase = true))
    }

    @Test
    fun validateBranchName_duplicateBranchFails() {
        val existing = listOf("main", "feature/login")
        val dupErrExact = GitHubRepository.validateBranchName("main", existing)
        assertNotNull(dupErrExact)
        assertTrue(dupErrExact!!.contains("already exists", ignoreCase = true))

        val dupErrCase = GitHubRepository.validateBranchName("MAIN", existing)
        assertNotNull(dupErrCase)
        assertTrue(dupErrCase!!.contains("already exists", ignoreCase = true))

        val dupFeature = GitHubRepository.validateBranchName("feature/login", existing)
        assertNotNull(dupFeature)
        assertTrue(dupFeature!!.contains("already exists", ignoreCase = true))
    }

    @Test
    fun validateBranchName_invalidCharactersAndSequences() {
        val existing = listOf("main")

        // Invalid start characters
        assertNotNull(GitHubRepository.validateBranchName("-feature", existing))
        assertNotNull(GitHubRepository.validateBranchName(".feature", existing))
        assertNotNull(GitHubRepository.validateBranchName("/feature", existing))

        // Invalid end characters
        assertNotNull(GitHubRepository.validateBranchName("feature/", existing))
        assertNotNull(GitHubRepository.validateBranchName("feature.", existing))
        assertNotNull(GitHubRepository.validateBranchName("feature.lock", existing))

        // Invalid sequences
        assertNotNull(GitHubRepository.validateBranchName("feat..ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat//ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat@{ure", existing))

        // Invalid characters (spaces, ~, ^, :, ?, *, [, \)
        assertNotNull(GitHubRepository.validateBranchName("feat ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat~ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat^ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat:ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat?ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat*ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat[ure", existing))
        assertNotNull(GitHubRepository.validateBranchName("feat\\ure", existing))
    }

    // ==========================================
    // 2. Branch Management Dialog UI Tests
    // ==========================================

    @Test
    fun dialog_displaysCurrentBranch_andHeadSha_andBadges() {
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { _, _, _ -> },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        // Dialog card rendered
        composeTestRule.onNodeWithTag("branch_management_dialog").assertIsDisplayed()

        // Current branch card rendered and displays main
        composeTestRule.onNodeWithTag("current_branch_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("main").assertIsDisplayed()
        composeTestRule.onNodeWithText("CURRENT").assertIsDisplayed()
        composeTestRule.onNodeWithText("DEFAULT").assertIsDisplayed()
        composeTestRule.onNodeWithText("PROTECTED").assertIsDisplayed()
        composeTestRule.onNodeWithText("HEAD: a1b2c3d").assertIsDisplayed()

        // First item in other branches
        composeTestRule.onNodeWithText("feature/login").assertIsDisplayed()

        // Scroll to and verify staging branch
        composeTestRule.onNodeWithTag("branch_list").performScrollToNode(hasTestTag("branch_mgmt_item_staging"))
        composeTestRule.onNodeWithText("staging").assertIsDisplayed()
    }

    @Test
    fun dialog_refreshBranchesTriggered() {
        var refreshCount = 0
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = { refreshCount++ },
                    onSelectBranch = {},
                    onCreateBranch = { _, _, _ -> },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("branch_mgmt_refresh_button").performClick()
        assertEquals(1, refreshCount)
    }

    @Test
    fun dialog_createBranch_opensModal_validates_andCreates() {
        var createdName = ""
        var createdSource = ""
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { name, source, onComplete ->
                        createdName = name
                        createdSource = source
                        onComplete(true, null)
                    },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        // Click + Create button
        composeTestRule.onNodeWithTag("branch_mgmt_create_button").performClick()

        // Create branch dialog is displayed
        composeTestRule.onNodeWithTag("create_branch_dialog").assertIsDisplayed()

        // Source branch selector displayed
        composeTestRule.onNodeWithTag("source_branch_selector").assertIsDisplayed()

        // Confirm button initially disabled because name is empty
        composeTestRule.onNodeWithTag("create_branch_confirm_btn").assertIsNotEnabled()

        // Enter a valid branch name
        composeTestRule.onNodeWithTag("new_branch_name_input").performTextInput("feature/cool-stuff")

        // Confirm button should now be enabled
        composeTestRule.onNodeWithTag("create_branch_confirm_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("create_branch_confirm_btn").performClick()

        assertEquals("feature/cool-stuff", createdName)
        assertEquals("main", createdSource)
    }

    @Test
    fun dialog_createBranch_duplicateName_showsValidationError() {
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { _, _, _ -> },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        // Click + Create
        composeTestRule.onNodeWithTag("branch_mgmt_create_button").performClick()

        // Enter duplicate name "staging"
        composeTestRule.onNodeWithTag("new_branch_name_input").performTextInput("staging")

        // Validation error appears
        composeTestRule.onNodeWithText("A branch named 'staging' already exists").assertIsDisplayed()
        composeTestRule.onNodeWithTag("create_branch_confirm_btn").assertIsNotEnabled()
    }

    @Test
    fun dialog_createBranch_invalidName_showsValidationError() {
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { _, _, _ -> },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("branch_mgmt_create_button").performClick()
        composeTestRule.onNodeWithTag("new_branch_name_input").performTextInput("-bad-start")

        composeTestRule.onNodeWithText("Branch name cannot start with '/', '-', or '.'").assertIsDisplayed()
        composeTestRule.onNodeWithTag("create_branch_confirm_btn").assertIsNotEnabled()
    }

    @Test
    fun dialog_deleteBranch_defaultBranchProtected() {
        val repoWithFeatureDefault = sampleRepo.copy(branch = "main", defaultBranch = "feature/login")
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = repoWithFeatureDefault,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { _, _, _ -> },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        // "main" is active branch card: no delete button exists
        composeTestRule.onNodeWithTag("branch_mgmt_delete_main").assertDoesNotExist()

        // "feature/login" is default branch: delete button is disabled
        composeTestRule.onNodeWithTag("branch_list").performScrollToNode(hasTestTag("branch_mgmt_delete_feature/login"))
        composeTestRule.onNodeWithTag("branch_mgmt_delete_feature/login").assertIsNotEnabled()

        // Scroll to "staging", which is non-default: delete button is enabled
        composeTestRule.onNodeWithTag("branch_list").performScrollToNode(hasTestTag("branch_mgmt_delete_staging"))
        composeTestRule.onNodeWithTag("branch_mgmt_delete_staging").assertIsEnabled()
    }

    @Test
    fun dialog_deleteBranch_nonDefaultBranch_invokesDelete() {
        var deletedName = ""
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { _, _, _ -> },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { name, onComplete ->
                        deletedName = name
                        onComplete(true, null)
                    },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        // Scroll directly to delete button and click
        composeTestRule.onNodeWithTag("branch_list").performScrollToNode(hasTestTag("branch_mgmt_delete_feature/login"))
        composeTestRule.onNodeWithTag("branch_mgmt_delete_feature/login").performClick()

        // Confirmation dialog is displayed
        composeTestRule.onNodeWithTag("delete_branch_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("delete_branch_confirm_btn").performClick()

        assertEquals("feature/login", deletedName)
    }

    @Test
    fun dialog_renameBranch_invokesCallback() {
        var oldNameCaptured = ""
        var newNameCaptured = ""
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { _, _, _ -> },
                    onRenameBranch = { oldName, newName, onComplete ->
                        oldNameCaptured = oldName
                        newNameCaptured = newName
                        onComplete(true, null)
                    },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        // Scroll directly to rename button and click
        composeTestRule.onNodeWithTag("branch_list").performScrollToNode(hasTestTag("branch_mgmt_rename_feature/login"))
        composeTestRule.onNodeWithTag("branch_mgmt_rename_feature/login").performClick()
        composeTestRule.onNodeWithTag("rename_branch_dialog").assertIsDisplayed()

        composeTestRule.onNodeWithTag("rename_branch_name_input").performTextClearance()
        composeTestRule.onNodeWithTag("rename_branch_name_input").performTextInput("feature/login-v2")

        composeTestRule.onNodeWithTag("rename_branch_confirm_btn").performClick()

        assertEquals("feature/login", oldNameCaptured)
        assertEquals("feature/login-v2", newNameCaptured)
    }

    @Test
    fun dialog_permissionFailure_displaysErrorMessage() {
        composeTestRule.setContent {
            MyApplicationTheme {
                BranchManagementDialog(
                    selectedRepo = sampleRepo,
                    branches = sampleBranches,
                    isLoading = false,
                    onDismiss = {},
                    onRefreshBranches = {},
                    onSelectBranch = {},
                    onCreateBranch = { _, _, onComplete ->
                        onComplete(false, "HTTP 403: Protected branch restriction prohibits modification")
                    },
                    onRenameBranch = { _, _, _ -> },
                    onDeleteBranch = { _, _ -> },
                    onSetDefaultBranch = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("branch_mgmt_create_button").performClick()
        composeTestRule.onNodeWithTag("new_branch_name_input").performTextInput("test-branch")
        composeTestRule.onNodeWithTag("create_branch_confirm_btn").performClick()

        // Error message surfaced to user
        composeTestRule.onNodeWithText("HTTP 403: Protected branch restriction prohibits modification").assertIsDisplayed()
    }

    // ==========================================
    // 3. Repo Browser Branch Selector & Overflow Menu
    // ==========================================

    @Test
    fun repoBrowserScreen_branchSelector_and_overflowMenu() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = sampleRepo,
                    currentPath = "",
                    contents = emptyList(),
                    isLoading = false,
                    errorMessage = null,
                    onNavigatePath = {},
                    onRefresh = {},
                    onCreateFile = { _, _, _, _ -> },
                    onCreateDirectory = { _, _ -> },
                    onDeleteFile = { _, _, _ -> },
                    onDeleteFilesBatch = { _, _, _ -> },
                    onLoadFileContent = { _, _ -> },
                    onUpdateFile = { _, _, _, _ -> },
                    onDownloadFile = {},
                    onDownloadCurrentFolder = {},
                    onOpenRepoSelector = {},
                    branches = sampleBranches
                )
            }
        }

        // Branch selector chip exists and shows "main"
        composeTestRule.onNodeWithTag("browser_branch_selector").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browser_branch_selector").performClick()

        // Branch management dialog is opened
        composeTestRule.onNodeWithTag("branch_management_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("branch_mgmt_close_button").performClick()
        composeTestRule.onNodeWithTag("branch_management_dialog").assertDoesNotExist()

        // Overflow menu exists and contains Branch management item
        composeTestRule.onNodeWithTag("browser_overflow_menu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browser_overflow_menu").performClick()

        composeTestRule.onNodeWithTag("menu_branch_management").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_branch_management").performClick()

        // Branch management dialog is opened via overflow menu
        composeTestRule.onNodeWithTag("branch_management_dialog").assertIsDisplayed()
    }
}
