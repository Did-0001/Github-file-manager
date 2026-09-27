package com.example.ui.screens.repository

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.dto.GitHubContentDto
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
class RepoBrowserScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testRepo = SelectedRepoInfo(
        owner = "octocat",
        name = "Hello-World",
        branch = "main",
        defaultBranch = "main",
        isPrivate = false
    )

    private val sampleContents = listOf(
        GitHubContentDto(
            name = "src",
            path = "src",
            sha = "sha_src_folder",
            size = 0,
            type = "dir",
            downloadUrl = null,
            htmlUrl = "https://github.com/octocat/Hello-World/tree/main/src"
        ),
        GitHubContentDto(
            name = "docs",
            path = "docs",
            sha = "sha_docs_folder",
            size = 0,
            type = "dir",
            downloadUrl = null,
            htmlUrl = "https://github.com/octocat/Hello-World/tree/main/docs"
        ),
        GitHubContentDto(
            name = "README.md",
            path = "README.md",
            sha = "sha_readme_blob",
            size = 1024,
            type = "file",
            downloadUrl = "https://example.com/README.md",
            htmlUrl = "https://github.com/octocat/Hello-World/blob/main/README.md"
        ),
        GitHubContentDto(
            name = "build.gradle.kts",
            path = "build.gradle.kts",
            sha = "sha_gradle_blob",
            size = 2048,
            type = "file",
            downloadUrl = "https://example.com/build.gradle.kts",
            htmlUrl = "https://github.com/octocat/Hello-World/blob/main/build.gradle.kts"
        )
    )

    @Test
    fun `displays root directory contents with folders and files`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "",
                    contents = sampleContents,
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
                    onDownloadSelected = {},
                    onUploadToFolder = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        // Parent dir ".." should not be displayed in root
        composeTestRule.onNodeWithTag("browser_parent_dir").assertDoesNotExist()

        // Items should be displayed
        composeTestRule.onNodeWithTag("browser_item_src").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browser_item_docs").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browser_item_README.md").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browser_item_build.gradle.kts").assertIsDisplayed()
    }

    @Test
    fun `displays parent directory button and clicking it navigates up`() {
        var navigatedPath: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "src/main",
                    contents = sampleContents,
                    isLoading = false,
                    errorMessage = null,
                    onNavigatePath = { navigatedPath = it },
                    onRefresh = {},
                    onCreateFile = { _, _, _, _ -> },
                    onCreateDirectory = { _, _ -> },
                    onDeleteFile = { _, _, _ -> },
                    onDeleteFilesBatch = { _, _, _ -> },
                    onLoadFileContent = { _, _ -> },
                    onUpdateFile = { _, _, _, _ -> },
                    onDownloadFile = {},
                    onDownloadCurrentFolder = {},
                    onDownloadSelected = {},
                    onUploadToFolder = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        // Parent directory ".." button should be displayed
        composeTestRule.onNodeWithTag("browser_parent_dir").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browser_parent_dir").performClick()

        assertEquals("src", navigatedPath)
    }

    @Test
    fun `clicking directory item invokes onNavigatePath with item path`() {
        var navigatedPath: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "",
                    contents = sampleContents,
                    isLoading = false,
                    errorMessage = null,
                    onNavigatePath = { navigatedPath = it },
                    onRefresh = {},
                    onCreateFile = { _, _, _, _ -> },
                    onCreateDirectory = { _, _ -> },
                    onDeleteFile = { _, _, _ -> },
                    onDeleteFilesBatch = { _, _, _ -> },
                    onLoadFileContent = { _, _ -> },
                    onUpdateFile = { _, _, _, _ -> },
                    onDownloadFile = {},
                    onDownloadCurrentFolder = {},
                    onDownloadSelected = {},
                    onUploadToFolder = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("browser_item_src").performClick()
        assertEquals("src", navigatedPath)
    }

    @Test
    fun `clicking file item opens file details dialog and triggers download`() {
        var downloadedFile: GitHubContentDto? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "",
                    contents = sampleContents,
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
                    onDownloadFile = { downloadedFile = it },
                    onDownloadCurrentFolder = {},
                    onDownloadSelected = {},
                    onUploadToFolder = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("browser_item_README.md").performClick()

        // Detail dialog buttons should be displayed
        composeTestRule.onNodeWithTag("dialog_download_file_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog_view_file_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog_edit_file_button").assertIsDisplayed()

        // Trigger download
        composeTestRule.onNodeWithTag("dialog_download_file_button").performClick()
        assertNotNull(downloadedFile)
        assertEquals("README.md", downloadedFile?.name)
    }

    @Test
    fun `clicking folder options opens folder dialog with upload action`() {
        var uploadedFolderPath: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "",
                    contents = sampleContents,
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
                    onDownloadSelected = {},
                    onUploadToFolder = { uploadedFolderPath = it },
                    onOpenRepoSelector = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("browser_folder_options_src").performClick()

        // Folder dialog buttons should be displayed
        composeTestRule.onNodeWithTag("dialog_open_folder_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog_download_folder_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog_upload_folder_button").assertIsDisplayed()

        // Click upload into folder
        composeTestRule.onNodeWithTag("dialog_upload_folder_button").performClick()
        assertEquals("src", uploadedFolderPath)
    }

    @Test
    fun `search filter filters contents dynamically`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "",
                    contents = sampleContents,
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
                    onDownloadSelected = {},
                    onUploadToFolder = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        // Enter search text
        composeTestRule.onNodeWithTag("browser_filter_input").performTextInput("gradle")

        // Only matching items should exist
        composeTestRule.onNodeWithTag("browser_item_build.gradle.kts").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browser_item_src").assertDoesNotExist()
        composeTestRule.onNodeWithTag("browser_item_docs").assertDoesNotExist()
        composeTestRule.onNodeWithTag("browser_item_README.md").assertDoesNotExist()
    }

    @Test
    fun `selection mode enables multi-selection and batch download`() {
        var downloadedPaths: List<String>? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "",
                    contents = sampleContents,
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
                    onDownloadSelected = { downloadedPaths = it },
                    onUploadToFolder = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        // Enter selection mode
        composeTestRule.onNodeWithTag("browser_select_mode_button").performClick()

        // Click items to select them
        composeTestRule.onNodeWithTag("browser_item_README.md").performClick()
        composeTestRule.onNodeWithTag("browser_item_build.gradle.kts").performClick()

        // Batch action buttons should now be visible
        composeTestRule.onNodeWithTag("batch_download_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("batch_delete_button").assertIsDisplayed()

        // Perform batch download
        composeTestRule.onNodeWithTag("batch_download_button").performClick()

        assertNotNull(downloadedPaths)
        assertEquals(2, downloadedPaths?.size)
        assertTrue(downloadedPaths?.contains("README.md") == true)
        assertTrue(downloadedPaths?.contains("build.gradle.kts") == true)
    }

    @Test
    fun `top bar upload button invokes onUploadToFolder with current path`() {
        var uploadTarget: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                RepoBrowserScreen(
                    selectedRepo = testRepo,
                    currentPath = "docs/api",
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
                    onDownloadSelected = {},
                    onUploadToFolder = { uploadTarget = it },
                    onOpenRepoSelector = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("browser_upload_button").performClick()
        assertEquals("docs/api", uploadTarget)
    }
}
