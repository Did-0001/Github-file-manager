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
    PAUSED,
    CONFLICT;

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

enum class WipeMode {
    NONE,
    SELECTED_FOLDER,
    DESTINATION, // Backward compatible alias for SELECTED_FOLDER
    CHANGED_FOLDERS,
    FULL_BRANCH;

    val isWipe: Boolean get() = this != NONE

    companion object {
        fun fromString(value: String?): WipeMode {
            return when (value?.uppercase()) {
                "SELECTED_FOLDER", "DESTINATION" -> SELECTED_FOLDER
                "CHANGED_FOLDERS", "UPLOADED_FOLDERS" -> CHANGED_FOLDERS
                "FULL_BRANCH" -> FULL_BRANCH
                else -> NONE
            }
        }
    }
}

data class DiffReport(
    val added: Int,
    val modified: Int,
    val deleted: Int,
    val unchanged: Int,
    val excluded: Int,
    val items: List<DiffItem>,
    val reviewedHeadSha: String? = null
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

enum class DownloadScope {
    REPOSITORY,
    DIRECTORY,
    SINGLE_FILE,
    SELECTED_ITEMS
}

data class DownloadConfig(
    val destinationTreeUri: Uri,
    val preserveStructure: Boolean = true,
    val createRepoFolder: Boolean = false,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.OVERWRITE,
    val asZip: Boolean = false,
    val downloadScope: DownloadScope = DownloadScope.REPOSITORY,
    val selectedPaths: List<String> = emptyList()
)
