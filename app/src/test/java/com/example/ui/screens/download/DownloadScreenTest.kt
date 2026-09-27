package com.example.ui.screens.download

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
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
class DownloadScreenTest {

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
        composeTestRule.onNodeWithTag("download_lazy_column").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun initialState_displaysSelectedRepoAndDefaultScope() {
        composeTestRule.setContent {
            MyApplicationTheme {
                DownloadScreen(
                    selectedRepo = testRepo,
                    initialPath = null,
                    initialIsFile = false,
                    initialSelectedPaths = emptyList(),
                    onOpenRepoSelector = {},
                    onStartDownload = { _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        composeTestRule.onNodeWithText("octocat/Hello-World").assertIsDisplayed()
        composeTestRule.onNodeWithText("Branch: main").assertIsDisplayed()
        composeTestRule.onNodeWithTag("download_switch_repo_btn").assertIsDisplayed()

        // Scope Tab
        composeTestRule.onNodeWithTag("scope_tab_entire_repo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scope_tab_folder").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scope_tab_single_file").assertIsDisplayed()

        // Destination Card
        scrollToTag("card_download_destination")
        composeTestRule.onNodeWithTag("card_download_destination").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_dest_folder_button").assertIsDisplayed()

        // Options Card
        scrollToTag("card_download_options")
        composeTestRule.onNodeWithTag("as_zip_switch").assertIsDisplayed()

        // Button disabled when no local destination selected
        scrollToTag("execute_download_button")
        composeTestRule.onNodeWithTag("execute_download_button").assertIsNotEnabled()
    }

    @Test
    fun switchScopeToFolder_displaysPathInputAndBrowseButton() {
        composeTestRule.setContent {
            MyApplicationTheme {
                DownloadScreen(
                    selectedRepo = testRepo,
                    initialPath = null,
                    initialIsFile = false,
                    initialSelectedPaths = emptyList(),
                    onOpenRepoSelector = {},
                    onStartDownload = { _, _ -> },
                    onFetchDirectory = { _, _, _, _ -> Result.success(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("scope_tab_folder").performClick()

        val pathInput = composeTestRule.onNodeWithTag("download_remote_path_input")
        pathInput.assertIsDisplayed()
        pathInput.performTextInput("src/main")
        pathInput.assertTextContains("src/main")

        composeTestRule.onNodeWithTag("download_browse_path_button").assertIsDisplayed()
    }

    @Test
    fun switchScopeToSingleFile_displaysSingleFileInput() {
        composeTestRule.setContent {
            MyApplicationTheme {
                DownloadScreen(
                    selectedRepo = testRepo,
                    initialPath = null,
                    initialIsFile = false,
                    initialSelectedPaths = emptyList(),
                    onOpenRepoSelector = {},
                    onStartDownload = { _, _ -> },
                    onFetchDirectory = { _, _, _, _ -> Result.success(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("scope_tab_single_file").performClick()

        val pathInput = composeTestRule.onNodeWithTag("download_remote_path_input")
        pathInput.assertIsDisplayed()
        pathInput.performTextInput("README.md")
        pathInput.assertTextContains("README.md")
    }

    @Test
    fun initialSelectedPaths_selectsSelectedScopeTabAndDisplaysPaths() {
        val selectedItems = listOf("src/App.kt", "build.gradle.kts")

        composeTestRule.setContent {
            MyApplicationTheme {
                DownloadScreen(
                    selectedRepo = testRepo,
                    initialPath = null,
                    initialIsFile = false,
                    initialSelectedPaths = selectedItems,
                    onOpenRepoSelector = {},
                    onStartDownload = { _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        composeTestRule.onNodeWithTag("scope_tab_selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 item(s) selected for download:").assertExists()
        composeTestRule.onNodeWithText("• src/App.kt").assertExists()
        composeTestRule.onNodeWithText("• build.gradle.kts").assertExists()
    }

    @Test
    fun toggleAsZip_togglesZipOption() {
        composeTestRule.setContent {
            MyApplicationTheme {
                DownloadScreen(
                    selectedRepo = testRepo,
                    initialPath = null,
                    initialIsFile = false,
                    initialSelectedPaths = emptyList(),
                    onOpenRepoSelector = {},
                    onStartDownload = { _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        scrollToTag("card_download_options")
        composeTestRule.onNodeWithTag("create_repo_folder_switch").assertIsDisplayed()

        // Toggle ZIP on
        composeTestRule.onNodeWithTag("as_zip_switch").performClick()

        // When asZip is enabled, subfolder options are hidden
        composeTestRule.onNodeWithTag("create_repo_folder_switch").assertDoesNotExist()
    }

    @Test
    fun collisionPolicySelection_updatesSelectedPolicy() {
        composeTestRule.setContent {
            MyApplicationTheme {
                DownloadScreen(
                    selectedRepo = testRepo,
                    initialPath = null,
                    initialIsFile = false,
                    initialSelectedPaths = emptyList(),
                    onOpenRepoSelector = {},
                    onStartDownload = { _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        scrollToTag("card_download_options")
        composeTestRule.onNodeWithTag("overwrite_policy_overwrite").assertIsSelected()

        // Select Skip
        composeTestRule.onNodeWithTag("overwrite_policy_skip").performClick()
        composeTestRule.onNodeWithTag("overwrite_policy_skip").assertIsSelected()

        // Select Keep Both
        composeTestRule.onNodeWithTag("overwrite_policy_keep_both").performClick()
        composeTestRule.onNodeWithTag("overwrite_policy_keep_both").assertIsSelected()
    }

    @Test
    fun browseGitHubPathButton_opensPathPickerDialog() {
        composeTestRule.setContent {
            MyApplicationTheme {
                DownloadScreen(
                    selectedRepo = testRepo,
                    initialPath = "src",
                    initialIsFile = false,
                    initialSelectedPaths = emptyList(),
                    onOpenRepoSelector = {},
                    onStartDownload = { _, _ -> },
                    onFetchDirectory = { _, _, _, _ -> Result.success(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("download_browse_path_button").performClick()
        composeTestRule.onNodeWithTag("github_path_picker_dialog").assertIsDisplayed()
    }
}
