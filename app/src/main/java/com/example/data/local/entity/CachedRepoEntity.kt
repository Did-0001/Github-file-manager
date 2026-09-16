package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_repos")
data class CachedRepoEntity(
    @PrimaryKey val id: Long,
    val owner: String,
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val description: String?,
    val updatedAt: String?
)
