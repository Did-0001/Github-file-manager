package com.example.ui.screens.transfers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransfersScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyTransfers_displaysEmptyState() {
        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = emptyList(),
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = {},
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithText("No Transfer History").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transfer Center").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 active • 0 finished").assertIsDisplayed()
        composeTestRule.onNodeWithTag("clear_all_transfers_btn").assertDoesNotExist()
    }

    @Test
    fun activeUploadTransfer_displaysProgressAndHandlesPause() {
        var pausedId: String? = null

        val uploadTransfer = TransferEntity(
            id = "upload_1",
            type = "UPLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "/storage/emulated/0/Uploads",
            destPath = "src/code",
            status = "UPLOADING",
            totalFiles = 10,
            processedFiles = 4,
            totalBytes = 10000L,
            processedBytes = 4000L,
            currentFile = "src/code/App.kt"
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(uploadTransfer),
                    onPauseTransfer = { pausedId = it },
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = {},
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 1024L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("active_transfer_upload_1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active Transfers").assertIsDisplayed()
        composeTestRule.onNodeWithText("UPLOAD • octocat/Hello-World").assertIsDisplayed()
        composeTestRule.onNodeWithText("4 of 10 files (3.9 KB)").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.0 KB/s").assertIsDisplayed()
        composeTestRule.onNodeWithText("Current: src/code/App.kt").assertIsDisplayed()

        composeTestRule.onNodeWithTag("pause_transfer_btn_upload_1").assertIsDisplayed().performClick()
        assertEquals("upload_1", pausedId)
    }

    @Test
    fun activePausedTransfer_displaysResumeButtonAndHandlesResume() {
        var resumedId: String? = null

        val pausedTransfer = TransferEntity(
            id = "download_paused",
            type = "DOWNLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "docs/",
            destPath = "/storage/downloads",
            status = "PAUSED",
            totalFiles = 5,
            processedFiles = 2,
            totalBytes = 5000L,
            processedBytes = 2000L
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(pausedTransfer),
                    onPauseTransfer = {},
                    onResumeTransfer = { resumedId = it },
                    onCancelTransfer = {},
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = {},
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("resume_transfer_btn_download_paused").assertIsDisplayed().performClick()
        assertEquals("download_paused", resumedId)
    }

    @Test
    fun activeTransfer_handlesCancel() {
        var cancelledId: String? = null

        val activeTransfer = TransferEntity(
            id = "cancel_test_id",
            type = "UPLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "/data",
            destPath = "remote",
            status = "PREPARING",
            totalFiles = 1,
            processedFiles = 0
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(activeTransfer),
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = { cancelledId = it },
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = {},
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("cancel_transfer_btn_cancel_test_id").assertIsDisplayed().performClick()
        assertEquals("cancel_test_id", cancelledId)
    }

    @Test
    fun activeTransfer_viewItems_opensItemsDialogAndDisplaysItems() {
        val activeTransfer = TransferEntity(
            id = "inspect_test_id",
            type = "UPLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "/data",
            destPath = "remote",
            status = "UPLOADING",
            totalFiles = 1,
            processedFiles = 0
        )

        val items = listOf(
            TransferItemEntity(
                id = 101L,
                transferId = "inspect_test_id",
                relativePath = "src/main/Index.kt",
                githubPath = "remote/src/main/Index.kt",
                sizeBytes = 2048L,
                status = "SUCCESS",
                blobSha = "1234567890abcdef"
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(activeTransfer),
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = {},
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(items) }
                )
            }
        }

        composeTestRule.onNodeWithTag("view_items_btn_inspect_test_id").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Transfer Items (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("src/main/Index.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("Blob: 1234567").assertIsDisplayed()

        // Close dialog
        composeTestRule.onNodeWithTag("close_items_dialog_btn").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Transfer Items (1)").assertDoesNotExist()
    }

    @Test
    fun pastCompletedTransfer_displaysCommitShaAndDetails() {
        val completedTransfer = TransferEntity(
            id = "past_completed_1",
            type = "UPLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "/data",
            destPath = "target/",
            status = "COMPLETED",
            totalFiles = 3,
            totalBytes = 1500L,
            commitSha = "c0ffee1234567890"
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(completedTransfer),
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = {},
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("past_transfer_past_completed_1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transfer History").assertIsDisplayed()
        composeTestRule.onNodeWithText("Commit: c0ffee12").assertIsDisplayed()
        composeTestRule.onNodeWithTag("details_transfer_btn_past_completed_1").assertIsDisplayed()
    }

    @Test
    fun pastFailedTransfer_displaysErrorAndHandlesRetryDialog() {
        var retryTransferId: String? = null
        var retryFailedOnly: Boolean? = null

        val failedTransfer = TransferEntity(
            id = "past_failed_1",
            type = "UPLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "/data",
            destPath = "target/",
            status = "FAILED",
            totalFiles = 2,
            totalBytes = 800L,
            errorMessage = "GitHub API rate limit exceeded"
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(failedTransfer),
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onRetryTransfer = { id, failedOnly ->
                        retryTransferId = id
                        retryFailedOnly = failedOnly
                    },
                    onDeleteTransfer = {},
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithText("Error: GitHub API rate limit exceeded").assertIsDisplayed()
        composeTestRule.onNodeWithTag("retry_transfer_btn_past_failed_1").assertIsDisplayed().performClick()

        // Verify retry dialog appears
        composeTestRule.onNodeWithText("Retry Transfer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("retry_failed_files_btn").assertIsDisplayed().performClick()

        assertEquals("past_failed_1", retryTransferId)
        assertEquals(true, retryFailedOnly)
    }

    @Test
    fun pastTransfer_handlesDelete() {
        var deletedId: String? = null

        val completedTransfer = TransferEntity(
            id = "delete_me_1",
            type = "DOWNLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "files/",
            destPath = "/storage/downloads",
            status = "COMPLETED",
            totalFiles = 1,
            totalBytes = 200L
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(completedTransfer),
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = { deletedId = it },
                    onClearCompleted = {},
                    onClearAll = {},
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("delete_transfer_btn_delete_me_1").assertIsDisplayed().performClick()
        assertEquals("delete_me_1", deletedId)
    }

    @Test
    fun clearActions_triggersCallbacks() {
        var clearCompletedCalled = false
        var clearAllCalled = false

        val activeTransfer = TransferEntity(
            id = "active_1",
            type = "UPLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "/data",
            destPath = "remote",
            status = "UPLOADING",
            totalFiles = 1,
            processedFiles = 0
        )

        val completedTransfer = TransferEntity(
            id = "completed_1",
            type = "DOWNLOAD",
            repoOwner = "octocat",
            repoName = "Hello-World",
            branch = "main",
            sourcePath = "files/",
            destPath = "/storage/downloads",
            status = "COMPLETED",
            totalFiles = 1,
            totalBytes = 200L
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                TransfersScreen(
                    transfers = listOf(activeTransfer, completedTransfer),
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onRetryTransfer = { _, _ -> },
                    onDeleteTransfer = {},
                    onClearCompleted = { clearCompletedCalled = true },
                    onClearAll = { clearAllCalled = true },
                    getSpeedForTransfer = { 0L },
                    getItemsForTransfer = { flowOf(emptyList()) }
                )
            }
        }

        composeTestRule.onNodeWithTag("clear_completed_transfers_btn").assertIsDisplayed().performClick()
        assertTrue(clearCompletedCalled)

        composeTestRule.onNodeWithTag("clear_all_transfers_btn").assertIsDisplayed().performClick()
        assertTrue(clearAllCalled)
    }
}
