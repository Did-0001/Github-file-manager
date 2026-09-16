package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val type: String, // UPLOAD, DOWNLOAD
    val repoOwner: String,
    val repoName: String,
    val branch: String,
    val sourcePath: String,
    val destPath: String,
    val status: String, // QUEUED, SCANNING, VALIDATING, PREPARING, UPLOADING, DOWNLOADING, COMMITTING, VERIFYING, COMPLETED, FAILED, CANCELLED, PAUSED
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val totalBytes: Long = 0L,
    val processedBytes: Long = 0L,
    val currentFile: String? = null,
    val commitSha: String? = null,
    val commitMessage: String? = null,
    val isWipe: Boolean = false,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
