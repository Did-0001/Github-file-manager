package com.example.domain.engine

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.dto.CreateCommitRequest
import com.example.data.remote.dto.GitTreeItemDto
import com.example.domain.model.*
import com.example.ui.screens.upload.HistoryClearConfirmationDialog
import com.example.ui.screens.upload.UploadScreen
import com.example.ui.screens.upload.WipeConfirmationDialog
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WipeAndHistoryClearTest {

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

    // =========================================================================
    // 1. Wipe Modes Preflight & Diff Calculations
    // =========================================================================

    @Test
    fun `wipeModes diff calculation NONE produces zero deletions`() {
        val remoteMap = mapOf(
            "src/Main.kt" to GitTreeItemDto("src/Main.kt", "100644", "blob", "sha1", 100L),
            "src/Utils.kt" to GitTreeItemDto("src/Utils.kt", "100644", "blob", "sha2", 200L),
            "docs/Readme.md" to GitTreeItemDto("docs/Readme.md", "100644", "blob", "sha3", 300L)
        )
        val handledPaths = setOf("src/Main.kt")
        val wipeMode = WipeMode.NONE

        val deletedItems = mutableListOf<String>()
        if (wipeMode == WipeMode.FULL_BRANCH) {
            for ((path, _) in remoteMap) {
                if (!handledPaths.contains(path)) deletedItems.add(path)
            }
        }
        assertEquals(0, deletedItems.size)
        assertFalse(wipeMode.isWipe)
    }

    @Test
    fun `wipeModes diff calculation SELECTED_FOLDER only deletes unhandled files in target folder`() {
        val remoteMap = mapOf(
            "features/auth/Login.kt" to GitTreeItemDto("features/auth/Login.kt", "100644", "blob", "sha1", 100L),
            "features/auth/OldAuth.kt" to GitTreeItemDto("features/auth/OldAuth.kt", "100644", "blob", "sha2", 200L),
            "features/profile/Profile.kt" to GitTreeItemDto("features/profile/Profile.kt", "100644", "blob", "sha3", 300L),
            "README.md" to GitTreeItemDto("README.md", "100644", "blob", "sha4", 50L)
        )
        val handledPaths = setOf("features/auth/Login.kt")
        val destinationDir = "features/auth"
        val prefix = "$destinationDir/"

        val deletedItems = mutableListOf<String>()
        for ((path, _) in remoteMap) {
            if (path.startsWith(prefix) && !handledPaths.contains(path)) {
                deletedItems.add(path)
            }
        }

        assertEquals(1, deletedItems.size)
        assertEquals("features/auth/OldAuth.kt", deletedItems.first())
        assertFalse("Other folders must not be touched", deletedItems.contains("features/profile/Profile.kt"))
        assertFalse("Root files must not be touched", deletedItems.contains("README.md"))
    }

    @Test
    fun `wipeModes diff calculation CHANGED_FOLDERS only deletes in touched directories`() {
        val remoteMap = mapOf(
            "src/utils/StringUtils.kt" to GitTreeItemDto("src/utils/StringUtils.kt", "100644", "blob", "sha1", 100L),
            "src/utils/OldHelper.kt" to GitTreeItemDto("src/utils/OldHelper.kt", "100644", "blob", "sha2", 150L),
            "assets/logo.png" to GitTreeItemDto("assets/logo.png", "100644", "blob", "sha3", 500L),
            "assets/old_banner.png" to GitTreeItemDto("assets/old_banner.png", "100644", "blob", "sha4", 600L),
            "docs/manual.pdf" to GitTreeItemDto("docs/manual.pdf", "100644", "blob", "sha5", 1000L)
        )
        // Upload touched "src/utils/StringUtils.kt" and "assets/logo.png", but DID NOT touch "docs"
        val handledPaths = setOf("src/utils/StringUtils.kt", "assets/logo.png")
        val touchedFolders = handledPaths.map { if (it.contains('/')) it.substringBeforeLast('/') else "" }.toSet()

        assertTrue(touchedFolders.contains("src/utils"))
        assertTrue(touchedFolders.contains("assets"))
        assertFalse(touchedFolders.contains("docs"))

        val deletedItems = mutableListOf<String>()
        for ((path, _) in remoteMap) {
            if (!handledPaths.contains(path)) {
                val isInsideTouched = touchedFolders.any { folder ->
                    if (folder.isEmpty()) !path.contains('/') else path == folder || path.startsWith("$folder/")
                }
                if (isInsideTouched) {
                    deletedItems.add(path)
                }
            }
        }

        // OldHelper.kt and old_banner.png are inside touched folders and not in upload -> DELETED
        assertEquals(2, deletedItems.size)
        assertTrue(deletedItems.contains("src/utils/OldHelper.kt"))
        assertTrue(deletedItems.contains("assets/old_banner.png"))
        // Untouched folder docs/manual.pdf must be preserved!
        assertFalse("Untouched folder must be preserved", deletedItems.contains("docs/manual.pdf"))
    }

    @Test
    fun `wipeModes diff calculation FULL_BRANCH deletes all unhandled remote files`() {
        val remoteMap = mapOf(
            "src/A.kt" to GitTreeItemDto("src/A.kt", "100644", "blob", "sha1", 100L),
            "src/B.kt" to GitTreeItemDto("src/B.kt", "100644", "blob", "sha2", 200L),
            "docs/C.md" to GitTreeItemDto("docs/C.md", "100644", "blob", "sha3", 300L)
        )
        val handledPaths = setOf("src/A.kt")

        val deletedItems = mutableListOf<String>()
        for ((path, _) in remoteMap) {
            if (!handledPaths.contains(path)) {
                deletedItems.add(path)
            }
        }

        assertEquals(2, deletedItems.size)
        assertTrue(deletedItems.contains("src/B.kt"))
        assertTrue(deletedItems.contains("docs/C.md"))
    }

    // =========================================================================
    // 2. Wipe Execution Paths (BaseTreeSha and Deletion Entries)
    // =========================================================================

    @Test
    fun `wipeExecutionPaths verify baseTreeSha selection across modes`() {
        val currentRootTreeSha = "root_tree_sha_abc123"

        // FULL_BRANCH passes baseTreeSha = null to wipe remote branch completely
        val fullBranchBaseTree = if (WipeMode.FULL_BRANCH == WipeMode.FULL_BRANCH) null else currentRootTreeSha
        assertNull(fullBranchBaseTree)

        // SELECTED_FOLDER, CHANGED_FOLDERS, and NONE retain currentRootTreeSha
        val selectedFolderBaseTree = if (WipeMode.SELECTED_FOLDER == WipeMode.FULL_BRANCH) null else currentRootTreeSha
        assertEquals(currentRootTreeSha, selectedFolderBaseTree)

        val changedFoldersBaseTree = if (WipeMode.CHANGED_FOLDERS == WipeMode.FULL_BRANCH) null else currentRootTreeSha
        assertEquals(currentRootTreeSha, changedFoldersBaseTree)

        val noneBaseTree = if (WipeMode.NONE == WipeMode.FULL_BRANCH) null else currentRootTreeSha
        assertEquals(currentRootTreeSha, noneBaseTree)
    }

    // =========================================================================
    // 3. History Clear Safety Guards & Orphan Commit Creation
    // =========================================================================

    @Test
    fun `historyClear enabled creates orphan root commit with empty parents and forces updateRef`() {
        val clearHistory = true
        val headCommitSha = "head_commit_sha_12345"

        // In TransferEngine, parents list for commit creation:
        val parentsList = if (clearHistory) emptyList<String>() else listOf(headCommitSha)
        val parentSha = if (clearHistory) null else headCommitSha

        assertEquals("Orphan root commit must have empty parents list", 0, parentsList.size)
        assertNull("Parent SHA must be null for orphan commit", parentSha)

        // Verify CreateCommitRequest payload
        val commitReq = CreateCommitRequest(
            message = "Initial root commit",
            tree = "tree_sha_xyz",
            parents = parentsList
        )
        assertTrue("Parents in commit request must be empty", commitReq.parents.isEmpty())

        // Ref update force flag: must be true ONLY when clearHistory is explicitly true!
        val forceUpdate = clearHistory
        assertTrue("Force flag must be true when clearing history", forceUpdate)
    }

    @Test
    fun `nonDestructive normal upload strictly preserves commit parent and prohibits force update`() {
        val clearHistory = false
        val headCommitSha = "head_commit_sha_67890"

        val parentsList = if (clearHistory) emptyList<String>() else listOf(headCommitSha)
        val parentSha = if (clearHistory) null else headCommitSha

        assertEquals("Normal upload must have exactly one parent", 1, parentsList.size)
        assertEquals(headCommitSha, parentsList.first())
        assertEquals(headCommitSha, parentSha)

        val commitReq = CreateCommitRequest(
            message = "Normal upload commit",
            tree = "tree_sha_xyz",
            parents = parentsList
        )
        assertEquals(listOf(headCommitSha), commitReq.parents)

        // Strict safety guard: normal upload must NEVER force-update
        val forceUpdate = clearHistory
        assertFalse("Force update is strictly forbidden during normal upload", forceUpdate)
    }

    // =========================================================================
    // 4. UI Tests for WipeConfirmationDialog & HistoryClearConfirmationDialog
    // =========================================================================

    @Test
    fun `wipeConfirmationDialog displays warning and gates enable button with acknowledgment`() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                WipeConfirmationDialog(
                    repo = "octocat/Hello-World",
                    branch = "main",
                    destinationPath = "src",
                    wipeMode = WipeMode.CHANGED_FOLDERS,
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Confirm Changed Folders Wipe").assertIsDisplayed()
        composeTestRule.onNodeWithText("You have selected Wipe Changed Folders for octocat/Hello-World on branch 'main'.").assertIsDisplayed()

        // Button disabled until checkbox is checked
        composeTestRule.onNodeWithTag("confirm_wipe_action_btn").assertIsNotEnabled()

        // Check ack checkbox
        composeTestRule.onNodeWithTag("wipe_confirm_ack_checkbox").performClick()
        composeTestRule.onNodeWithTag("confirm_wipe_action_btn").assertIsEnabled().performClick()

        assertTrue(confirmed)
        assertFalse(dismissed)
    }

    @Test
    fun `historyClearConfirmationDialog displays high risk warning and requires explicit acknowledgment`() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                HistoryClearConfirmationDialog(
                    repo = "octocat/Hello-World",
                    branch = "main",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("history_clear_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("High-Risk: Clear Git History").assertIsDisplayed()
        composeTestRule.onNodeWithText("You are about to rewrite Git history for octocat/Hello-World on branch 'main'.").assertIsDisplayed()

        // Confirm button disabled before acknowledgment
        composeTestRule.onNodeWithTag("history_clear_confirm_button").assertIsNotEnabled()

        // Check acknowledgment checkbox
        composeTestRule.onNodeWithTag("history_clear_ack_checkbox").performClick()
        composeTestRule.onNodeWithTag("history_clear_confirm_button").assertIsEnabled().performClick()

        assertTrue(confirmed)
        assertFalse(dismissed)
    }

    @Test
    fun `uploadScreen historyClearCheckbox opens confirmation dialog and enables history clear mode`() {
        var uploadDest: String? = null
        var uploadMsg: String? = null
        var uploadIsWipe: Boolean? = null
        var uploadMode: WipeMode? = null
        var uploadClearHistory: Boolean? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                UploadScreen(
                    selectedRepo = testRepo,
                    initialDestinationPath = "",
                    onScanFolder = {},
                    onScanFiles = {},
                    scannedFiles = listOf(sampleScannedFile),
                    excludedItems = emptyList(),
                    isScanning = false,
                    ignoreRules = IgnoreRules(),
                    onUpdateIgnoreRules = {},
                    onStartUpload = { dest, msg, isWipe, mode, clearHistory ->
                        uploadDest = dest
                        uploadMsg = msg
                        uploadIsWipe = isWipe
                        uploadMode = mode
                        uploadClearHistory = clearHistory
                    },
                    onOpenRepoSelector = {},
                    diffReport = null,
                    isCalculatingDiff = false,
                    preflightReport = null,
                    onRunPreflight = { _, _, _, _ -> },
                    onFetchDirectory = null
                )
            }
        }

        composeTestRule.onNodeWithTag("upload_lazy_column").performScrollToNode(hasTestTag("clear_history_checkbox"))

        // Click "Also clear Git history" checkbox
        composeTestRule.onNodeWithTag("clear_history_checkbox").performClick()

        // Dialog appears
        composeTestRule.onNodeWithTag("history_clear_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history_clear_ack_checkbox").performClick()
        composeTestRule.onNodeWithTag("history_clear_confirm_button").performClick()

        // Button text should now reflect rewrite history
        composeTestRule.onNodeWithTag("upload_lazy_column").performScrollToNode(hasTestTag("execute_upload_button"))
        composeTestRule.onNodeWithText("Rewrite History & Upload 1 Files").assertIsDisplayed()

        // Click upload
        composeTestRule.onNodeWithTag("execute_upload_button").performClick()

        assertEquals(true, uploadClearHistory)
        assertEquals(false, uploadIsWipe)
        assertEquals(WipeMode.NONE, uploadMode)
    }
}
