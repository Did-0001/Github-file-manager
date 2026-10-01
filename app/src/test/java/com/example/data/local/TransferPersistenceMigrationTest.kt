package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferPersistenceMigrationTest {

    @Test
    fun `test migration 1 to 2 preserves transfer records and adds reviewedHeadSha column`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_1_2.db"
        context.deleteDatabase(dbName)

        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `cached_repos` (
                            `id` INTEGER NOT NULL, `owner` TEXT NOT NULL, `name` TEXT NOT NULL,
                            `fullName` TEXT NOT NULL, `isPrivate` INTEGER NOT NULL,
                            `defaultBranch` TEXT NOT NULL, `description` TEXT, `updatedAt` TEXT,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfers` (
                            `id` TEXT NOT NULL, `type` TEXT NOT NULL, `repoOwner` TEXT NOT NULL,
                            `repoName` TEXT NOT NULL, `branch` TEXT NOT NULL, `sourcePath` TEXT NOT NULL,
                            `destPath` TEXT NOT NULL, `status` TEXT NOT NULL, `totalFiles` INTEGER NOT NULL,
                            `processedFiles` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL,
                            `processedBytes` INTEGER NOT NULL, `currentFile` TEXT, `commitSha` TEXT,
                            `commitMessage` TEXT, `isWipe` INTEGER NOT NULL, `wipeMode` TEXT NOT NULL,
                            `preserveStructure` INTEGER NOT NULL, `createRepoFolder` INTEGER NOT NULL,
                            `asZip` INTEGER NOT NULL, `downloadScope` TEXT NOT NULL, `remotePath` TEXT NOT NULL,
                            `destinationUri` TEXT NOT NULL, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfer_items` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transferId` TEXT NOT NULL,
                            `relativePath` TEXT NOT NULL, `githubPath` TEXT NOT NULL,
                            `sizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `sha` TEXT,
                            `blobSha` TEXT, `localUri` TEXT, `errorMessage` TEXT,
                            `retryCount` INTEGER NOT NULL, `processedBytes` INTEGER NOT NULL
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val dbV1 = openHelper.writableDatabase

        // Insert V1 transfer record
        dbV1.execSQL("""
            INSERT INTO `transfers` (
                `id`, `type`, `repoOwner`, `repoName`, `branch`, `sourcePath`, `destPath`,
                `status`, `totalFiles`, `processedFiles`, `totalBytes`, `processedBytes`,
                `isWipe`, `wipeMode`, `preserveStructure`, `createRepoFolder`, `asZip`,
                `downloadScope`, `remotePath`, `destinationUri`, `retryCount`, `createdAt`
            ) VALUES (
                'transfer-v1', 'UPLOAD', 'testOwner', 'testRepo', 'main', 'folder', 'dest',
                'QUEUED', 2, 0, 1024, 0,
                0, 'NONE', 1, 0, 0,
                'ALL', '', '', 0, 123456789
            )
        """.trimIndent())

        // Insert V1 transfer item record
        dbV1.execSQL("""
            INSERT INTO `transfer_items` (
                `transferId`, `relativePath`, `githubPath`, `sizeBytes`, `status`,
                `localUri`, `retryCount`, `processedBytes`
            ) VALUES (
                'transfer-v1', 'file1.txt', 'dest/file1.txt', 512, 'PENDING',
                'content://test/file1', 0, 0
            )
        """.trimIndent())

        // Execute MIGRATION_1_2
        AppDatabase.MIGRATION_1_2.migrate(dbV1)

        // Verify transfer survived and reviewedHeadSha exists with default null
        val cursor = dbV1.query("SELECT id, type, repoOwner, repoName, totalBytes, reviewedHeadSha FROM transfers WHERE id = 'transfer-v1'")
        assertTrue("Expected transfer record to exist after migration 1->2", cursor.moveToFirst())
        assertEquals("transfer-v1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals("UPLOAD", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals("testOwner", cursor.getString(cursor.getColumnIndexOrThrow("repoOwner")))
        assertEquals("testRepo", cursor.getString(cursor.getColumnIndexOrThrow("repoName")))
        assertEquals(1024L, cursor.getLong(cursor.getColumnIndexOrThrow("totalBytes")))
        assertTrue("reviewedHeadSha should be null by default", cursor.isNull(cursor.getColumnIndexOrThrow("reviewedHeadSha")))
        cursor.close()

        // Verify transfer items survived
        val itemCursor = dbV1.query("SELECT transferId, relativePath, sizeBytes, localUri FROM transfer_items WHERE transferId = 'transfer-v1'")
        assertTrue("Expected transfer item to exist after migration", itemCursor.moveToFirst())
        assertEquals("transfer-v1", itemCursor.getString(itemCursor.getColumnIndexOrThrow("transferId")))
        assertEquals("file1.txt", itemCursor.getString(itemCursor.getColumnIndexOrThrow("relativePath")))
        assertEquals(512L, itemCursor.getLong(itemCursor.getColumnIndexOrThrow("sizeBytes")))
        assertEquals("content://test/file1", itemCursor.getString(itemCursor.getColumnIndexOrThrow("localUri")))
        itemCursor.close()

        openHelper.close()
    }

    @Test
    fun `test migration 2 to 3 preserves transfer records and adds overwritePolicy column`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_2_3.db"
        context.deleteDatabase(dbName)

        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `cached_repos` (
                            `id` INTEGER NOT NULL, `owner` TEXT NOT NULL, `name` TEXT NOT NULL,
                            `fullName` TEXT NOT NULL, `isPrivate` INTEGER NOT NULL,
                            `defaultBranch` TEXT NOT NULL, `description` TEXT, `updatedAt` TEXT,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfers` (
                            `id` TEXT NOT NULL, `type` TEXT NOT NULL, `repoOwner` TEXT NOT NULL,
                            `repoName` TEXT NOT NULL, `branch` TEXT NOT NULL, `sourcePath` TEXT NOT NULL,
                            `destPath` TEXT NOT NULL, `status` TEXT NOT NULL, `totalFiles` INTEGER NOT NULL,
                            `processedFiles` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL,
                            `processedBytes` INTEGER NOT NULL, `currentFile` TEXT, `commitSha` TEXT,
                            `commitMessage` TEXT, `isWipe` INTEGER NOT NULL, `wipeMode` TEXT NOT NULL,
                            `reviewedHeadSha` TEXT, `preserveStructure` INTEGER NOT NULL,
                            `createRepoFolder` INTEGER NOT NULL, `asZip` INTEGER NOT NULL,
                            `downloadScope` TEXT NOT NULL, `remotePath` TEXT NOT NULL,
                            `destinationUri` TEXT NOT NULL, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfer_items` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transferId` TEXT NOT NULL,
                            `relativePath` TEXT NOT NULL, `githubPath` TEXT NOT NULL,
                            `sizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `sha` TEXT,
                            `blobSha` TEXT, `localUri` TEXT, `errorMessage` TEXT,
                            `retryCount` INTEGER NOT NULL, `processedBytes` INTEGER NOT NULL
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val dbV2 = openHelper.writableDatabase

        // Insert V2 transfer record with reviewedHeadSha
        val testSha = "fedcba9876543210fedcba9876543210fedcba98"
        dbV2.execSQL("""
            INSERT INTO `transfers` (
                `id`, `type`, `repoOwner`, `repoName`, `branch`, `sourcePath`, `destPath`,
                `status`, `totalFiles`, `processedFiles`, `totalBytes`, `processedBytes`,
                `isWipe`, `wipeMode`, `reviewedHeadSha`, `preserveStructure`, `createRepoFolder`,
                `asZip`, `downloadScope`, `remotePath`, `destinationUri`, `retryCount`, `createdAt`
            ) VALUES (
                'transfer-v2', 'DOWNLOAD', 'repoOwner', 'repoName', 'dev', 'src', 'content://dest',
                'COMPLETED', 1, 1, 2048, 2048,
                0, 'NONE', '$testSha', 1, 0,
                1, 'WHOLE_REPO', '', 'content://dest', 0, 987654321
            )
        """.trimIndent())

        // Execute MIGRATION_2_3
        AppDatabase.MIGRATION_2_3.migrate(dbV2)

        // Verify transfer survived and overwritePolicy has default 'OVERWRITE'
        val cursor = dbV2.query("SELECT id, type, branch, destPath, reviewedHeadSha, overwritePolicy FROM transfers WHERE id = 'transfer-v2'")
        assertTrue("Expected transfer record to exist after migration 2->3", cursor.moveToFirst())
        assertEquals("transfer-v2", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals("DOWNLOAD", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals("dev", cursor.getString(cursor.getColumnIndexOrThrow("branch")))
        assertEquals("content://dest", cursor.getString(cursor.getColumnIndexOrThrow("destPath")))
        assertEquals(testSha, cursor.getString(cursor.getColumnIndexOrThrow("reviewedHeadSha")))
        assertEquals("OVERWRITE", cursor.getString(cursor.getColumnIndexOrThrow("overwritePolicy")))
        cursor.close()

        openHelper.close()
    }

    @Test
    fun `test non-destructive migrations through Room database builder`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_room_durability.db"
        context.deleteDatabase(dbName)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

        val transfer = TransferEntity(
            id = "transfer-live-1",
            type = "UPLOAD",
            repoOwner = "owner",
            repoName = "repo",
            branch = "main",
            sourcePath = "files",
            destPath = "/",
            status = "QUEUED",
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 100L,
            processedBytes = 0L,
            createdAt = System.currentTimeMillis(),
            overwritePolicy = "KEEP_BOTH",
            reviewedHeadSha = "sha123"
        )

        val item = TransferItemEntity(
            transferId = "transfer-live-1",
            relativePath = "doc.pdf",
            githubPath = "doc.pdf",
            sizeBytes = 100L,
            status = "PENDING",
            localUri = "content://com.android.providers.media/doc.pdf"
        )

        val dao = db.transferDao()
        dao.insertTransfer(transfer)
        dao.insertItems(listOf(item))

        val retrieved = dao.getTransferById("transfer-live-1")
        assertNotNull(retrieved)
        assertEquals("transfer-live-1", retrieved?.id)
        assertEquals("KEEP_BOTH", retrieved?.overwritePolicy)
        assertEquals("sha123", retrieved?.reviewedHeadSha)

        val items = dao.getItemsForTransferSync("transfer-live-1")
        assertEquals(1, items.size)
        assertEquals("content://com.android.providers.media/doc.pdf", items[0].localUri)

        db.close()
    }

    @Test
    fun `test sequential migration 1 to 2 to 3 preserves in-flight transfer state and item progress`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_e2e_migration.db"
        context.deleteDatabase(dbName)

        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `cached_repos` (
                            `id` INTEGER NOT NULL, `owner` TEXT NOT NULL, `name` TEXT NOT NULL,
                            `fullName` TEXT NOT NULL, `isPrivate` INTEGER NOT NULL,
                            `defaultBranch` TEXT NOT NULL, `description` TEXT, `updatedAt` TEXT,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfers` (
                            `id` TEXT NOT NULL, `type` TEXT NOT NULL, `repoOwner` TEXT NOT NULL,
                            `repoName` TEXT NOT NULL, `branch` TEXT NOT NULL, `sourcePath` TEXT NOT NULL,
                            `destPath` TEXT NOT NULL, `status` TEXT NOT NULL, `totalFiles` INTEGER NOT NULL,
                            `processedFiles` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL,
                            `processedBytes` INTEGER NOT NULL, `currentFile` TEXT, `commitSha` TEXT,
                            `commitMessage` TEXT, `isWipe` INTEGER NOT NULL, `wipeMode` TEXT NOT NULL,
                            `preserveStructure` INTEGER NOT NULL, `createRepoFolder` INTEGER NOT NULL,
                            `asZip` INTEGER NOT NULL, `downloadScope` TEXT NOT NULL, `remotePath` TEXT NOT NULL,
                            `destinationUri` TEXT NOT NULL, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfer_items` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transferId` TEXT NOT NULL,
                            `relativePath` TEXT NOT NULL, `githubPath` TEXT NOT NULL,
                            `sizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `sha` TEXT,
                            `blobSha` TEXT, `localUri` TEXT, `errorMessage` TEXT,
                            `retryCount` INTEGER NOT NULL, `processedBytes` INTEGER NOT NULL
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val db = openHelper.writableDatabase

        // Insert in-flight V1 transfer record
        db.execSQL("""
            INSERT INTO `transfers` (
                `id`, `type`, `repoOwner`, `repoName`, `branch`, `sourcePath`, `destPath`,
                `status`, `totalFiles`, `processedFiles`, `totalBytes`, `processedBytes`,
                `currentFile`, `commitSha`, `commitMessage`, `isWipe`, `wipeMode`,
                `preserveStructure`, `createRepoFolder`, `asZip`, `downloadScope`,
                `remotePath`, `destinationUri`, `errorMessage`, `retryCount`, `createdAt`
            ) VALUES (
                'inflight-transfer', 'UPLOAD', 'ownerX', 'repoY', 'feature-branch', 'local/src', 'remote/dest',
                'UPLOADING', 3, 1, 3000, 1000,
                'file2.kt', NULL, 'Work in progress', 1, 'DESTINATION',
                1, 0, 0, 'ALL',
                '', '', NULL, 1, 1700000000000
            )
        """.trimIndent())

        // Insert items in different states: SUCCESS, FAILED, PENDING
        db.execSQL("INSERT INTO `transfer_items` (`transferId`, `relativePath`, `githubPath`, `sizeBytes`, `status`, `blobSha`, `retryCount`, `processedBytes`) VALUES ('inflight-transfer', 'file1.kt', 'remote/dest/file1.kt', 1000, 'SUCCESS', 'sha_blob_1', 0, 1000)")
        db.execSQL("INSERT INTO `transfer_items` (`transferId`, `relativePath`, `githubPath`, `sizeBytes`, `status`, `errorMessage`, `retryCount`, `processedBytes`) VALUES ('inflight-transfer', 'file2.kt', 'remote/dest/file2.kt', 1000, 'FAILED', 'Socket timeout', 1, 0)")
        db.execSQL("INSERT INTO `transfer_items` (`transferId`, `relativePath`, `githubPath`, `sizeBytes`, `status`, `retryCount`, `processedBytes`) VALUES ('inflight-transfer', 'file3.kt', 'remote/dest/file3.kt', 1000, 'PENDING', 0, 0)")

        // Apply MIGRATION_1_2 and MIGRATION_2_3 in sequence
        AppDatabase.MIGRATION_1_2.migrate(db)
        AppDatabase.MIGRATION_2_3.migrate(db)

        // Verify transfer state is 100% preserved
        val cursor = db.query("SELECT id, type, repoOwner, repoName, branch, status, totalFiles, processedFiles, totalBytes, processedBytes, currentFile, commitMessage, wipeMode, retryCount, reviewedHeadSha, overwritePolicy FROM transfers WHERE id = 'inflight-transfer'")
        assertTrue(cursor.moveToFirst())
        assertEquals("inflight-transfer", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals("UPLOAD", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals("ownerX", cursor.getString(cursor.getColumnIndexOrThrow("repoOwner")))
        assertEquals("repoY", cursor.getString(cursor.getColumnIndexOrThrow("repoName")))
        assertEquals("feature-branch", cursor.getString(cursor.getColumnIndexOrThrow("branch")))
        assertEquals("UPLOADING", cursor.getString(cursor.getColumnIndexOrThrow("status")))
        assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("totalFiles")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("processedFiles")))
        assertEquals(3000L, cursor.getLong(cursor.getColumnIndexOrThrow("totalBytes")))
        assertEquals(1000L, cursor.getLong(cursor.getColumnIndexOrThrow("processedBytes")))
        assertEquals("file2.kt", cursor.getString(cursor.getColumnIndexOrThrow("currentFile")))
        assertEquals("Work in progress", cursor.getString(cursor.getColumnIndexOrThrow("commitMessage")))
        assertEquals("DESTINATION", cursor.getString(cursor.getColumnIndexOrThrow("wipeMode")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("retryCount")))
        assertTrue("reviewedHeadSha is null by default", cursor.isNull(cursor.getColumnIndexOrThrow("reviewedHeadSha")))
        assertEquals("OVERWRITE", cursor.getString(cursor.getColumnIndexOrThrow("overwritePolicy")))
        cursor.close()

        // Verify all 3 item states are intact
        val itemCursor = db.query("SELECT relativePath, status, processedBytes, blobSha, errorMessage FROM transfer_items WHERE transferId = 'inflight-transfer' ORDER BY relativePath")
        assertEquals(3, itemCursor.count)

        assertTrue(itemCursor.moveToNext())
        assertEquals("file1.kt", itemCursor.getString(0))
        assertEquals("SUCCESS", itemCursor.getString(1))
        assertEquals(1000L, itemCursor.getLong(2))
        assertEquals("sha_blob_1", itemCursor.getString(3))

        assertTrue(itemCursor.moveToNext())
        assertEquals("file2.kt", itemCursor.getString(0))
        assertEquals("FAILED", itemCursor.getString(1))
        assertEquals(0L, itemCursor.getLong(2))
        assertEquals("Socket timeout", itemCursor.getString(4))

        assertTrue(itemCursor.moveToNext())
        assertEquals("file3.kt", itemCursor.getString(0))
        assertEquals("PENDING", itemCursor.getString(1))
        assertEquals(0L, itemCursor.getLong(2))

        itemCursor.close()
        openHelper.close()
    }

    @Test
    fun `test migration 3 to 4 preserves transfer records and adds selectedPathsJson column`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_3_4.db"
        context.deleteDatabase(dbName)

        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfers` (
                            `id` TEXT NOT NULL, `type` TEXT NOT NULL, `repoOwner` TEXT NOT NULL,
                            `repoName` TEXT NOT NULL, `branch` TEXT NOT NULL, `sourcePath` TEXT NOT NULL,
                            `destPath` TEXT NOT NULL, `status` TEXT NOT NULL, `totalFiles` INTEGER NOT NULL,
                            `processedFiles` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL,
                            `processedBytes` INTEGER NOT NULL, `currentFile` TEXT, `commitSha` TEXT,
                            `commitMessage` TEXT, `isWipe` INTEGER NOT NULL, `wipeMode` TEXT NOT NULL,
                            `reviewedHeadSha` TEXT, `overwritePolicy` TEXT NOT NULL DEFAULT 'OVERWRITE',
                            `preserveStructure` INTEGER NOT NULL, `createRepoFolder` INTEGER NOT NULL,
                            `asZip` INTEGER NOT NULL, `downloadScope` TEXT NOT NULL, `remotePath` TEXT NOT NULL,
                            `destinationUri` TEXT NOT NULL, `errorMessage` TEXT, `retryCount` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val db = openHelper.writableDatabase

        db.execSQL("""
            INSERT INTO `transfers` (
                `id`, `type`, `repoOwner`, `repoName`, `branch`, `sourcePath`, `destPath`,
                `status`, `totalFiles`, `processedFiles`, `totalBytes`, `processedBytes`,
                `currentFile`, `commitSha`, `commitMessage`, `isWipe`, `wipeMode`,
                `reviewedHeadSha`, `overwritePolicy`, `preserveStructure`, `createRepoFolder`,
                `asZip`, `downloadScope`, `remotePath`, `destinationUri`, `errorMessage`,
                `retryCount`, `createdAt`
            ) VALUES (
                'download-transfer-v3', 'DOWNLOAD', 'octocat', 'Hello-World', 'main', '3 selected items', 'content://dest',
                'QUEUED', 3, 0, 1024, 0,
                NULL, NULL, NULL, 0, 'NONE',
                NULL, 'KEEP_BOTH', 1, 0,
                0, 'SELECTED_ITEMS', 'src', 'content://dest', NULL,
                0, 1710000000000
            )
        """.trimIndent())

        // Apply MIGRATION_3_4
        AppDatabase.MIGRATION_3_4.migrate(db)

        val cursor = db.query("SELECT id, type, repoOwner, repoName, branch, status, downloadScope, remotePath, overwritePolicy, selectedPathsJson FROM transfers WHERE id = 'download-transfer-v3'")
        assertTrue(cursor.moveToFirst())
        assertEquals("download-transfer-v3", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals("DOWNLOAD", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals("octocat", cursor.getString(cursor.getColumnIndexOrThrow("repoOwner")))
        assertEquals("SELECTED_ITEMS", cursor.getString(cursor.getColumnIndexOrThrow("downloadScope")))
        assertEquals("src", cursor.getString(cursor.getColumnIndexOrThrow("remotePath")))
        assertEquals("KEEP_BOTH", cursor.getString(cursor.getColumnIndexOrThrow("overwritePolicy")))
        assertTrue("selectedPathsJson should be null by default after migration", cursor.isNull(cursor.getColumnIndexOrThrow("selectedPathsJson")))
        cursor.close()

        // Test inserting with selectedPathsJson populated
        db.execSQL("UPDATE transfers SET selectedPathsJson = '[\"src/A.kt\",\"src/B.kt\"]' WHERE id = 'download-transfer-v3'")
        val updatedCursor = db.query("SELECT selectedPathsJson FROM transfers WHERE id = 'download-transfer-v3'")
        assertTrue(updatedCursor.moveToFirst())
        assertEquals("[\"src/A.kt\",\"src/B.kt\"]", updatedCursor.getString(0))
        updatedCursor.close()

        openHelper.close()
    }

    @Test
    fun testMigration4To5_addsClearHistoryColumn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test-v4-to-v5.db"
        context.deleteDatabase(dbName)

        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `transfers` (
                            `id` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `repoOwner` TEXT NOT NULL,
                            `repoName` TEXT NOT NULL,
                            `branch` TEXT NOT NULL,
                            `sourcePath` TEXT NOT NULL,
                            `destPath` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `totalFiles` INTEGER NOT NULL,
                            `processedFiles` INTEGER NOT NULL,
                            `totalBytes` INTEGER NOT NULL,
                            `processedBytes` INTEGER NOT NULL,
                            `currentFile` TEXT,
                            `commitSha` TEXT,
                            `commitMessage` TEXT,
                            `isWipe` INTEGER NOT NULL,
                            `wipeMode` TEXT NOT NULL,
                            `reviewedHeadSha` TEXT,
                            `overwritePolicy` TEXT NOT NULL,
                            `preserveStructure` INTEGER NOT NULL,
                            `createRepoFolder` INTEGER NOT NULL,
                            `asZip` INTEGER NOT NULL,
                            `downloadScope` TEXT NOT NULL,
                            `remotePath` TEXT NOT NULL,
                            `destinationUri` TEXT NOT NULL,
                            `selectedPathsJson` TEXT,
                            `errorMessage` TEXT,
                            `retryCount` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `completedAt` INTEGER,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val db = openHelper.writableDatabase

        // Insert record under schema v4 (no clearHistory column)
        db.execSQL("""
            INSERT INTO transfers (
                id, type, repoOwner, repoName, branch, sourcePath, destPath,
                status, totalFiles, processedFiles, totalBytes, processedBytes,
                currentFile, commitSha, commitMessage, isWipe, wipeMode,
                reviewedHeadSha, overwritePolicy, preserveStructure, createRepoFolder,
                asZip, downloadScope, remotePath, destinationUri, selectedPathsJson,
                retryCount, createdAt
            ) VALUES (
                'upload-transfer-v4', 'UPLOAD', 'octocat', 'Hello-World', 'main', '/local', 'dest',
                'QUEUED', 2, 0, 2048, 0,
                NULL, NULL, 'commit msg', 0, 'NONE',
                'sha123', 'OVERWRITE', 1, 0,
                0, 'REPOSITORY', '', '', NULL,
                0, 1710000000000
            )
        """.trimIndent())

        // Apply MIGRATION_4_5
        AppDatabase.MIGRATION_4_5.migrate(db)

        val cursor = db.query("SELECT id, type, wipeMode, clearHistory FROM transfers WHERE id = 'upload-transfer-v4'")
        assertTrue(cursor.moveToFirst())
        assertEquals("upload-transfer-v4", cursor.getString(cursor.getColumnIndexOrThrow("id")))
        assertEquals("NONE", cursor.getString(cursor.getColumnIndexOrThrow("wipeMode")))
        assertEquals("clearHistory should default to 0 (false) after migration", 0, cursor.getInt(cursor.getColumnIndexOrThrow("clearHistory")))
        cursor.close()

        // Test updating clearHistory to 1 (true)
        db.execSQL("UPDATE transfers SET clearHistory = 1, wipeMode = 'FULL_BRANCH' WHERE id = 'upload-transfer-v4'")
        val updatedCursor = db.query("SELECT clearHistory, wipeMode FROM transfers WHERE id = 'upload-transfer-v4'")
        assertTrue(updatedCursor.moveToFirst())
        assertEquals(1, updatedCursor.getInt(0))
        assertEquals("FULL_BRANCH", updatedCursor.getString(1))
        updatedCursor.close()

        openHelper.close()
    }
}
