package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.CachedRepoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {
    @Query("SELECT * FROM cached_repos ORDER BY updatedAt DESC")
    fun getAllRepos(): Flow<List<CachedRepoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(repos: List<CachedRepoEntity>)

    @Query("DELETE FROM cached_repos")
    suspend fun clearAll()
}
