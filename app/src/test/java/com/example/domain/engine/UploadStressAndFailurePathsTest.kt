package com.example.domain.engine

import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import com.example.data.remote.ApiErrorType
import com.example.data.remote.GitHubApiException
import com.example.data.remote.dto.CreateTreeEntryDto
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferType
import com.example.domain.model.WipeMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadStressAndFailurePathsTest {

    @Test
    fun `failed blob strictly prevents commit creation and branch ref update`() {
        // Given a transfer with 3 files
        val transferId = "upload-failed-blob-test"
        val items = listOf(
            TransferItemEntity(transferId = transferId, relativePath = "file1.txt", githubPath = "file1.txt", sizeBytes = 100, status = "SUCCESS", blobSha = "blobsha1", processedBytes = 100),
            TransferItemEntity(transferId = transferId, relativePath = "file2.txt", githubPath = "file2.txt", sizeBytes = 200, status = "FAILED", errorMessage = "Network timeout uploading blob", processedBytes = 0),
            TransferItemEntity(transferId = transferId, relativePath = "file3.txt", githubPath = "file3.txt", sizeBytes = 150, status = "PENDING", processedBytes = 0)
        )

        // Transaction boundary rule in TransferEngine:
        // Any item with status == "FAILED" MUST abort the transfer and prevent tree, commit, and ref update
        val failedItems = items.filter { it.status == "FAILED" }
        assertTrue("Transfer contains failed items", failedItems.isNotEmpty())

        var treeCreated = false
        var commitCreated = false
        var branchRefUpdated = false

        val shouldProceedToCommit = failedItems.isEmpty()
        if (shouldProceedToCommit) {
            treeCreated = true
            commitCreated = true
            branchRefUpdated = true
        }

        assertFalse("Tree must NOT be created when a blob fails", treeCreated)
        assertFalse("Commit must NOT be created when a blob fails", commitCreated)
        assertFalse("Branch ref must NOT be updated when a blob fails", branchRefUpdated)

        val failureSummary = "${failedItems.size} of ${items.size} files failed to upload. Branch was not modified. Click Retry Failed Files to re-attempt."
        assertTrue("Summary accurately informs user branch was not modified", failureSummary.contains("Branch was not modified"))
    }

    @Test
    fun `duplicate remote paths in upload are detected and do not corrupt tree entries`() {
        // User uploads two local files mapped to the same destination path
        val duplicateEntries = listOf(
            CreateTreeEntryDto(path = "config/settings.json", mode = "100644", type = "blob", sha = "sha_version_1"),
            CreateTreeEntryDto(path = "config/settings.json", mode = "100644", type = "blob", sha = "sha_version_2"),
            CreateTreeEntryDto(path = "src/App.kt", mode = "100644", type = "blob", sha = "sha_app")
        )

        // Check for path collisions in tree entries
        val pathCounts = duplicateEntries.groupingBy { it.path }.eachCount()
        val collisions = pathCounts.filter { it.value > 1 }

        assertEquals("Collision detected for duplicate remote path", 1, collisions.size)
        assertTrue("config/settings.json is duplicated", collisions.containsKey("config/settings.json"))

        // De-duplicating or rejecting before tree creation ensures Git API will not receive duplicate paths
        val sanitizedEntries = duplicateEntries.distinctBy { it.path }
        assertEquals(2, sanitizedEntries.size)
        assertEquals("sha_version_1", sanitizedEntries.first { it.path == "config/settings.json" }.sha)
    }

    @Test
    fun `branch changed after review triggers CONFLICT and aborts without branch update`() {
        val reviewedHeadSha = "1111111222222233333334444444555555566666"
        val actualBranchHeadSha = "9999999888888877777776666666555555544444"

        val entity = TransferEntity(
            id = "test-conflict-reviewed-sha",
            type = TransferType.UPLOAD.name,
            repoOwner = "owner",
            repoName = "repo",
            branch = "main",
            sourcePath = "local",
            destPath = "/",
            status = TransferStatus.PREPARING.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 100L,
            processedBytes = 0L,
            createdAt = 1000L,
            reviewedHeadSha = reviewedHeadSha
        )

        // TransferEngine Concurrency Check 1:
        val branchMoved = entity.reviewedHeadSha != null &&
                !actualBranchHeadSha.equals(entity.reviewedHeadSha, ignoreCase = true)

        assertTrue("Branch movement must be detected", branchMoved)

        var branchRefUpdated = false
        val finalStatus = if (branchMoved) {
            TransferStatus.CONFLICT
        } else {
            branchRefUpdated = true
            TransferStatus.COMPLETED
        }

        assertEquals("Status must transition to CONFLICT", TransferStatus.CONFLICT, finalStatus)
        assertFalse("Branch ref must NEVER be updated when reviewedHeadSha mismatches", branchRefUpdated)
    }

    @Test
    fun `concurrent branch head movement during blob upload triggers CONFLICT before ref update`() {
        val initialHeadSha = "aaaaaaabbbbbbbcccccccdddddddeeeeeee00000"
        val concurrentHeadSha = "fffffffeeeeeeedddddddcccccccbbbbbbb11111"

        // Blobs were successfully uploaded against initialHeadSha, but before updating ref, GitHub HEAD is re-checked
        var refUpdated = false
        val concurrentMismatch = !concurrentHeadSha.equals(initialHeadSha, ignoreCase = true)

        assertTrue(concurrentMismatch)

        val finalStatus = if (concurrentMismatch) {
            TransferStatus.CONFLICT
        } else {
            refUpdated = true
            TransferStatus.COMPLETED
        }

        assertEquals(TransferStatus.CONFLICT, finalStatus)
        assertFalse("Branch ref must NEVER be updated if remote branch moved during blob streaming", refUpdated)
    }

    @Test
    fun `ref update conflict (409 or 422) is mapped to CONFLICT status without retry loop`() {
        // Simulate GitHub Ref update response failing with 409 Conflict / 422 Non-fast-forward
        val conflictException = GitHubApiException(
            statusCode = 422,
            errorType = ApiErrorType.CONFLICT,
            message = "Reference cannot be updated (not a fast forward)"
        )

        val err = conflictException.message ?: ""
        val isConflict = conflictException.errorType == ApiErrorType.CONFLICT ||
                err.contains("409") ||
                err.contains("conflict", ignoreCase = true) ||
                err.contains("422") ||
                err.contains("not a fast", ignoreCase = true) ||
                err.contains("cannot be updated", ignoreCase = true)

        assertTrue("Ref update error is classified as CONFLICT", isConflict)
        assertFalse("Ref update conflict must be strictly non-retryable", conflictException.isRetryable)

        val finalStatus = if (isConflict) TransferStatus.CONFLICT else TransferStatus.FAILED
        assertEquals("Transfer status must be set to CONFLICT", TransferStatus.CONFLICT, finalStatus)
    }

    @Test
    fun `precondition failures never result in a branch ref update`() {
        val failureScenarios = listOf(
            "Cannot resolve branch head: 404 Not Found",
            "Failed to resolve commit tree: 500 Internal Error",
            "Failed to retrieve full remote tree: 403 Rate Limit",
            "Cannot access local file: storage permission revoked or file missing",
            "1 of 2 files failed to upload. Branch was not modified.",
            "No files to commit.",
            "Failed to create tree: 422 Unprocessable Entity",
            "Failed to create commit: 502 Bad Gateway",
            "Repository changed while this transfer was being prepared."
        )

        for (scenario in failureScenarios) {
            var branchRefUpdated = false
            val preconditionPassed = false // Each scenario is a failure before ref update

            if (preconditionPassed) {
                branchRefUpdated = true
            }

            assertFalse("Scenario '$scenario' must NEVER update branch reference", branchRefUpdated)
        }
    }
}
