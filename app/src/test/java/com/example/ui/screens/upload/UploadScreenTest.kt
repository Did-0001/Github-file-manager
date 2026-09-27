package com.example.ui.screens.upload

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
import com.example.domain.model.*
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
class UploadScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testRepo = SelectedRepoInfo(
        owner = "octocat",
        name = "Hello-World",
        branch = "main",
        defaultBranch = "main",
        isPrivate = false
    )

    private val sampleScannedFile = FileScanItem(
        uri = Uri.parse("content://test/file1"),
        relativePath = "src/Main.kt",
        sizeBytes = 1024L,
        isDirectory = false
    )

    private fun scrollToTag(tag: String) {
        composeTestRule.onNodeWithTag("upload_lazy_column").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun initialState_displaysRepoDestinationAndDefaults() {
        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = null,
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = emptyList(),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { _, _, _, _ -> },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        composeTestRule.onNodeWithText("Target: octocat/Hello-World (main)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_folder_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_files_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("destination_path_input").assertIsDisplayed()

        // Scroll to commit card
        scrollToTag("card_upload_commit")
        composeTestRule.onNodeWithTag("commit_message_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("execute_upload_button").assertIsDisplayed()
    }

    @Test
    fun editDestinationPath_updatesValue() {
        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = "initial/path",
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = emptyList(),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { _, _, _, _ -> },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        val destInput = composeTestRule.onNodeWithTag("destination_path_input")
        destInput.assertTextContains("initial/path")
        destInput.performTextClearance()
        destInput.performTextInput("packages/app")
        destInput.assertTextContains("packages/app")
    }

    @Test
    fun openIgnoreRulesDialog_modifiesPatternsAndSaves() {
        var updatedRules: IgnoreRules? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = null,
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = emptyList(),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = { updatedRules = it },
                    onStartUpload = { _, _, _, _ -> },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        scrollToTag("card_upload_source")
        composeTestRule.onNodeWithTag("open_ignore_rules_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Ignore Rules (.gitignore)").assertIsDisplayed()

        val patternsInput = composeTestRule.onNodeWithTag("custom_ignore_patterns_input")
        patternsInput.assertExists().performTextInput("*.log\nsecrets/")

        composeTestRule.onNodeWithTag("save_ignore_rules_button").assertIsDisplayed().performClick()

        assertNotNull(updatedRules)
        assertTrue(updatedRules!!.customPatterns.contains("*.log"))
        assertTrue(updatedRules!!.customPatterns.contains("secrets/"))
    }

    @Test
    fun calculateDiffButton_triggersOnRunPreflight() {
        var preflightDest: String? = null
        var preflightIsWipe: Boolean? = null
        var preflightWipeMode: WipeMode? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = "docs",
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = listOf(sampleScannedFile),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { _, _, _, _ -> },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { dest, isWipe, wipeMode ->
                        preflightDest = dest
                        preflightIsWipe = isWipe
                        preflightWipeMode = wipeMode
                    },
                    onFetchDirectory = null
                )
            }
        }

        scrollToTag("card_upload_diff")
        composeTestRule.onNodeWithTag("calculate_diff_btn").assertIsDisplayed().performClick()
        assertEquals("docs", preflightDest)
        assertEquals(false, preflightIsWipe)
        assertEquals(WipeMode.NONE, preflightWipeMode)
    }

    @Test
    fun diffReportPresent_displaysDiffDetailsDialog() {
        val sampleDiff = DiffReport(
            added = 1,
            modified = 1,
            deleted = 0,
            unchanged = 0,
            excluded = 0,
            items = listOf(
                DiffItem(
                    localPath = "src/Main.kt",
                    remotePath = "src/Main.kt",
                    changeType = DiffChangeType.ADDED,
                    sizeBytes = 1024L
                ),
                DiffItem(
                    localPath = "src/Utils.kt",
                    remotePath = "src/Utils.kt",
                    changeType = DiffChangeType.MODIFIED,
                    sizeBytes = 512L
                )
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = null,
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = listOf(sampleScannedFile),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { _, _, _, _ -> },
                    onOpenRepoSelector = {},
                    diffReport = sampleDiff,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        scrollToTag("card_upload_diff")
        composeTestRule.onNodeWithTag("view_diff_details_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Itemized Changes (2 files)").assertIsDisplayed()
        composeTestRule.onNodeWithText("src/Main.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("ADDED").assertIsDisplayed()
        composeTestRule.onNodeWithText("src/Utils.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("MODIFIED").assertIsDisplayed()

        // Close dialog
        composeTestRule.onNodeWithText("Done").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Itemized Changes (2 files)").assertDoesNotExist()
    }

    @Test
    fun wipeModeSelection_opensWipeConfirmDialogAndAcks() {
        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = "target_dir",
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = listOf(sampleScannedFile),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { _, _, _, _ -> },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        // Scroll to commit card
        scrollToTag("card_upload_commit")

        // Toggle Wipe Mode (destinationPath is "target_dir" so it triggers destination wipe dialog)
        composeTestRule.onNodeWithTag("wipe_mode_checkbox").performClick()

        // Verify confirmation dialog appears
        composeTestRule.onNodeWithText("Confirm Destination Wipe").assertIsDisplayed()

        // Confirm button is disabled until acknowledged
        composeTestRule.onNodeWithTag("confirm_wipe_action_btn").assertIsNotEnabled()

        // Check acknowledgment
        composeTestRule.onNodeWithTag("wipe_confirm_ack_checkbox").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("confirm_wipe_action_btn").assertIsEnabled().performClick()

        // Dialog should be dismissed
        composeTestRule.onNodeWithText("Confirm Destination Wipe").assertDoesNotExist()
    }

    @Test
    fun executeUpload_withScannedFiles_callsOnStartUpload() {
        var uploadDest: String? = null
        var uploadCommitMsg: String? = null
        var uploadIsWipe: Boolean? = null
        var uploadWipeMode: WipeMode? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = "releases/v1",
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = listOf(sampleScannedFile),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { dest, msg, isWipe, mode ->
                        uploadDest = dest
                        uploadCommitMsg = msg
                        uploadIsWipe = isWipe
                        uploadWipeMode = mode
                    },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        scrollToTag("card_upload_commit")
        composeTestRule.onNodeWithTag("execute_upload_button").performClick()

        assertEquals("releases/v1", uploadDest)
        assertEquals("Upload 1 files via GitHub File Manager", uploadCommitMsg)
        assertEquals(false, uploadIsWipe)
        assertEquals(WipeMode.NONE, uploadWipeMode)
    }

    @Test
    fun browseGitHubPathButton_opensPathPickerDialog() {
        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = "",
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = emptyList(),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { _, _, _, _ -> },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _ -> },
                    onFetchDirectory = { _, _, _, _ -> Result.success(emptyList()) }
                )
            }
        }

        scrollToTag("card_upload_destination")
        composeTestRule.onNodeWithTag("upload_browse_path_button").performClick()
        composeTestRule.onNodeWithTag("github_path_picker_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Browse GitHub Destination").assertIsDisplayed()
    }
}
