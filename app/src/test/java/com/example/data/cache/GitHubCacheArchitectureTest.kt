package com.example.data.cache

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.SecureStorage
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.ApiClient
import com.example.data.remote.dto.*
import com.example.data.repository.GitHubRepository
import com.example.domain.engine.TransferEngine
import com.example.domain.model.WipeMode
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GitHubCacheArchitectureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var mockWebServer: MockWebServer
    private lateinit var secureStorage: SecureStorage
    private lateinit var apiClient: ApiClient
    private lateinit var cacheManager: GitHubCacheManager
    private lateinit var gitHubRepository: GitHubRepository

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context = RuntimeEnvironment.getApplication()
        secureStorage = SecureStorage(context)
        secureStorage.saveToken("ghp_test_token_1234567890")

        val baseUrl = mockWebServer.url("/").toString()
        apiClient = ApiClient(secureStorage, customBaseUrl = baseUrl)
        cacheManager = GitHubCacheManager(context)
        gitHubRepository = GitHubRepository(apiClient, cacheManager)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        cacheManager.clearAllCache()
    }

    private val sampleRepoJson = """
        [
          {
            "id": 101,
            "name": "Repo-Alpha",
            "full_name": "octocat/Repo-Alpha",
            "private": true,
            "owner": {"login": "octocat", "avatar_url": null},
            "description": "Alpha Repository",
            "default_branch": "main",
            "updated_at": "2026-09-30T10:00:00Z"
          }
        ]
    """.trimIndent()

    private val sampleRepoBJson = """
        [
          {
            "id": 102,
            "name": "Repo-Beta",
            "full_name": "octocat/Repo-Beta",
            "private": false,
            "owner": {"login": "octocat", "avatar_url": null},
            "description": "Beta Repository",
            "default_branch": "main",
            "updated_at": "2026-09-30T10:05:00Z"
          }
        ]
    """.trimIndent()

    private val sampleBranchesJson = """
        [
          {
            "name": "main",
            "commit": {"sha": "sha_main_12345"},
            "protected": true
          },
          {
            "name": "develop",
            "commit": {"sha": "sha_dev_67890"},
            "protected": false
          }
        ]
    """.trimIndent()

    private val sampleDirectoryJson = """
        [
          {
            "name": "src",
            "path": "src",
            "sha": "sha_dir_src",
            "size": 0,
            "type": "dir",
            "download_url": null
          },
          {
            "name": "build.gradle.kts",
            "path": "build.gradle.kts",
            "sha": "sha_file_gradle",
            "size": 1024,
            "type": "file",
            "download_url": "https://api.github.com/raw/gradle"
          }
        ]
    """.trimIndent()

    @Test
    fun testCacheHit_avoidsDuplicateNetworkRequests() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleRepoJson))

        // First call: hits network and populates cache
        val firstResult = gitHubRepository.getAllUserRepos(bypassCache = false)
        assertTrue(firstResult.isSuccess)
        assertEquals(1, firstResult.getOrThrow().size)
        assertEquals("Repo-Alpha", firstResult.getOrThrow()[0].name)
        assertEquals(1, mockWebServer.requestCount)

        // Second call without bypass: served from cache, zero additional network calls
        val secondResult = gitHubRepository.getAllUserRepos(bypassCache = false)
        assertTrue(secondResult.isSuccess)
        assertEquals("Repo-Alpha", secondResult.getOrThrow()[0].name)
        assertEquals("Network request count should remain 1 on cache hit", 1, mockWebServer.requestCount)
    }

    @Test
    fun testRefreshBypass_forcesFreshNetworkCallAndUpdate() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleRepoJson))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleRepoBJson))

        // First call: populates cache with Repo-Alpha
        val firstResult = gitHubRepository.getAllUserRepos(bypassCache = false)
        assertEquals("Repo-Alpha", firstResult.getOrThrow()[0].name)
        assertEquals(1, mockWebServer.requestCount)

        // Call with bypassCache = true: forces live call to GitHub and receives Repo-Beta
        val bypassResult = gitHubRepository.getAllUserRepos(bypassCache = true)
        assertEquals("Repo-Beta", bypassResult.getOrThrow()[0].name)
        assertEquals(2, mockWebServer.requestCount)

        // Subsequent normal call now serves the updated cached Repo-Beta
        val cachedResult = gitHubRepository.getAllUserRepos(bypassCache = false)
        assertEquals("Repo-Beta", cachedResult.getOrThrow()[0].name)
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun testBranchMetadata_cacheAndMutationInvalidation() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleBranchesJson))

        // 1. Fetch branches and verify cached
        val branchesRes = gitHubRepository.getAllBranches("octocat", "Repo-Alpha", bypassCache = false)
        assertTrue(branchesRes.isSuccess)
        assertEquals(2, branchesRes.getOrThrow().size)
        assertEquals(1, mockWebServer.requestCount)

        // Cached hit
        val cachedBranches = gitHubRepository.getAllBranches("octocat", "Repo-Alpha", bypassCache = false)
        assertEquals(2, cachedBranches.getOrThrow().size)
        assertEquals(1, mockWebServer.requestCount)

        // 2. Branch mutation: Create branch (mock 201 Created)
        val createRefResponseJson = """{"ref":"refs/heads/feature-x","node_id":"N1","url":"","object":{"sha":"sha_feature_123","type":"commit","url":""}}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(createRefResponseJson))

        val createRes = gitHubRepository.createBranch("octocat", "Repo-Alpha", "feature-x", "sha_main_12345")
        assertTrue(createRes.isSuccess)

        // Verify branch cache for octocat/Repo-Alpha was invalidated by mutation
        val updatedBranchesJson = """
            [
              {"name": "main", "commit": {"sha": "sha_main_12345"}, "protected": true},
              {"name": "develop", "commit": {"sha": "sha_dev_67890"}, "protected": false},
              {"name": "feature-x", "commit": {"sha": "sha_feature_123"}, "protected": false}
            ]
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(updatedBranchesJson))

        val afterMutationBranches = gitHubRepository.getAllBranches("octocat", "Repo-Alpha", bypassCache = false)
        assertEquals(3, afterMutationBranches.getOrThrow().size)
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun testDirectoryAndFileContentCache_andClearAllOnSignOut() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleDirectoryJson))

        // Fetch directory contents
        val dirRes = gitHubRepository.getDirectoryContents("octocat", "Repo-Alpha", "", "main", bypassCache = false)
        assertTrue(dirRes.isSuccess)
        assertEquals(2, dirRes.getOrThrow().size)

        // Put file content in bounded LRU cache
        cacheManager.putFileContent("octocat", "Repo-Alpha", "main", "README.md", "# Hello World from Cache")
        assertEquals("# Hello World from Cache", cacheManager.getFileContent("octocat", "Repo-Alpha", "main", "README.md"))

        val statsBefore = cacheManager.getStats()
        assertTrue(statsBefore.directoryCount > 0)
        assertTrue(statsBefore.fileContentCount > 0)

        // Simulate sign-out / clearAllCache purge
        cacheManager.clearAllCache()

        val statsAfter = cacheManager.getStats()
        assertEquals(0, statsAfter.repoCount)
        assertEquals(0, statsAfter.branchCount)
        assertEquals(0, statsAfter.directoryCount)
        assertEquals(0, statsAfter.fileContentCount)
        assertEquals(0L, statsAfter.totalContentBytes)
        assertNull(cacheManager.getFileContent("octocat", "Repo-Alpha", "main", "README.md"))
    }

    @Test
    fun testTransferIsolation_destructivePreflightAndUploadNeverTrustsCache() = runBlocking {
        // 1. Populate directory cache with stale entry
        val staleItem = GitHubContentDto(
            name = "stale_cached_file.txt",
            path = "stale_cached_file.txt",
            sha = "stale_sha_111",
            size = 10,
            type = "file",
            downloadUrl = null
        )
        cacheManager.putDirectoryContents("octocat", "Repo-Alpha", "main", "", listOf(staleItem))
        assertNotNull(cacheManager.getDirectoryContents("octocat", "Repo-Alpha", "main", ""))

        // 2. Enqueue live responses for preflight & transfer: getRef (HEAD SHA), getTree, and live getDirectoryContents
        val refResponse = """{"ref":"refs/heads/main","node_id":"N","url":"","object":{"sha":"sha_live_head_999","type":"commit","url":""}}"""
        val treeResponse = """{"sha":"sha_live_tree_888","truncated":false,"tree":[{"path":"remote_file.txt","mode":"100644","type":"blob","sha":"blob_sha_777","size":50}]}"""

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(refResponse))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(treeResponse))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sampleDirectoryJson))

        val engine = TransferEngine(
            context = RuntimeEnvironment.getApplication(),
            gitHubRepository = gitHubRepository,
            transferRepository = com.example.data.repository.TransferRepository(
                com.example.data.local.AppDatabase.getInstance(RuntimeEnvironment.getApplication())
            )
        )

        // 3. Preflight safety check directly queries live branch ref (never cached)
        val report = engine.runPreflight(
            owner = "octocat",
            repo = "Repo-Alpha",
            branch = "main",
            destinationDir = "",
            files = emptyList(),
            isWipe = true,
            wipeMode = WipeMode.FULL_BRANCH,
            clearHistory = false
        )
        assertFalse(report.isBlocked)
        assertTrue(report.checks.isNotEmpty())
        assertEquals(1, mockWebServer.requestCount)

        // 4. Git full tree query for upload/diff verification directly queries git/trees API (never cached)
        val fullTreeRes = gitHubRepository.getFullTree("octocat", "Repo-Alpha", "sha_live_tree_888")
        assertTrue(fullTreeRes.isSuccess)
        assertEquals(1, fullTreeRes.getOrThrow().size)
        assertEquals(2, mockWebServer.requestCount)

        // 5. TransferEngine folder download with bypassCache = true bypasses stale cache entry and gets live files
        val liveDirRes = gitHubRepository.getDirectoryContents(
            owner = "octocat",
            repo = "Repo-Alpha",
            path = "",
            branch = "main",
            bypassCache = true
        )
        assertTrue(liveDirRes.isSuccess)
        val liveItems = liveDirRes.getOrThrow()
        assertEquals(2, liveItems.size)
        assertFalse("Live transfer check must NOT return stale cached file", liveItems.any { it.name == "stale_cached_file.txt" })
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun testSettingsScreen_rendersCacheArchitectureCard_andClearDialog() {
        var cleared = false
        var selectedPolicy: CacheExpirationPolicy? = null

        val sampleStats = CacheStats(
            repoCount = 4,
            branchCount = 12,
            directoryCount = 7,
            fileContentCount = 15,
            totalContentBytes = 256 * 1024L,
            policy = CacheExpirationPolicy.MINUTES_15
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", null),
                    selectedRepo = SelectedRepoInfo("octocat", "Repo-Alpha", "main", "main", false),
                    cacheStats = sampleStats,
                    onSetCacheExpirationPolicy = { selectedPolicy = it },
                    onClearCache = { cleared = true },
                    onOpenAuth = {},
                    onSignOut = {},
                    onOpenRepoSelector = {}
                )
            }
        }

        // Scroll to cache card and verify elements
        composeTestRule.onNodeWithTag("settings_lazy_column").performScrollToNode(hasTestTag("settings_cache_card"))
        composeTestRule.onNodeWithTag("settings_cache_card").assertIsDisplayed()

        // Verify stats display
        composeTestRule.onNodeWithTag("cache_repos_count").assertTextContains("4", substring = true)
        composeTestRule.onNodeWithTag("cache_branches_count").assertTextContains("12", substring = true)
        composeTestRule.onNodeWithTag("cache_dirs_count").assertTextContains("7", substring = true)
        composeTestRule.onNodeWithTag("cache_files_count").assertTextContains("15", substring = true)

        // Verify warning card explaining stale data and private storage
        composeTestRule.onNodeWithTag("cache_warning_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stale Data & Local Storage Notice").assertIsDisplayed()

        // Verify policy chips and click 1 Hour
        composeTestRule.onNodeWithTag("cache_policy_hours_1").performClick()
        assertEquals(CacheExpirationPolicy.HOURS_1, selectedPolicy)

        // Click Clear Cache button and verify confirmation dialog opens
        composeTestRule.onNodeWithTag("settings_clear_cache_btn").performClick()
        composeTestRule.onNodeWithTag("clear_cache_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear Local Cache?").assertIsDisplayed()

        // Confirm clear
        composeTestRule.onNodeWithTag("confirm_clear_cache_button").performClick()
        assertTrue(cleared)
        composeTestRule.onNodeWithTag("clear_cache_confirm_dialog").assertDoesNotExist()
    }
}
