package com.example.domain.manager

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import com.example.data.repository.TransferRepository
import com.example.domain.model.DownloadConfig
import com.example.domain.model.FileScanItem
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferType
import com.example.domain.model.WipeMode
import com.example.domain.worker.TransferWorker
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TransferManager(
    private val context: Context,
    private val transferRepository: TransferRepository
) {
    private val workManager = WorkManager.getInstance(context)

    val activeTransfers: Flow<List<TransferEntity>> = transferRepository.activeTransfers
    val historyTransfers: Flow<List<TransferEntity>> = transferRepository.historyTransfers

    fun getItemsForTransfer(transferId: String): Flow<List<TransferItemEntity>> =
        transferRepository.getItemsForTransfer(transferId)

    suspend fun startUpload(
        owner: String,
        repo: String,
        branch: String,
        destinationPath: String,
        scannedFiles: List<FileScanItem>,
        commitMessage: String,
        wipeMode: WipeMode = WipeMode.NONE,
        reviewedHeadSha: String? = null
    ): String {
        val transferId = UUID.randomUUID().toString()
        val totalBytes = scannedFiles.sumOf { it.sizeBytes }

        val entity = TransferEntity(
            id = transferId,
            type = TransferType.UPLOAD.name,
            repoOwner = owner,
            repoName = repo,
            branch = branch,
            sourcePath = if (scannedFiles.size == 1) scannedFiles.first().relativePath else "${scannedFiles.size} files",
            destPath = destinationPath,
            status = TransferStatus.QUEUED.name,
            totalFiles = scannedFiles.size,
            processedFiles = 0,
            totalBytes = totalBytes,
            processedBytes = 0L,
            createdAt = System.currentTimeMillis(),
            commitMessage = commitMessage,
            isWipe = wipeMode != WipeMode.NONE,
            wipeMode = wipeMode.name,
            reviewedHeadSha = reviewedHeadSha
        )

        val itemEntities = scannedFiles.map { file ->
            val cleanRel = file.relativePath.trimStart('/').replace('\\', '/')
            val finalGithubPath = if (destinationPath.trim().isEmpty()) {
                cleanRel
            } else {
                "${destinationPath.trim().trim('/')}/$cleanRel"
            }
            TransferItemEntity(
                transferId = transferId,
                relativePath = file.relativePath,
                githubPath = finalGithubPath,
                localUri = file.uri.toString(),
                sizeBytes = file.sizeBytes,
                status = "PENDING"
            )
        }

        transferRepository.insertTransfer(entity)
        transferRepository.insertItems(itemEntities)

        enqueueWork(transferId)
        return transferId
    }

    suspend fun startDownload(
        owner: String,
        repo: String,
        branch: String,
        remotePath: String,
        config: DownloadConfig
    ): String {
        val transferId = UUID.randomUUID().toString()

        val entity = TransferEntity(
            id = transferId,
            type = TransferType.DOWNLOAD.name,
            repoOwner = owner,
            repoName = repo,
            branch = branch,
            sourcePath = if (remotePath.isEmpty()) "Entire Repository ($branch)" else remotePath,
            destPath = config.destinationTreeUri.toString(),
            status = TransferStatus.QUEUED.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 0L,
            processedBytes = 0L,
            createdAt = System.currentTimeMillis(),
            asZip = config.asZip,
            overwritePolicy = config.overwritePolicy.name,
            preserveStructure = config.preserveStructure,
            createRepoFolder = config.createRepoFolder,
            downloadScope = config.downloadScope.name
        )

        transferRepository.insertTransfer(entity)

        enqueueWork(transferId)
        return transferId
    }

    suspend fun pauseTransfer(transferId: String) {
        cancelWork(transferId)
        transferRepository.getTransfer(transferId)?.let {
            transferRepository.updateTransfer(it.copy(status = TransferStatus.PAUSED.name))
        }
    }

    suspend fun resumeTransfer(transferId: String) {
        val entity = transferRepository.getTransfer(transferId) ?: return
        transferRepository.updateTransfer(entity.copy(status = TransferStatus.QUEUED.name))
        enqueueWork(transferId, ExistingWorkPolicy.REPLACE)
    }

    suspend fun cancelTransfer(transferId: String) {
        cancelWork(transferId)
        transferRepository.getTransfer(transferId)?.let {
            transferRepository.updateTransfer(it.copy(status = TransferStatus.CANCELLED.name))
        }
    }

    suspend fun retryTransfer(transferId: String, failedOnly: Boolean = true) {
        val entity = transferRepository.getTransfer(transferId) ?: return
        if (failedOnly) {
            transferRepository.resetFailedItems(transferId)
        } else {
            transferRepository.resetAllItems(transferId)
        }
        transferRepository.updateTransfer(entity.copy(status = TransferStatus.QUEUED.name, errorMessage = null))
        enqueueWork(transferId, ExistingWorkPolicy.REPLACE)
    }

    suspend fun clearCompletedTransfers() {
        transferRepository.clearCompletedTransfers()
    }

    private fun enqueueWork(transferId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to transferId))
            .addTag("transfer_$transferId")
            .build()
        workManager.enqueueUniqueWork("transfer_$transferId", policy, request)
    }

    private fun cancelWork(transferId: String) {
        workManager.cancelUniqueWork("transfer_$transferId")
    }
}
