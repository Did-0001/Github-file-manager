package com.example.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.dto.GitHubContentDto
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GitHubPathPickerDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testRepo = SelectedRepoInfo(
        owner = "test-owner",
        name = "test-repo",
        branch = "main",
        defaultBranch = "main",
        isPrivate = false
    )

    private fun createDirectoryContents(path: String): List<GitHubContentDto> {
        return when (path) {
            "" -> listOf(
                GitHubContentDto(
                    name = "app",
                    path = "app",
                    sha = "sha_app",
                    size = 0,
                    type = "dir",
                    downloadUrl = null,
                    htmlUrl = ""
                ),
                GitHubContentDto(
                    name = "docs",
                    path = "docs",
                    sha = "sha_docs",
                    size = 0,
                    type = "dir",
                    downloadUrl = null,
                    htmlUrl = ""
                ),
                GitHubContentDto(
                    name = "README.md",
                    path = "README.md",
                    sha = "sha_readme",
                    size = 120,
                    type = "file",
                    downloadUrl = "https://example.com/README.md",
                    htmlUrl = ""
                )
            )
            "app" -> listOf(
                GitHubContentDto(
                    name = "src",
                    path = "app/src",
                    sha = "sha_src",
                    size = 0,
                    type = "dir",
                    downloadUrl = null,
                    htmlUrl = ""
                ),
                GitHubContentDto(
                    name = "build.gradle.kts",
                    path = "app/build.gradle.kts",
                    sha = "sha_gradle",
                    size = 500,
                    type = "file",
                    downloadUrl = "https://example.com/build.gradle.kts",
                    htmlUrl = ""
                )
            )
            "app/src" -> listOf(
                GitHubContentDto(
                    name = "main",
                    path = "app/src/main",
                    sha = "sha_main",
                    size = 0,
                    type = "dir",
                    downloadUrl = null,
                    htmlUrl = ""
                )
            )
            else -> emptyList()
        }
    }

    @Test
    fun popupOpens_andDisplaysRootContents() {
        var confirmedPath: String? = null
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                GitHubPathPickerContent(
                    selectedRepo = testRepo,
                    initialPath = "",
                    pickerMode = GitHubPickerMode.DIRECTORIES_ONLY,
                    onFetchDirectory = { _, _, path, _ ->
                        Result.success(createDirectoryContents(path))
                    },
                    onDismiss = { dismissed = true },
                    onPathConfirmed = { confirmedPath = it }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Verify title & repo header displayed
        composeTestRule.onNodeWithText("Browse GitHub Destination").assertIsDisplayed()
        composeTestRule.onNodeWithText("test-owner/test-repo (main)").assertIsDisplayed()

        // Verify root items displayed
        composeTestRule.onNodeWithText("app").assertIsDisplayed()
        composeTestRule.onNodeWithText("docs").assertIsDisplayed()
        composeTestRule.onNodeWithText("README.md").assertIsDisplayed()

        // Verify root is default selected in DIRECTORIES_ONLY mode
        composeTestRule.onNodeWithTag("path_picker_selected_path_text").assertTextContains("/ (Root)")
    }

    @Test
    fun folderNavigation_andUpNavigation_workCorrectly() {
        composeTestRule.setContent {
            MyApplicationTheme {
                GitHubPathPickerContent(
                    selectedRepo = testRepo,
                    initialPath = "",
                    pickerMode = GitHubPickerMode.DIRECTORIES_ONLY,
                    onFetchDirectory = { _, _, path, _ ->
                        Result.success(createDirectoryContents(path))
                    },
                    onDismiss = {},
                    onPathConfirmed = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Click on "app" item to navigate into it
        composeTestRule.onNodeWithTag("path_picker_item_app").performClick()
        composeTestRule.waitForIdle()

        // Should now see contents of "app"
        composeTestRule.onNodeWithText("src").assertIsDisplayed()
        composeTestRule.onNodeWithText("build.gradle.kts").assertIsDisplayed()
        composeTestRule.onNodeWithTag("path_picker_selected_path_text").assertTextContains("/app")

        // Now navigate into "src"
        composeTestRule.onNodeWithTag("path_picker_item_app/src").performClick()
        composeTestRule.waitForIdle()

        // Should see "main"
        composeTestRule.onNodeWithText("main").assertIsDisplayed()
        composeTestRule.onNodeWithTag("path_picker_selected_path_text").assertTextContains("/app/src")

        // Now click Up button to navigate back to "app"
        composeTestRule.onNodeWithTag("path_picker_up_btn").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("src").assertIsDisplayed()
        composeTestRule.onNodeWithTag("path_picker_selected_path_text").assertTextContains("/app")
    }

    @Test
    fun directoriesOnlyMode_allowsSelectingRootOrFolders_notFiles() {
        var confirmedPath: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                GitHubPathPickerContent(
                    selectedRepo = testRepo,
                    initialPath = "",
                    pickerMode = GitHubPickerMode.DIRECTORIES_ONLY,
                    onFetchDirectory = { _, _, path, _ ->
                        Result.success(createDirectoryContents(path))
                    },
                    onDismiss = {},
                    onPathConfirmed = { confirmedPath = it }
                )
            }
        }

        composeTestRule.waitForIdle()

        // In DIRECTORIES_ONLY, clicking a file (README.md) does not select it
        composeTestRule.onNodeWithTag("path_picker_item_README.md").performClick()
        composeTestRule.waitForIdle()
        // Selection remains root
        composeTestRule.onNodeWithTag("path_picker_selected_path_text").assertTextContains("/ (Root)")

        // Select "docs" using the folder select button
        composeTestRule.onNodeWithTag("select_folder_docs").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("path_picker_selected_path_text").assertTextContains("/docs")

        // Press confirm (tick/Select) button
        composeTestRule.onNodeWithTag("path_picker_confirm_btn").performClick()
        assertEquals("docs", confirmedPath)
    }

    @Test
    fun rootCanBeConfirmed_inDirectoriesOnlyMode() {
        var confirmedPath: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                GitHubPathPickerContent(
                    selectedRepo = testRepo,
                    initialPath = "",
                    pickerMode = GitHubPickerMode.DIRECTORIES_ONLY,
                    onFetchDirectory = { _, _, path, _ ->
                        Result.success(createDirectoryContents(path))
                    },
                    onDismiss = {},
                    onPathConfirmed = { confirmedPath = it }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Confirm root directly
        composeTestRule.onNodeWithTag("path_picker_confirm_btn").performClick()
        assertEquals("/", confirmedPath)
    }

    @Test
    fun filesOnlyMode_allowsSelectingFiles_notDirectories() {
        var confirmedPath: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                GitHubPathPickerContent(
                    selectedRepo = testRepo,
                    initialPath = "",
                    pickerMode = GitHubPickerMode.FILES_ONLY,
                    onFetchDirectory = { _, _, path, _ ->
                        Result.success(createDirectoryContents(path))
                    },
                    onDismiss = {},
                    onPathConfirmed = { confirmedPath = it }
                )
            }
        }

        composeTestRule.waitForIdle()

        // In FILES_ONLY, initially selected path is blank, confirm button is disabled
        composeTestRule.onNodeWithTag("path_picker_confirm_btn").assertIsNotEnabled()

        // Clicking a directory ("app") navigates into it, does NOT select it as the target file
        composeTestRule.onNodeWithTag("path_picker_item_app").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("path_picker_confirm_btn").assertIsNotEnabled()

        // In "app", click "build.gradle.kts" file
        composeTestRule.onNodeWithTag("path_picker_item_app/build.gradle.kts").performClick()
        composeTestRule.waitForIdle()

        // File is now selected and confirm button becomes enabled
        composeTestRule.onNodeWithTag("path_picker_selected_path_text").assertTextContains("app/build.gradle.kts")
        composeTestRule.onNodeWithTag("path_picker_confirm_btn").assertIsEnabled()

        // Confirm selection
        composeTestRule.onNodeWithTag("path_picker_confirm_btn").performClick()
        assertEquals("app/build.gradle.kts", confirmedPath)
    }

    @Test
    fun cancel_doesNotConfirmPath_andInvokesDismiss() {
        var confirmedPath: String? = null
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                GitHubPathPickerContent(
                    selectedRepo = testRepo,
                    initialPath = "",
                    pickerMode = GitHubPickerMode.DIRECTORIES_ONLY,
                    onFetchDirectory = { _, _, path, _ ->
                        Result.success(createDirectoryContents(path))
                    },
                    onDismiss = { dismissed = true },
                    onPathConfirmed = { confirmedPath = it }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Select docs
        composeTestRule.onNodeWithTag("select_folder_docs").performClick()
        composeTestRule.waitForIdle()

        // Cancel
        composeTestRule.onNodeWithTag("path_picker_cancel_btn").performClick()

        assert(dismissed)
        assertNull(confirmedPath)
    }

    @Test
    fun errorState_allowsRetry() {
        var callCount = 0

        composeTestRule.setContent {
            MyApplicationTheme {
                GitHubPathPickerContent(
                    selectedRepo = testRepo,
                    initialPath = "",
                    pickerMode = GitHubPickerMode.DIRECTORIES_ONLY,
                    onFetchDirectory = { _, _, _, _ ->
                        callCount++
                        if (callCount == 1) {
                            Result.failure(RuntimeException("Network timeout"))
                        } else {
                            Result.success(createDirectoryContents(""))
                        }
                    },
                    onDismiss = {},
                    onPathConfirmed = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Should display error message and retry button
        composeTestRule.onNodeWithText("Network timeout").assertIsDisplayed()
        composeTestRule.onNodeWithTag("path_picker_retry_btn").performClick()
        composeTestRule.waitForIdle()

        // After retry, contents should load
        composeTestRule.onNodeWithText("app").assertIsDisplayed()
    }
}
