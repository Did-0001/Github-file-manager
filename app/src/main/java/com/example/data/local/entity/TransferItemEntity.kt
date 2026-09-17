package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transfer_items",
    indices = [Index("transferId")]
)
data class TransferItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transferId: String,
    val relativePath: String,
    val githubPath: String,
    val sizeBytes: Long,
    val status: String, // PENDING, IN_PROGRESS, SUCCESS, FAILED, SKIPPED
    val sha: String? = null,
    val blobSha: String? = null,
    val localUri: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val processedBytes: Long = 0L
)
