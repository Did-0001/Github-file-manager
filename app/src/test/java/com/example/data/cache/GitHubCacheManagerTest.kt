package com.example.data.cache

import com.example.data.remote.dto.BranchCommitDto
import com.example.data.remote.dto.GitHubBranchDto
import com.example.data.remote.dto.GitHubContentDto
import com.example.data.remote.dto.GitHubRepoDto
import com.example.data.remote.dto.RepoOwnerDto
import org.junit.Assert.*
import org.junit.Test

class GitHubCacheManagerTest {

    private fun sampleRepo(id: Long, name: String): GitHubRepoDto {
        return GitHubRepoDto(
            id = id,
            name = name,
            fullName = "octocat/$name",
            owner = RepoOwnerDto(login = "octocat", avatarUrl = null),
            isPrivate = true,
            defaultBranch = "main",
            description = "Sample repo $name",
            updatedAt = "2026-09-30T10:00:00Z"
        )
    }

    private fun sampleBranch(name: String, sha: String = "abc1234567890"): GitHubBranchDto {
        return GitHubBranchDto(
            name = name,
            commit = BranchCommitDto(sha = sha),
            isProtected = false
        )
    }

    private fun sampleContent(path: String, isDir: Boolean = false): GitHubContentDto {
        return GitHubContentDto(
            name = path.substringAfterLast('/'),
            path = path,
            sha = "sha_$path",
            size = 128,
            type = if (isDir) "dir" else "file",
            downloadUrl = null
        )
    }

    @Test
    fun testRepositoryCache_putGetAndExpiry() {
        val cacheManager = GitHubCacheManager()
        cacheManager.expirationPolicy = CacheExpirationPolicy.MINUTES_5

        val repos = listOf(sampleRepo(1, "repo1"), sampleRepo(2, "repo2"))
        val now = 1000000L

        cacheManager.putRepos(repos, timestampMillis = now)

        // 1. Valid fetch before expiration
        val cached = cacheManager.getRepos(bypassCache = false, currentTimeMillis = now + 60000L) // 1 min later
        assertNotNull(cached)
        assertEquals(2, cached!!.size)
        assertEquals("repo1", cached[0].name)

        // 2. Bypass cache explicitly
        val bypassed = cacheManager.getRepos(bypassCache = true, currentTimeMillis = now + 60000L)
        assertNull(bypassed)

        // 3. Expired fetch (5 mins + 1 ms)
        val expired = cacheManager.getRepos(bypassCache = false, currentTimeMillis = now + (5 * 60 * 1000L) + 1L)
        assertNull(expired)
    }

    @Test
    fun testBranchMetadataCache_putGetAndBypass() {
        val cacheManager = GitHubCacheManager()
        cacheManager.expirationPolicy = CacheExpirationPolicy.MINUTES_15

        val branches = listOf(sampleBranch("main"), sampleBranch("feature"))
        val now = 2000000L

        cacheManager.putBranches("octocat", "hello-world", branches, timestampMillis = now)
        cacheManager.putBranchDetails("octocat", "hello-world", "main", branches[0], timestampMillis = now)

        // Hit branches
        val cachedBranches = cacheManager.getBranches("octocat", "hello-world", bypassCache = false, currentTimeMillis = now + 500000L)
        assertNotNull(cachedBranches)
        assertEquals(2, cachedBranches!!.size)

        // Hit branch details
        val branchDetails = cacheManager.getBranchDetails("octocat", "hello-world", "main", bypassCache = false, currentTimeMillis = now + 500000L)
        assertNotNull(branchDetails)
        assertEquals("main", branchDetails!!.name)

        // Refresh bypass
        assertNull(cacheManager.getBranches("octocat", "hello-world", bypassCache = true, currentTimeMillis = now + 500000L))
        assertNull(cacheManager.getBranchDetails("octocat", "hello-world", "main", bypassCache = true, currentTimeMillis = now + 500000L))
    }

    @Test
    fun testDirectoryMetadataCache_putGetAndExpiry() {
        val cacheManager = GitHubCacheManager()
        cacheManager.expirationPolicy = CacheExpirationPolicy.HOURS_1

        val contents = listOf(sampleContent("src", isDir = true), sampleContent("README.md", isDir = false))
        val now = 3000000L

        cacheManager.putDirectoryContents("octocat", "hello-world", "main", "", contents, timestampMillis = now)

        val cached = cacheManager.getDirectoryContents("octocat", "hello-world", "main", "", bypassCache = false, currentTimeMillis = now + 30 * 60 * 1000L)
        assertNotNull(cached)
        assertEquals(2, cached!!.size)

        // Expired after 1 hour + 1 second
        val expired = cacheManager.getDirectoryContents("octocat", "hello-world", "main", "", bypassCache = false, currentTimeMillis = now + 60 * 60 * 1000L + 1000L)
        assertNull(expired)
    }

    @Test
    fun testBoundedFileContentCache_lruEvictionAndSize() {
        val cacheManager = GitHubCacheManager()
        val now = 4000000L

        // Store 55 items when MAX_CONTENT_ITEMS is 50
        for (i in 1..55) {
            cacheManager.putFileContent("octocat", "repo", "main", "file_$i.txt", "Content of file $i", timestampMillis = now)
        }

        val stats = cacheManager.getStats()
        // LRU cache bounded to max 50 items
        assertTrue("Cache size ${stats.fileContentCount} should be <= 50", stats.fileContentCount <= 50)

        // Most recent should be present
        val file55 = cacheManager.getFileContent("octocat", "repo", "main", "file_55.txt", bypassCache = false, currentTimeMillis = now)
        assertNotNull(file55)
        assertEquals("Content of file 55", file55)

        // Very old evicted item should be evicted
        val file1 = cacheManager.getFileContent("octocat", "repo", "main", "file_1.txt", bypassCache = false, currentTimeMillis = now)
        assertNull(file1)
    }

    @Test
    fun testClearAllCache_purgesAllPrivateData() {
        val cacheManager = GitHubCacheManager()
        val now = 5000000L

        cacheManager.putRepos(listOf(sampleRepo(1, "secret-repo")), timestampMillis = now)
        cacheManager.putBranches("octocat", "secret-repo", listOf(sampleBranch("main")), timestampMillis = now)
        cacheManager.putDirectoryContents("octocat", "secret-repo", "main", "secret_folder", listOf(sampleContent("secret.json")), timestampMillis = now)
        cacheManager.putFileContent("octocat", "secret-repo", "main", "secret.json", "{\"apiKey\":\"12345\"}", timestampMillis = now)

        val statsBefore = cacheManager.getStats()
        assertTrue(statsBefore.repoCount > 0)
        assertTrue(statsBefore.branchCount > 0)
        assertTrue(statsBefore.directoryCount > 0)
        assertTrue(statsBefore.fileContentCount > 0)

        // Trigger full clear (same as sign-out or manual clear in settings)
        cacheManager.clearAllCache()

        val statsAfter = cacheManager.getStats()
        assertEquals(0, statsAfter.repoCount)
        assertEquals(0, statsAfter.branchCount)
        assertEquals(0, statsAfter.directoryCount)
        assertEquals(0, statsAfter.fileContentCount)
        assertEquals(0L, statsAfter.totalContentBytes)

        assertNull(cacheManager.getRepos(bypassCache = false, currentTimeMillis = now))
        assertNull(cacheManager.getBranches("octocat", "secret-repo", bypassCache = false, currentTimeMillis = now))
        assertNull(cacheManager.getDirectoryContents("octocat", "secret-repo", "main", "secret_folder", bypassCache = false, currentTimeMillis = now))
        assertNull(cacheManager.getFileContent("octocat", "secret-repo", "main", "secret.json", bypassCache = false, currentTimeMillis = now))
    }

    @Test
    fun testManualPolicy_neverExpiresAutomatically() {
        val cacheManager = GitHubCacheManager()
        cacheManager.expirationPolicy = CacheExpirationPolicy.MANUAL

        val now = 6000000L
        cacheManager.putRepos(listOf(sampleRepo(99, "manual-repo")), timestampMillis = now)

        // 7 days later
        val cached = cacheManager.getRepos(bypassCache = false, currentTimeMillis = now + 7 * 24 * 3600 * 1000L)
        assertNotNull(cached)
        assertEquals(1, cached!!.size)
    }
}
