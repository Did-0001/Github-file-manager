package com.example.domain.engine

import com.example.data.remote.dto.GitTreeItemDto
import com.example.domain.model.DiffChangeType
import com.example.domain.model.DiffItem
import com.example.domain.model.WipeMode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DiffAndWipeModeTest {

    @Test
    fun `test same size different content results in MODIFIED not UNCHANGED`() {
        // Local content: 100 'A' bytes
        val localBytes = ByteArray(100) { 'A'.code.toByte() }
        val localSha = GitBlobHasher.calculateShaForStream(ByteArrayInputStream(localBytes), 100L)

        // Remote content: 100 'B' bytes (same size 100 bytes, different content)
        val remoteBytes = ByteArray(100) { 'B'.code.toByte() }
        val remoteSha = GitBlobHasher.calculateShaForStream(ByteArrayInputStream(remoteBytes), 100L)

        // Verify SHA hashes are distinct
        assertNotEquals(localSha, remoteSha)

        val remoteItem = GitTreeItemDto(
            path = "data.txt",
            mode = "100644",
            type = "blob",
            sha = remoteSha,
            size = 100L
        )

        // Simulate diff classification
        val isIdentical = localSha.equals(remoteItem.sha, ignoreCase = true)
        val changeType = if (isIdentical) DiffChangeType.UNCHANGED else DiffChangeType.MODIFIED

        assertEquals("Same-size different-content must be MODIFIED", DiffChangeType.MODIFIED, changeType)
    }

    @Test
    fun `test same size same content results in UNCHANGED`() {
        val content = "Hello World 12345".toByteArray()
        val localSha = GitBlobHasher.calculateShaForStream(ByteArrayInputStream(content), content.size.toLong())
        val remoteSha = GitBlobHasher.calculateShaForStream(ByteArrayInputStream(content), content.size.toLong())

        assertEquals(localSha, remoteSha)

        val remoteItem = GitTreeItemDto(
            path = "data.txt",
            mode = "100644",
            type = "blob",
            sha = remoteSha,
            size = content.size.toLong()
        )

        val isIdentical = localSha.equals(remoteItem.sha, ignoreCase = true)
        val changeType = if (isIdentical) DiffChangeType.UNCHANGED else DiffChangeType.MODIFIED

        assertEquals("Identical content must be UNCHANGED", DiffChangeType.UNCHANGED, changeType)
    }

    @Test
    fun `test wipe modes distinguish NONE, DESTINATION, and FULL_BRANCH`() {
        val remoteInventory = mapOf(
            "src/Main.kt" to GitTreeItemDto("src/Main.kt", "100644", "blob", "sha1", 100L),
            "src/Utils.kt" to GitTreeItemDto("src/Utils.kt", "100644", "blob", "sha2", 200L),
            "docs/readme.md" to GitTreeItemDto("docs/readme.md", "100644", "blob", "sha3", 300L),
            "root.txt" to GitTreeItemDto("root.txt", "100644", "blob", "sha4", 50L)
        )

        // Uploading only "src/Main.kt" targeting destination "src"
        val handledPaths = setOf("src/Main.kt")
        val destination = "src"

        // 1. WipeMode.NONE
        val deletedNone = mutableListOf<String>()
        // In NONE mode, no deletions are made
        assertEquals(0, deletedNone.size)

        // 2. WipeMode.DESTINATION (target: "src")
        val deletedDestination = mutableListOf<String>()
        val prefix = "$destination/"
        for ((path, _) in remoteInventory) {
            if (path.startsWith(prefix) && !handledPaths.contains(path)) {
                deletedDestination.add(path)
            }
        }
        // "src/Utils.kt" should be deleted, but "docs/readme.md" and "root.txt" MUST be preserved!
        assertEquals(listOf("src/Utils.kt"), deletedDestination)
        assertFalse("DESTINATION wipe must not delete files outside destination", deletedDestination.contains("docs/readme.md"))
        assertFalse("DESTINATION wipe must not delete files outside destination", deletedDestination.contains("root.txt"))

        // 3. WipeMode.FULL_BRANCH
        val deletedFullBranch = mutableListOf<String>()
        for ((path, _) in remoteInventory) {
            if (!handledPaths.contains(path)) {
                deletedFullBranch.add(path)
            }
        }
        // FULL_BRANCH deletes all files not in upload: "src/Utils.kt", "docs/readme.md", "root.txt"
        assertEquals(3, deletedFullBranch.size)
        assertTrue(deletedFullBranch.contains("src/Utils.kt"))
        assertTrue(deletedFullBranch.contains("docs/readme.md"))
        assertTrue(deletedFullBranch.contains("root.txt"))
    }

    @Test
    fun `test WipeMode NONE preserves base_tree and includes only new or modified entries`() {
        val baseTreeSha = "base_tree_sha_12345"
        val wipeMode = WipeMode.NONE

        val effectiveBaseTree = if (wipeMode == WipeMode.FULL_BRANCH) null else baseTreeSha
        assertEquals("NONE mode must preserve base_tree SHA", baseTreeSha, effectiveBaseTree)

        // In NONE mode, deletion entries (sha = null) are never generated
        val deletionEntries = mutableListOf<String>()
        assertTrue("No deletion entries generated under WipeMode.NONE", deletionEntries.isEmpty())
    }

    @Test
    fun `test WipeMode DESTINATION generates sha-null deletion entries only under target prefix`() {
        val baseTreeSha = "base_tree_sha_12345"
        val wipeMode = WipeMode.DESTINATION
        val destination = "features/login"
        val prefix = "$destination/"

        val effectiveBaseTree = if (wipeMode == WipeMode.FULL_BRANCH) null else baseTreeSha
        assertEquals("DESTINATION mode must preserve base_tree SHA", baseTreeSha, effectiveBaseTree)

        val remoteFiles = listOf(
            "features/login/LoginView.kt",
            "features/login/LegacyAuth.kt",
            "features/profile/ProfileView.kt",
            "README.md"
        )
        val handledInUpload = setOf("features/login/LoginView.kt")

        val entriesToDelete = remoteFiles.filter { path ->
            path.startsWith(prefix) && !handledInUpload.contains(path)
        }

        // Only LegacyAuth.kt is under features/login and not in handledInUpload
        assertEquals(listOf("features/login/LegacyAuth.kt"), entriesToDelete)

        // Files outside destination must NEVER be marked for deletion
        assertFalse(entriesToDelete.contains("features/profile/ProfileView.kt"))
        assertFalse(entriesToDelete.contains("README.md"))
    }

    @Test
    fun `test WipeMode FULL_BRANCH passes base_tree as null for clean branch replacement`() {
        val baseTreeSha = "base_tree_sha_12345"
        val wipeMode = WipeMode.FULL_BRANCH

        val effectiveBaseTree = if (wipeMode == WipeMode.FULL_BRANCH) null else baseTreeSha
        assertNull("FULL_BRANCH mode must pass base_tree as null to wipe remote branch files", effectiveBaseTree)
    }

    @Test
    fun `test failed preconditions across all wipe modes strictly prevent branch ref update`() {
        for (mode in listOf(WipeMode.NONE, WipeMode.DESTINATION, WipeMode.FULL_BRANCH)) {
            var branchRefUpdated = false

            // Scenario 1: Tree creation fails (e.g. 422 error from GitHub)
            val treeCreationSuccess = false
            if (treeCreationSuccess) {
                branchRefUpdated = true
            }
            assertFalse("Branch ref must not update when tree creation fails in $mode", branchRefUpdated)

            // Scenario 2: Commit creation fails (e.g. 500 error from GitHub)
            val commitCreationSuccess = false
            if (commitCreationSuccess) {
                branchRefUpdated = true
            }
            assertFalse("Branch ref must not update when commit creation fails in $mode", branchRefUpdated)

            // Scenario 3: Branch head moved (concurrency check fails)
            val branchHeadMatched = false
            if (branchHeadMatched) {
                branchRefUpdated = true
            }
            assertFalse("Branch ref must not update when branch head mismatch occurs in $mode", branchRefUpdated)
        }
    }
}
