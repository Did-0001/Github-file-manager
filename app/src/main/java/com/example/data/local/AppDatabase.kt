package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.data.local.dao.RepoDao
import com.example.data.local.dao.TransferDao
import com.example.data.local.entity.CachedRepoEntity
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity

@Database(
    entities = [
        CachedRepoEntity::class,
        TransferEntity::class,
        TransferItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "github_file_manager.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
