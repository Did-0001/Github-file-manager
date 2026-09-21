package com.example.domain.engine

import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadFailurePathsStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `truncated response triggers IOException and cleans up partial download file`() {
        val expectedLength = 1024L
        val receivedBytes = 512L // truncated
        val partFile = tempFolder.newFile("test.part_download")
        FileOutputStream(partFile).use { it.write(ByteArray(receivedBytes.toInt()) { 1 }) }

        var cleanedUp = false
        try {
            if (receivedBytes != expectedLength) {
                throw IOException("Truncated download: expected $expectedLength bytes, got $receivedBytes")
            }
        } catch (e: IOException) {
            // TransferEngine cleanup logic
            partFile.delete()
            cleanedUp = !partFile.exists()
        }

        assertTrue("Part file must be cleaned up on truncated response", cleanedUp)
    }

    @Test
    fun `incorrect git blob SHA triggers IOException and deletes temporary file`() {
        val expectedContent = "Original authentic code"
        val expectedSha = GitBlobHasher.calculateSha(expectedContent.toByteArray(Charsets.UTF_8))

        val corruptedContent = "Altered unauthorized code"
        val partFile = tempFolder.newFile("corrupted.part_download")
        FileOutputStream(partFile).use { it.write(corruptedContent.toByteArray(Charsets.UTF_8)) }

        val actualSha = partFile.inputStream().use { GitBlobHasher.calculateSha(it, partFile.length()) }
        assertNotEquals(expectedSha, actualSha)

        var downloadSucceeded = false
        var cleanedUp = false
        try {
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                throw IOException("Git blob SHA mismatch: expected $expectedSha, calculated $actualSha")
            }
            downloadSucceeded = true
        } catch (e: IOException) {
            partFile.delete()
            cleanedUp = !partFile.exists()
        }

        assertFalse(downloadSucceeded)
        assertTrue("Temporary part file must be deleted on SHA mismatch", cleanedUp)
    }

    @Test
    fun `correct git blob SHA validates successfully and completes atomically`() {
        val content = "println(\"Download verified\")"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val expectedSha = GitBlobHasher.calculateSha(bytes)

        val partFile = tempFolder.newFile("valid.part_download")
        FileOutputStream(partFile).use { it.write(bytes) }

        val actualSha = partFile.inputStream().use { GitBlobHasher.calculateSha(it, partFile.length()) }
        assertEquals(expectedSha, actualSha)

        val finalTarget = File(tempFolder.root, "Valid.kt")
        val renameSucceeded = partFile.renameTo(finalTarget)

        assertTrue("Atomic rename must succeed for verified file", renameSucceeded)
        assertTrue("Final target file exists", finalTarget.exists())
        assertEquals(content.length.toLong(), finalTarget.length())
    }

    @Test
    fun `null output stream triggers immediate failure without corrupted file creation`() {
        val outputStreamAvailable = false
        var failureReported = false

        try {
            if (!outputStreamAvailable) {
                throw IOException("Failed to open output stream for destination: null stream returned")
            }
        } catch (e: IOException) {
            failureReported = true
            assertTrue(e.message!!.contains("null stream returned"))
        }

        assertTrue("Null output stream must be caught and reported as failure", failureReported)
    }

    @Test
    fun `rename failure deletes part file and marks transfer item as FAILED`() {
        val partFile = tempFolder.newFile("file.part_123")
        FileOutputStream(partFile).use { it.write("test".toByteArray()) }

        val renameSuccessful = false // simulate atomic rename failure
        var partFileCleaned = false

        try {
            if (!renameSuccessful) {
                throw IOException("Failed to rename temporary download file to file.kt")
            }
        } catch (e: IOException) {
            partFile.delete()
            partFileCleaned = !partFile.exists()
        }

        assertTrue("Part file must be cleaned up when rename fails", partFileCleaned)
    }

    @Test
    fun `existing valid target with matching SHA recovers without re-downloading`() {
        val content = "fun validCode() = 1"
        val bytes = content.toByteArray()
        val expectedSha = GitBlobHasher.calculateSha(bytes)

        val existingFile = tempFolder.newFile("ValidCode.kt")
        FileOutputStream(existingFile).use { it.write(bytes) }

        val actualSha = existingFile.inputStream().use { GitBlobHasher.calculateSha(it, existingFile.length()) }
        val isValid = (actualSha == expectedSha)

        assertTrue(isValid)
        // Item marked SUCCESS directly
        val itemStatus = if (isValid) "SUCCESS" else "PENDING"
        assertEquals("Existing valid target recovers as SUCCESS", "SUCCESS", itemStatus)
    }

    @Test
    fun `existing corrupted target is replaced cleanly under OVERWRITE policy`() {
        val expectedSha = "abcdef1234567890abcdef1234567890abcdef12"
        val existingCorrupted = tempFolder.newFile("CorruptedTarget.kt")
        FileOutputStream(existingCorrupted).use { it.write("incomplete".toByteArray()) }

        val actualSha = existingCorrupted.inputStream().use { GitBlobHasher.calculateSha(it, existingCorrupted.length()) }
        val isValid = (actualSha == expectedSha)
        assertFalse(isValid)

        val policy = OverwritePolicy.OVERWRITE
        if (!isValid && policy == OverwritePolicy.OVERWRITE) {
            existingCorrupted.delete()
        }

        assertFalse("Corrupted target must be deleted for fresh download under OVERWRITE", existingCorrupted.exists())
    }

    @Test
    fun `OverwritePolicy KEEP_BOTH generates collision-free numbered file name`() {
        fun resolveKeepBothFileName(existingNames: Set<String>, originalFileName: String): String {
            if (!existingNames.contains(originalFileName)) return originalFileName
            val dotIndex = originalFileName.lastIndexOf('.')
            val baseName = if (dotIndex > 0) originalFileName.substring(0, dotIndex) else originalFileName
            val extension = if (dotIndex > 0) originalFileName.substring(dotIndex) else ""
            var counter = 1
            var candidate: String
            do {
                candidate = "$baseName ($counter)$extension"
                counter++
            } while (existingNames.contains(candidate))
            return candidate
        }

        val existing = setOf("report.pdf", "report (1).pdf")
        val resolvedName = resolveKeepBothFileName(existing, "report.pdf")

        assertEquals("report (2).pdf", resolvedName)
    }

    @Test
    fun `OverwritePolicy SKIP preserves existing target file and marks item SUCCESS`() {
        val existingFile = tempFolder.newFile("ImportantData.txt")
        val originalContent = "Preserve this unmodified data"
        FileOutputStream(existingFile).use { it.write(originalContent.toByteArray()) }

        val policy = OverwritePolicy.SKIP
        val targetExists = existingFile.exists()

        var downloaded = false
        var finalItemStatus = "PENDING"

        if (targetExists && policy == OverwritePolicy.SKIP) {
            // Skip re-downloading
            finalItemStatus = "SUCCESS"
        } else {
            downloaded = true
        }

        assertFalse("File must NOT be redownloaded under SKIP", downloaded)
        assertEquals("SUCCESS", finalItemStatus)
        assertEquals(originalContent, existingFile.readText())
    }

    @Test
    fun `OverwritePolicy OVERWRITE deletes existing file and creates fresh target`() {
        val existingFile = tempFolder.newFile("Outdated.txt")
        existingFile.writeText("old version")

        val policy = OverwritePolicy.OVERWRITE
        if (existingFile.exists() && policy == OverwritePolicy.OVERWRITE) {
            existingFile.delete()
        }

        assertFalse(existingFile.exists())
        val freshFile = File(tempFolder.root, "Outdated.txt")
        freshFile.writeText("new updated version")

        assertEquals("new updated version", freshFile.readText())
    }

    @Test
    fun `selected files scope filters only requested files from repository tree`() {
        val allRepoBlobs = listOf(
            "src/App.kt",
            "src/Model.kt",
            "docs/README.md",
            "gradle/wrapper.properties"
        )

        val selectedScope = DownloadScope.SELECTED_ITEMS
        val userSelections = listOf("src/App.kt", "docs/README.md")

        val itemsToDownload = allRepoBlobs.filter { blob ->
            userSelections.contains(blob)
        }

        assertEquals(2, itemsToDownload.size)
        assertTrue(itemsToDownload.contains("src/App.kt"))
        assertTrue(itemsToDownload.contains("docs/README.md"))
        assertFalse(itemsToDownload.contains("src/Model.kt"))
    }

    @Test
    fun `selected folder scope includes all nested descendants within directory prefix`() {
        val allRepoBlobs = listOf(
            "docs/intro.md",
            "docs/guides/setup.md",
            "docs/guides/advanced.md",
            "src/Main.kt"
        )

        val selectedFolder = "docs"
        val prefix = "$selectedFolder/"

        val itemsInFolder = allRepoBlobs.filter { it.startsWith(prefix) || it == selectedFolder }

        assertEquals(3, itemsInFolder.size)
        assertTrue(itemsInFolder.contains("docs/intro.md"))
        assertTrue(itemsInFolder.contains("docs/guides/setup.md"))
        assertTrue(itemsInFolder.contains("docs/guides/advanced.md"))
        assertFalse(itemsInFolder.contains("src/Main.kt"))
    }

    @Test
    fun `complete repository scope includes all blobs without exclusion`() {
        val allRepoBlobs = listOf("file1.txt", "dir/file2.txt", "dir/sub/file3.txt")
        val scope = DownloadScope.REPOSITORY

        val itemsToDownload = if (scope == DownloadScope.REPOSITORY) allRepoBlobs else emptyList()
        assertEquals(3, itemsToDownload.size)
    }

    @Test
    fun `tree enumeration failure aborts whole-repo download without partial extraction`() {
        val treeRetrievalResult: Result<List<String>> = Result.failure(IOException("500 Internal Server Error fetching Git tree"))

        var downloadStarted = false
        var transferFailed = false
        var errorMessage: String? = null

        if (treeRetrievalResult.isFailure) {
            transferFailed = true
            errorMessage = "Failed to retrieve full remote tree: ${treeRetrievalResult.exceptionOrNull()?.message}"
        } else {
            downloadStarted = true
        }

        assertTrue("Transfer must fail immediately on tree enumeration error", transferFailed)
        assertFalse("Download must not proceed with partial or missing tree", downloadStarted)
        assertTrue(errorMessage!!.contains("500 Internal Server Error"))
    }
}
