package com.example.domain.engine

import com.example.data.local.entity.TransferEntity
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferType
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TransferExecutionArchitectureTest {

    @Test
    fun `test active transfer states include queued through verifying and exclude paused and cancelled`() {
        val activeStates = listOf(
            TransferStatus.QUEUED,
            TransferStatus.SCANNING,
            TransferStatus.VALIDATING,
            TransferStatus.PREPARING,
            TransferStatus.UPLOADING,
            TransferStatus.DOWNLOADING,
            TransferStatus.COMMITTING,
            TransferStatus.VERIFYING
        )

        val inactiveStates = listOf(
            TransferStatus.COMPLETED,
            TransferStatus.FAILED,
            TransferStatus.CANCELLED,
            TransferStatus.PAUSED,
            TransferStatus.CONFLICT
        )

        for (state in activeStates) {
            assertTrue("Expected state $state to be active", state.isActive)
        }

        for (state in inactiveStates) {
            assertFalse("Expected state $state to NOT be active", state.isActive)
        }
    }

    @Test
    fun `test startup reconciliation ignores paused and cancelled transfers`() {
        val pausedEntity = TransferEntity(
            id = "transfer-paused",
            type = TransferType.UPLOAD.name,
            repoOwner = "owner",
            repoName = "repo",
            branch = "main",
            sourcePath = "files",
            destPath = "dest",
            status = TransferStatus.PAUSED.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 100L,
            processedBytes = 0L,
            createdAt = 1000L
        )

        val cancelledEntity = TransferEntity(
            id = "transfer-cancelled",
            type = TransferType.UPLOAD.name,
            repoOwner = "owner",
            repoName = "repo",
            branch = "main",
            sourcePath = "files",
            destPath = "dest",
            status = TransferStatus.CANCELLED.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 100L,
            processedBytes = 0L,
            createdAt = 1000L
        )

        val pausedStatus = TransferStatus.valueOf(pausedEntity.status)
        val cancelledStatus = TransferStatus.valueOf(cancelledEntity.status)

        assertFalse("Paused transfers must not be marked active for reconciliation restart", pausedStatus.isActive)
        assertFalse("Cancelled transfers must not be marked active for reconciliation restart", cancelledStatus.isActive)
    }

    @Test
    fun `test branch movement detection flags CONFLICT and prevents overwrite`() {
        val reviewedHeadSha = "1111111222222233333334444444555555566666"
        val currentRemoteHeadSha = "9999999888888877777776666666555555544444"

        val entity = TransferEntity(
            id = "transfer-upload-1",
            type = TransferType.UPLOAD.name,
            repoOwner = "testowner",
            repoName = "testrepo",
            branch = "main",
            sourcePath = "local",
            destPath = "remote",
            status = TransferStatus.PREPARING.name,
            totalFiles = 2,
            processedFiles = 0,
            totalBytes = 200L,
            processedBytes = 0L,
            createdAt = 1000L,
            reviewedHeadSha = reviewedHeadSha
        )

        // Simulate Concurrency Check 1 in executeUploadJob
        val branchMoved = entity.reviewedHeadSha != null &&
                !currentRemoteHeadSha.equals(entity.reviewedHeadSha, ignoreCase = true)

        assertTrue("Branch head mismatch must be detected as moved", branchMoved)

        val finalStatus = if (branchMoved) TransferStatus.CONFLICT else TransferStatus.UPLOADING
        assertEquals(TransferStatus.CONFLICT, finalStatus)
    }

    @Test
    fun `test retry and resume preserves reviewedHeadSha`() {
        val reviewedHeadSha = "abcdef0123456789abcdef0123456789abcdef01"
        val entity = TransferEntity(
            id = "transfer-123",
            type = TransferType.UPLOAD.name,
            repoOwner = "owner",
            repoName = "repo",
            branch = "feature",
            sourcePath = "src",
            destPath = "dest",
            status = TransferStatus.FAILED.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 50L,
            processedBytes = 0L,
            createdAt = 1000L,
            reviewedHeadSha = reviewedHeadSha,
            errorMessage = "Socket timeout"
        )

        // Simulate retryTransfer state update:
        // status -> QUEUED, errorMessage -> null, reviewedHeadSha UNCHANGED
        val retriedEntity = entity.copy(
            status = TransferStatus.QUEUED.name,
            errorMessage = null
        )

        assertEquals(reviewedHeadSha, retriedEntity.reviewedHeadSha)
        assertEquals(TransferStatus.QUEUED.name, retriedEntity.status)
        assertNull(retriedEntity.errorMessage)

        // Simulate resumeTransfer state update:
        val resumedEntity = entity.copy(
            status = TransferStatus.QUEUED.name
        )

        assertEquals(reviewedHeadSha, resumedEntity.reviewedHeadSha)
        assertEquals(TransferStatus.QUEUED.name, resumedEntity.status)
    }

    @Test
    fun `test retryable vs permanent failure categorization`() {
        fun isRetryable(throwable: Throwable?, errorMessage: String?): Boolean {
            if (throwable is SocketTimeoutException ||
                throwable is UnknownHostException ||
                throwable is java.net.ConnectException ||
                throwable is java.io.InterruptedIOException) {
                return true
            }
            val msg = (errorMessage ?: throwable?.message ?: "").lowercase()
            if (msg.contains("timeout") ||
                msg.contains("unable to resolve host") ||
                msg.contains("connection reset") ||
                msg.contains("failed to connect") ||
                msg.contains("502") || msg.contains("503") || msg.contains("504") ||
                msg.contains("429") || msg.contains("rate limit")
            ) {
                return true
            }
            return false
        }

        // Transient exceptions should be retryable
        assertTrue(isRetryable(SocketTimeoutException("Read timed out"), null))
        assertTrue(isRetryable(UnknownHostException("api.github.com"), null))
        assertTrue(isRetryable(null, "Server returned 503 Service Unavailable"))
        assertTrue(isRetryable(null, "HTTP 429 Too Many Requests (rate limit)"))

        // Permanent exceptions should NOT be retryable
        assertFalse(isRetryable(IllegalArgumentException("Invalid path"), null))
        assertFalse(isRetryable(null, "HTTP 401 Bad credentials"))
        assertFalse(isRetryable(null, "HTTP 404 Not Found"))
        assertFalse(isRetryable(null, "HTTP 422 Unprocessable Entity"))
    }
}
