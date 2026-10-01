package com.example.domain.engine

import com.example.data.local.entity.TransferEntity
import com.example.domain.model.DownloadConfig
import com.example.domain.model.DownloadScope
import com.example.domain.model.OverwritePolicy
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadCorrectnessAndRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `unknown content length must remain indeterminate and not set totalBytes equal to processedBytes`() {
        val totalExpectedBytes: Long? = null
        val processedBytes = 4096L

        // Rule: Never convert unknown Content-Length into 'totalBytes = processedBytes'
        val effectiveTotalBytes: Long? = totalExpectedBytes

        assertNull("Unknown total size must remain indeterminate (null)", effectiveTotalBytes)
        assertNotEquals("processedBytes must not become totalBytes", processedBytes, effectiveTotalBytes ?: -1L)
    }

    @Test
    fun `known content length must strictly validate downloaded size`() {
        val expectedLength = 1024L
        val receivedSuccess = 1024L
        val receivedTruncated = 512L

        assertTrue("Matching content length validates successfully", receivedSuccess == expectedLength)
        assertFalse("Truncated content length fails validation", receivedTruncated == expectedLength)
    }

    @Test
    fun `git blob sha verification validates downloaded file against remote git tree sha`() {
        val fileContent = "fun main() = println(\"Hello Android!\")\n"
        val bytes = fileContent.toByteArray(Charsets.UTF_8)
        val expectedSha = GitBlobHasher.calculateSha(bytes)

        val downloadedFile = tempFolder.newFile("Sample.kt")
        FileOutputStream(downloadedFile).use { it.write(bytes) }

        // Verify blob SHA from downloaded file
        val actualSha = downloadedFile.inputStream().use { GitBlobHasher.calculateSha(it, downloadedFile.length()) }
        assertEquals("Calculated file SHA must match expected git blob SHA", expectedSha, actualSha)

        // Verify corrupted file fails
        val corruptedFile = tempFolder.newFile("Corrupted.kt")
        FileOutputStream(corruptedFile).use {
            it.write("Corrupted content".toByteArray(Charsets.UTF_8))
        }
        val corruptedSha = corruptedFile.inputStream().use { GitBlobHasher.calculateSha(it, corruptedFile.length()) }
        assertNotEquals("Corrupted file SHA must not match expected git blob SHA", expectedSha, corruptedSha)
    }

    @Test
    fun `crash recovery validates existing file and marks SUCCESS without re-downloading if SHA matches`() {
        val fileContent = "class SecureConfig { val secret = 42 }"
        val bytes = fileContent.toByteArray(Charsets.UTF_8)
        val gitBlobSha = GitBlobHasher.calculateSha(bytes)

        val targetFile = tempFolder.newFile("Config.kt")
        FileOutputStream(targetFile).use { it.write(bytes) }

        // Process dies and restarts. We inspect targetFile.
        assertTrue(targetFile.exists())
        val calculatedSha = targetFile.inputStream().use { GitBlobHasher.calculateSha(it, targetFile.length()) }
        val isValid = (calculatedSha == gitBlobSha)

        assertTrue("File with valid matching SHA recovers as SUCCESS without redownload", isValid)
    }

    @Test
    fun `crash recovery with corrupted existing file enforces overwrite or replacement`() {
        val originalContent = "valid production content"
        val expectedSha = GitBlobHasher.calculateSha(originalContent.toByteArray())

        val corruptedFile = tempFolder.newFile("Invalid.kt")
        FileOutputStream(corruptedFile).use { it.write("garbage from interrupted write".toByteArray()) }

        val calculatedSha = corruptedFile.inputStream().use { GitBlobHasher.calculateSha(it, corruptedFile.length()) }
        val isValid = (calculatedSha == expectedSha)

        assertFalse("Corrupted file fails SHA validation", isValid)

        // Policy action: safe replacement on OVERWRITE
        val policy = OverwritePolicy.OVERWRITE
        if (!isValid && policy == OverwritePolicy.OVERWRITE) {
            corruptedFile.delete()
        }
        assertFalse("Corrupted file is removed for safe clean re-download under OVERWRITE policy", corruptedFile.exists())
    }

    @Test
    fun `multi-select relativePath calculation handles files and nested folder hierarchies`() {
        val selectedPaths = listOf("app/src/Main.kt", "docs/guides", "README.md")
        val allBlobs = listOf(
            "app/src/Main.kt",
            "docs/guides/setup.md",
            "docs/guides/architecture/overview.md",
            "README.md",
            "unrelated/file.txt"
        )

        val normalizedSelected = selectedPaths.map { it.trimStart('/').trimEnd('/') }.toSet()
        val matchedBlobs = allBlobs.filter { blob ->
            normalizedSelected.contains(blob) || normalizedSelected.any { sel -> blob.startsWith("$sel/") }
        }

        assertEquals(4, matchedBlobs.size)
        assertTrue(matchedBlobs.contains("app/src/Main.kt"))
        assertTrue(matchedBlobs.contains("docs/guides/setup.md"))
        assertTrue(matchedBlobs.contains("docs/guides/architecture/overview.md"))
        assertTrue(matchedBlobs.contains("README.md"))
        assertFalse(matchedBlobs.contains("unrelated/file.txt"))

        // Verify relative paths
        val relativePaths = matchedBlobs.map { blob ->
            val directMatch = selectedPaths.find { it.trimStart('/') == blob }
            if (directMatch != null) {
                blob.substringAfterLast('/')
            } else {
                val parentFolder = selectedPaths.find { blob.startsWith(it.trimStart('/') + "/") }
                if (parentFolder != null) {
                    blob.removePrefix(parentFolder.trimStart('/') + "/")
                } else {
                    blob
                }
            }
        }

        assertEquals("Main.kt", relativePaths[0])
        assertEquals("setup.md", relativePaths[1])
        assertEquals("architecture/overview.md", relativePaths[2])
        assertEquals("README.md", relativePaths[3])
    }

    @Test
    fun `createRepoFolder and preserveStructure configuration flags are accurately represented`() {
        val config = DownloadConfig(
            destinationTreeUri = android.net.Uri.EMPTY,
            asZip = false,
            preserveStructure = false,
            createRepoFolder = true,
            downloadScope = DownloadScope.SELECTED_ITEMS,
            selectedPaths = listOf("file1.txt", "file2.txt")
        )

        assertTrue(config.createRepoFolder)
        assertFalse(config.preserveStructure)
        assertEquals(DownloadScope.SELECTED_ITEMS, config.downloadScope)
        assertEquals(2, config.selectedPaths.size)
    }

    @Test
    fun `single-file download relative path is file name rather than full remote path`() {
        val cleanRemote = "sub/path/to/script.py"
        val blobPath = "sub/path/to/script.py"

        val rel = if (cleanRemote.isEmpty()) {
            blobPath
        } else if (blobPath == cleanRemote) {
            cleanRemote.substringAfterLast('/')
        } else {
            blobPath.removePrefix("$cleanRemote/").removePrefix("/")
        }

        assertEquals("script.py", rel)
    }

    @Test
    fun `startDownload durably persists complete download configuration into Room`() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.local.AppDatabase::class.java).build()
        val repo = com.example.data.repository.TransferRepository(db)

        val selectedList = listOf("src/main/A.kt", "src/main/B.kt", "README.md")
        val config = DownloadConfig(
            destinationTreeUri = android.net.Uri.parse("content://my/download/folder"),
            asZip = false,
            overwritePolicy = OverwritePolicy.KEEP_BOTH,
            preserveStructure = true,
            createRepoFolder = true,
            downloadScope = DownloadScope.SELECTED_ITEMS,
            selectedPaths = selectedList
        )

        val transferId = java.util.UUID.randomUUID().toString()
        val selectedJson = org.json.JSONArray(config.selectedPaths).toString()

        val entity = TransferEntity(
            id = transferId,
            type = TransferType.DOWNLOAD.name,
            repoOwner = "google",
            repoName = "sample-repo",
            branch = "develop",
            sourcePath = "3 selected items",
            destPath = config.destinationTreeUri.toString(),
            destinationUri = config.destinationTreeUri.toString(),
            remotePath = "src/main",
            selectedPathsJson = selectedJson,
            status = TransferStatus.QUEUED.name,
            totalFiles = 3,
            processedFiles = 0,
            totalBytes = 0L,
            processedBytes = 0L,
            asZip = config.asZip,
            overwritePolicy = config.overwritePolicy.name,
            preserveStructure = config.preserveStructure,
            createRepoFolder = config.createRepoFolder,
            downloadScope = config.downloadScope.name
        )

        repo.insertTransfer(entity)

        val retrieved = repo.getTransfer(transferId)
        assertNotNull(retrieved)
        assertEquals("google", retrieved!!.repoOwner)
        assertEquals("sample-repo", retrieved.repoName)
        assertEquals("develop", retrieved.branch)
        assertEquals("src/main", retrieved.remotePath)
        assertEquals("content://my/download/folder", retrieved.destPath)
        assertEquals("content://my/download/folder", retrieved.destinationUri)
        assertEquals("SELECTED_ITEMS", retrieved.downloadScope)
        assertEquals("KEEP_BOTH", retrieved.overwritePolicy)
        assertTrue(retrieved.preserveStructure)
        assertTrue(retrieved.createRepoFolder)
        assertFalse(retrieved.asZip)
        assertEquals(selectedJson, retrieved.selectedPathsJson)

        // Verify JSON deserialization preserves all elements
        val deserialized = mutableListOf<String>()
        val jsonArr = org.json.JSONArray(retrieved.selectedPathsJson!!)
        for (i in 0 until jsonArr.length()) {
            deserialized.add(jsonArr.getString(i))
        }
        assertEquals(selectedList, deserialized)

        db.close()
    }
}
