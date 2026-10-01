package com.example.domain.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.SecureStorage
import com.example.data.local.entity.TransferEntity
import com.example.data.remote.ApiClient
import com.example.data.remote.GitHubApiException
import com.example.data.repository.GitHubRepository
import com.example.data.repository.TransferRepository
import com.example.domain.engine.TransferEngine
import com.example.domain.model.TransferStatus
import kotlinx.coroutines.CancellationException

class TransferWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_TRANSFER_ID = "key_transfer_id"
        const val CHANNEL_ID = "github_transfers_channel"
        const val NOTIFICATION_ID_BASE = 10000

        fun mapWorkerResult(
            entity: TransferEntity?,
            isStopped: Boolean,
            caughtException: Throwable?,
            attemptCount: Int = 0
        ): Result {
            val statusStr = entity?.status
            val status = try { statusStr?.let { TransferStatus.valueOf(it) } } catch (_: Exception) { null }

            // 1. Branch conflict
            if (status == TransferStatus.CONFLICT) {
                return Result.failure(
                    workDataOf(
                        "reason" to "conflict",
                        "error" to (entity?.errorMessage ?: "Branch conflict detected")
                    )
                )
            }

            // 2. Pause
            if (status == TransferStatus.PAUSED || (isStopped && status == TransferStatus.PAUSED)) {
                return Result.success(
                    workDataOf("reason" to "pause")
                )
            }

            // 3. Cancellation
            if (status == TransferStatus.CANCELLED || (isStopped && caughtException is CancellationException)) {
                return Result.failure(
                    workDataOf("reason" to "cancellation")
                )
            }

            // 4. Completed
            if (status == TransferStatus.COMPLETED) {
                return Result.success(
                    workDataOf("reason" to "completed")
                )
            }

            // 5. Retryable vs Permanent failure
            val isRetryable = isRetryableError(caughtException, entity?.errorMessage)
            if (isRetryable && attemptCount < 3) {
                return Result.retry()
            }

            return Result.failure(
                workDataOf(
                    "reason" to "permanent_failure",
                    "error" to (entity?.errorMessage ?: caughtException?.localizedMessage ?: "Transfer failed")
                )
            )
        }

        fun isRetryableError(throwable: Throwable?, errorMessage: String?): Boolean {
            if (throwable is GitHubApiException) {
                return throwable.isRetryable
            }
            if (throwable?.cause is GitHubApiException) {
                return (throwable.cause as GitHubApiException).isRetryable
            }
            if (throwable is java.net.SocketTimeoutException ||
                throwable is java.net.UnknownHostException ||
                throwable is java.net.ConnectException ||
                throwable is java.net.NoRouteToHostException ||
                throwable is java.io.InterruptedIOException ||
                throwable is java.io.IOException) {
                return true
            }
            val msg = (errorMessage ?: throwable?.message ?: "").lowercase()
            // Non-retryable conditions take priority
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
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val transferId = inputData.getString(KEY_TRANSFER_ID) ?: return Result.failure()

        createNotificationChannel()

        val database = AppDatabase.getInstance(context)
        val transferRepository = TransferRepository(database)
        val secureStorage = SecureStorage(context)
        val apiClient = ApiClient(secureStorage)
        val gitHubRepository = GitHubRepository(apiClient)
        val transferEngine = TransferEngine(context, gitHubRepository, transferRepository)

        val entity = transferRepository.getTransfer(transferId) ?: return Result.failure()
        if (entity.status == TransferStatus.PAUSED.name || entity.status == TransferStatus.CANCELLED.name) {
            return Result.success(workDataOf("reason" to "skipped_inactive"))
        }

        val notificationId = NOTIFICATION_ID_BASE + (transferId.hashCode() and 0x7FFF)
        val initialType = entity.type
        val initialTitle = "GitHub File Manager"
        val initialSub = if (initialType == "UPLOAD") "Uploading..." else "Downloading..."

        val initialFgInfo = createForegroundInfo(
            notificationId = notificationId,
            title = initialTitle,
            subText = initialSub,
            progress = 0,
            indeterminate = true
        )
        try {
            setForeground(initialFgInfo)
        } catch (_: Exception) {
            // Foreground may not be permitted if invoked under certain platform constraints
        }

        return try {
            transferEngine.executeTransferById(transferId) { currentFile, processedBytes, totalBytes ->
                if (isStopped) return@executeTransferById
                val progress = if (totalBytes > 0) ((processedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                val indeterminate = totalBytes <= 0
                val subText = if (initialType == "UPLOAD") {
                    if (currentFile != null) "Uploading: $currentFile" else "Uploading..."
                } else {
                    if (currentFile != null) "Downloading: $currentFile" else "Downloading..."
                }
                val fgInfo = createForegroundInfo(
                    notificationId = notificationId,
                    title = initialTitle,
                    subText = subText,
                    progress = progress,
                    indeterminate = indeterminate
                )
                try {
                    notificationManager.notify(notificationId, fgInfo.notification)
                } catch (_: Exception) {}
            }

            val finalEntity = transferRepository.getTransfer(transferId)
            mapWorkerResult(finalEntity, isStopped = isStopped, caughtException = null, attemptCount = runAttemptCount)
        } catch (e: CancellationException) {
            val finalEntity = transferRepository.getTransfer(transferId)
            mapWorkerResult(finalEntity, isStopped = true, caughtException = e, attemptCount = runAttemptCount)
        } catch (e: Exception) {
            val finalEntity = transferRepository.getTransfer(transferId)
            mapWorkerResult(finalEntity, isStopped = isStopped, caughtException = e, attemptCount = runAttemptCount)
        } finally {
            try {
                notificationManager.cancel(notificationId)
            } catch (_: Exception) {}
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        subText: String,
        progress: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .apply {
                if (indeterminate) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(100, progress, false)
                }
            }
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of active GitHub uploads and downloads"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
