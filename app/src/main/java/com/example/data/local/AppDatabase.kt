package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from version 1 to version 2:
         * In version 2, reviewedHeadSha was added to transfers table for concurrency safety.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transfers` ADD COLUMN `reviewedHeadSha` TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from version 2 to version 3:
         * In version 3, overwritePolicy was added to transfers table to control file collision semantics,
         * and wiped destination support with wipeMode column.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add overwritePolicy with default 'OVERWRITE' if not already present
                db.execSQL("ALTER TABLE `transfers` ADD COLUMN `overwritePolicy` TEXT NOT NULL DEFAULT 'OVERWRITE'")
            }
        }

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "github_file_manager.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
