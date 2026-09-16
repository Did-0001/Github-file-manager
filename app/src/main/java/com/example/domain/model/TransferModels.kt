package com.example.domain.model

import android.net.Uri

enum class TransferType {
    UPLOAD,
    DOWNLOAD
}

enum class TransferStatus {
    QUEUED,
    SCANNING,
    VALIDATING,
    PREPARING,
    UPLOADING,
    DOWNLOADING,
    COMMITTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED;

    val isActive: Boolean
        get() = this in listOf(
            QUEUED, SCANNING, VALIDATING, PREPARING,
            UPLOADING, DOWNLOADING, COMMITTING, VERIFYING
        )
}

enum class CheckLevel {
    PASS,
    WARN,
    FAIL
}

data class PreflightCheckItem(
    val title: String,
    val level: CheckLevel,
    val detail: String
)

data class PreflightReport(
    val checks: List<PreflightCheckItem>,
    val totalFiles: Int,
    val totalBytes: Long,
    val warningsCount: Int,
    val errorsCount: Int
) {
    val isBlocked: Boolean get() = errorsCount > 0
}

enum class DiffChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    UNCHANGED,
    EXCLUDED
}

data class DiffItem(
    val localPath: String,
    val remotePath: String,
    val changeType: DiffChangeType,
    val sizeBytes: Long,
    val reason: String? = null
)

data class DiffReport(
    val added: Int,
    val modified: Int,
    val deleted: Int,
    val unchanged: Int,
    val excluded: Int,
    val items: List<DiffItem>
)

data class FileScanItem(
    val uri: Uri,
    val relativePath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

enum class OverwritePolicy {
    OVERWRITE,
    SKIP,
    KEEP_BOTH
}

data class DownloadConfig(
    val destinationTreeUri: Uri,
    val preserveStructure: Boolean = true,
    val createRepoFolder: Boolean = true,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.OVERWRITE,
    val asZip: Boolean = false
)
