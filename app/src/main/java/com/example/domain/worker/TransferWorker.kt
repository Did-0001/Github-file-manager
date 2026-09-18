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
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.SecureStorage
import com.example.data.remote.ApiClient
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
            return Result.success()
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
            Result.success()
        } catch (e: CancellationException) {
            Result.failure()
        } catch (e: Exception) {
            Result.failure()
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
