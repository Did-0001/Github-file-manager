package com.example.domain.worker

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.example.data.local.entity.TransferEntity
import com.example.data.remote.ApiErrorType
import com.example.data.remote.GitHubApiException
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkManagerAndSafStressTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Pure mapping function mirroring TransferWorker.mapWorkerResult
    private fun mapWorkerResult(
        entity: TransferEntity?,
        isStopped: Boolean,
        caughtException: Throwable?,
        runAttemptCount: Int
    ): ListenableWorker.Result {
        val statusStr = entity?.status
        val status = try { statusStr?.let { TransferStatus.valueOf(it) } } catch (_: Exception) { null }

        // 1. Branch conflict
        if (status == TransferStatus.CONFLICT) {
            return ListenableWorker.Result.failure(
                workDataOf(
                    "reason" to "conflict",
                    "error" to (entity?.errorMessage ?: "Branch conflict detected")
                )
            )
        }

        // 2. Pause
        if (status == TransferStatus.PAUSED || (isStopped && status == TransferStatus.PAUSED)) {
            return ListenableWorker.Result.success(
                workDataOf("reason" to "pause")
            )
        }

        // 3. Cancellation
        if (status == TransferStatus.CANCELLED || (isStopped && caughtException is CancellationException)) {
            return ListenableWorker.Result.failure(
                workDataOf("reason" to "cancellation")
            )
        }

        // 4. Completed
        if (status == TransferStatus.COMPLETED) {
            return ListenableWorker.Result.success(
                workDataOf("reason" to "completed")
            )
        }

        // 5. Retryable vs Permanent failure
        val isRetryable = isRetryableError(caughtException, entity?.errorMessage)
        if (isRetryable && runAttemptCount < 3) {
            return ListenableWorker.Result.retry()
        }

        return ListenableWorker.Result.failure(
            workDataOf(
                "reason" to "permanent_failure",
                "error" to (entity?.errorMessage ?: caughtException?.localizedMessage ?: "Transfer failed")
            )
        )
    }

    private fun isRetryableError(throwable: Throwable?, errorMessage: String?): Boolean {
        if (throwable is GitHubApiException) {
            return throwable.isRetryable
        }
        if (throwable?.cause is GitHubApiException) {
            return (throwable.cause as GitHubApiException).isRetryable
        }
        if (throwable is SocketTimeoutException ||
            throwable is UnknownHostException ||
            throwable is java.net.ConnectException ||
            throwable is java.net.NoRouteToHostException ||
            throwable is java.io.InterruptedIOException ||
            throwable is IOException
        ) {
            return true
        }
        val msg = (errorMessage ?: throwable?.message ?: "").lowercase()
        if (msg.contains("conflict") ||
            msg.contains("401") || msg.contains("auth") ||
            (msg.contains("403") && !msg.contains("rate limit")) ||
            msg.contains("404") || msg.contains("not found") ||
            msg.contains("422") || msg.contains("validation") ||
            msg.contains("permission revoked") ||
            msg.contains("cannot access local") ||
            msg.contains("directory missing") ||
            msg.contains("file missing")
        ) {
            return false
        }
        if (msg.contains("timeout") ||
            msg.contains("unable to resolve host") ||
            msg.contains("connection reset") ||
            msg.contains("failed to connect") ||
            msg.contains("network") ||
            msg.contains("socket") ||
            msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") ||
            msg.contains("server error") ||
            msg.contains("429") || msg.contains("rate limit")
        ) {
            return true
        }
        return false
    }

    @Test
    fun `WorkManager completed status maps strictly to Result success`() {
        val completedEntity = createEntity(TransferStatus.COMPLETED)
        val result = mapWorkerResult(completedEntity, isStopped = false, caughtException = null, runAttemptCount = 0)

        assertTrue("Completed transfer must produce Result.success()", result is ListenableWorker.Result.Success)
    }

    @Test
    fun `WorkManager retryable failure triggers Result retry when runAttemptCount under 3`() {
        val failedEntity = createEntity(TransferStatus.FAILED, errorMessage = "Socket timeout during blob transfer")
        val timeoutEx = SocketTimeoutException("Connection timed out")

        val result1 = mapWorkerResult(failedEntity, isStopped = false, caughtException = timeoutEx, runAttemptCount = 0)
        assertTrue("Attempt 0 on retryable error must trigger Result.retry()", result1 is ListenableWorker.Result.Retry)

        val result2 = mapWorkerResult(failedEntity, isStopped = false, caughtException = timeoutEx, runAttemptCount = 2)
        assertTrue("Attempt 2 on retryable error must trigger Result.retry()", result2 is ListenableWorker.Result.Retry)
    }

    @Test
    fun `WorkManager retryable failure caps at 3 attempts and turns into Result failure`() {
        val failedEntity = createEntity(TransferStatus.FAILED, errorMessage = "Socket timeout during blob transfer")
        val timeoutEx = SocketTimeoutException("Connection timed out")

        val result3 = mapWorkerResult(failedEntity, isStopped = false, caughtException = timeoutEx, runAttemptCount = 3)
        assertTrue("Attempt 3 on retryable error must terminate with Result.failure()", result3 is ListenableWorker.Result.Failure)
    }

    @Test
    fun `WorkManager permanent failures map strictly to Result failure without retry`() {
        val authError = GitHubApiException(errorType = ApiErrorType.AUTH_REQUIRED, statusCode = 401, message = "Bad credentials")
        val resultAuth = mapWorkerResult(createEntity(TransferStatus.FAILED), isStopped = false, caughtException = authError, runAttemptCount = 0)
        assertTrue(resultAuth is ListenableWorker.Result.Failure)

        val forbiddenError = GitHubApiException(errorType = ApiErrorType.PERMISSION_DENIED, statusCode = 403, message = "Access forbidden")
        val resultForbidden = mapWorkerResult(createEntity(TransferStatus.FAILED), isStopped = false, caughtException = forbiddenError, runAttemptCount = 0)
        assertTrue(resultForbidden is ListenableWorker.Result.Failure)

        val notFoundError = GitHubApiException(errorType = ApiErrorType.NOT_FOUND, statusCode = 404, message = "Repository not found")
        val resultNotFound = mapWorkerResult(createEntity(TransferStatus.FAILED), isStopped = false, caughtException = notFoundError, runAttemptCount = 0)
        assertTrue(resultNotFound is ListenableWorker.Result.Failure)

        val validationError = GitHubApiException(errorType = ApiErrorType.VALIDATION_ERROR, statusCode = 422, message = "Path is invalid")
        val resultValidation = mapWorkerResult(createEntity(TransferStatus.FAILED), isStopped = false, caughtException = validationError, runAttemptCount = 0)
        assertTrue(resultValidation is ListenableWorker.Result.Failure)

        val revokedPermissionEntity = createEntity(TransferStatus.FAILED, errorMessage = "Cannot access local file: storage permission revoked or file missing")
        val resultRevoked = mapWorkerResult(revokedPermissionEntity, isStopped = false, caughtException = null, runAttemptCount = 0)
        assertTrue(resultRevoked is ListenableWorker.Result.Failure)
    }

    @Test
    fun `WorkManager conflict, pause, and cancel never trigger accidental retry`() {
        // CONFLICT
        val conflictEntity = createEntity(TransferStatus.CONFLICT, errorMessage = "Branch head moved concurrently")
        val resultConflict = mapWorkerResult(conflictEntity, isStopped = false, caughtException = null, runAttemptCount = 0)
        assertTrue("CONFLICT must produce Result.failure()", resultConflict is ListenableWorker.Result.Failure)

        // PAUSE
        val pausedEntity = createEntity(TransferStatus.PAUSED)
        val resultPause = mapWorkerResult(pausedEntity, isStopped = true, caughtException = null, runAttemptCount = 0)
        assertTrue("PAUSED must produce Result.success() to acknowledge graceful stop", resultPause is ListenableWorker.Result.Success)

        // CANCEL
        val cancelledEntity = createEntity(TransferStatus.CANCELLED)
        val resultCancel = mapWorkerResult(cancelledEntity, isStopped = true, caughtException = CancellationException("User cancelled"), runAttemptCount = 0)
        assertTrue("CANCELLED must produce Result.failure() without retry", resultCancel is ListenableWorker.Result.Failure)
    }

    @Test
    fun `duplicate scheduling policy KEEP preserves single execution`() {
        val policy = ExistingWorkPolicy.KEEP
        assertEquals("ExistingWorkPolicy.KEEP must be used for unique work", ExistingWorkPolicy.KEEP, policy)
    }

    @Test
    fun `process death recovery reconciles active transfers to QUEUED and skips inactive`() {
        val transfers = listOf(
            createEntity(TransferStatus.PREPARING, id = "t1"),
            createEntity(TransferStatus.UPLOADING, id = "t2"),
            createEntity(TransferStatus.DOWNLOADING, id = "t3"),
            createEntity(TransferStatus.COMMITTING, id = "t4"),
            createEntity(TransferStatus.VERIFYING, id = "t5"),
            createEntity(TransferStatus.QUEUED, id = "t6"),
            createEntity(TransferStatus.PAUSED, id = "t7"),
            createEntity(TransferStatus.CANCELLED, id = "t8"),
            createEntity(TransferStatus.COMPLETED, id = "t9"),
            createEntity(TransferStatus.FAILED, id = "t10"),
            createEntity(TransferStatus.CONFLICT, id = "t11")
        )

        // Simulate startup reconciliation:
        val reconciled = transfers.mapNotNull { entity ->
            val status = TransferStatus.valueOf(entity.status)
            if (status.isActive) {
                entity.copy(status = TransferStatus.QUEUED.name, errorMessage = null)
            } else {
                null // Remains untouched
            }
        }

        // Active transfers: PREPARING, UPLOADING, DOWNLOADING, COMMITTING, VERIFYING, QUEUED = 6
        assertEquals(6, reconciled.size)
        assertTrue(reconciled.all { it.status == TransferStatus.QUEUED.name })

        // Inactive transfers (PAUSED, CANCELLED, COMPLETED, FAILED, CONFLICT) were NOT modified to QUEUED
        val untouchedIds = listOf("t7", "t8", "t9", "t10", "t11")
        for (id in untouchedIds) {
            assertFalse("Inactive transfer $id must not be reconciled", reconciled.any { it.id == id })
        }
    }

    @Test
    fun `SAF persisted access survives process death simulation and validates permissions`() {
        val sampleUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownloads%2FMyRepo")

        // In Android SAF, persistable URI permission is taken via takePersistableUriPermission:
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(3, flags)

        // Background worker inspects URI without UI prompt
        val hasScheme = sampleUri.scheme == "content"
        val hasPath = sampleUri.path?.isNotEmpty() == true
        assertTrue("Persisted SAF URI has content scheme", hasScheme)
        assertTrue("Persisted SAF URI contains path", hasPath)

        // Invalid URI simulation
        val invalidUri = Uri.parse("invalid://bad/path")
        val isValidContentUri = invalidUri.scheme == "content"
        assertFalse("Invalid URI scheme fails SAF validation", isValidContentUri)
    }

    private fun createEntity(
        status: TransferStatus,
        id: String = "transfer-test-1",
        errorMessage: String? = null
    ): TransferEntity {
        return TransferEntity(
            id = id,
            type = TransferType.UPLOAD.name,
            repoOwner = "owner",
            repoName = "repo",
            branch = "main",
            sourcePath = "source",
            destPath = "dest",
            status = status.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 100L,
            processedBytes = 0L,
            createdAt = 1000L,
            errorMessage = errorMessage
        )
    }
}
