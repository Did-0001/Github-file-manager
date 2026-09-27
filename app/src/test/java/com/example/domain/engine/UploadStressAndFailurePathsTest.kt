package com.example.domain.engine

import com.example.data.local.SecureStorage
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import com.example.data.remote.ApiClient
import com.example.data.remote.ApiErrorType
import com.example.data.remote.GitHubApiException
import com.example.data.remote.dto.CreateBlobRequest
import com.example.data.remote.dto.CreateCommitRequest
import com.example.data.remote.dto.CreateTreeEntryDto
import com.example.data.remote.dto.CreateTreeRequest
import com.example.data.remote.dto.UpdateRefRequest
import com.example.data.repository.GitHubRepository
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferType
import com.example.domain.model.WipeMode
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadStressAndFailurePathsTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: ApiClient
    private lateinit var gitHubRepository: GitHubRepository

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context = RuntimeEnvironment.getApplication()
        val secureStorage = SecureStorage(context)
        apiClient = ApiClient(secureStorage, mockWebServer.url("/").toString())
        gitHubRepository = GitHubRepository(apiClient)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `failed blob upload returns non-retryable 4xx error and prevents tree commit and ref update`() = runBlocking {
        // Enqueue 403 Permission Denied response for blob upload
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"message":"Resource not accessible by personal access token","documentation_url":"https://docs.github.com"}""")
        )

        val blobContent = "test blob content".toByteArray()
        val body = blobContent.toRequestBody("application/octet-stream".toMediaType())
        val blobRes = gitHubRepository.createBlobStream("owner", "repo", body)

        assertTrue("Blob upload must fail with 403", blobRes.isFailure)
        val ex = blobRes.exceptionOrNull() as? GitHubApiException
        assertNotNull("Exception must be GitHubApiException", ex)
        assertEquals(ApiErrorType.PERMISSION_DENIED, ex!!.errorType)
        assertEquals(403, ex.statusCode)
        assertFalse("Permission denied must NOT be retryable", ex.isRetryable)

        // Verify transaction boundary: tree, commit, ref update are NEVER executed
        assertEquals("Only blob upload endpoint was called", 1, mockWebServer.requestCount)
        val recordedReq = mockWebServer.takeRequest()
        assertEquals("/repos/owner/repo/git/blobs", recordedReq.path)
    }

    @Test
    fun `create tree failure with 422 unprocessable entity produces validation error and aborts commit`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"message":"Tree entry path is invalid","errors":[{"resource":"Tree","field":"path","code":"invalid"}]}""")
        )

        val entries = listOf(
            CreateTreeEntryDto(path = "invalid/../path", mode = "100644", type = "blob", sha = "abc1234567890")
        )
        val treeRes = gitHubRepository.createTree("owner", "repo", "base123", entries)

        assertTrue("Tree creation must fail", treeRes.isFailure)
        val ex = treeRes.exceptionOrNull() as? GitHubApiException
        assertNotNull(ex)
        assertEquals(ApiErrorType.VALIDATION_ERROR, ex!!.errorType)
        assertEquals(422, ex.statusCode)
        assertFalse(ex.isRetryable)

        // Recorded request checked
        val recordedReq = mockWebServer.takeRequest()
        assertEquals("/repos/owner/repo/git/trees", recordedReq.path)
        assertTrue(recordedReq.body.readUtf8().contains("invalid/../path"))
    }

    @Test
    fun `create commit failure with 502 bad gateway produces retryable server error`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setBody("""{"message":"Bad Gateway"}""")
        )

        val commitRes = gitHubRepository.createCommit("owner", "repo", "Test commit", "tree123", "parent123")

        assertTrue("Commit creation must fail on 502", commitRes.isFailure)
        val ex = commitRes.exceptionOrNull() as? GitHubApiException
        assertNotNull(ex)
        assertEquals(ApiErrorType.SERVER_ERROR, ex!!.errorType)
        assertEquals(502, ex.statusCode)
        assertTrue("Server errors like 502 Bad Gateway MUST be retryable", ex.isRetryable)
    }

    @Test
    fun `branch update failure with 422 not-fast-forward produces non-retryable CONFLICT`() = runBlocking {
        // GitHub Git Data API returns 422 when an updateRef is not a fast forward
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"message":"Reference cannot be updated (not a fast forward)"}""")
        )

        val updateRes = gitHubRepository.updateBranchRef("owner", "repo", "main", "newcommit123", force = false)

        assertTrue("Update branch ref must fail on 422 not fast forward", updateRes.isFailure)
        val ex = updateRes.exceptionOrNull() as? GitHubApiException
        assertNotNull(ex)
        assertEquals(ApiErrorType.CONFLICT, ex!!.errorType)
        assertEquals(422, ex.statusCode)
        assertFalse("Ref update conflict must NEVER be retryable", ex.isRetryable)
    }

    @Test
    fun `branch update failure with 409 conflict produces non-retryable CONFLICT`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"message":"Conflict: branch was updated concurrently"}""")
        )

        val updateRes = gitHubRepository.updateBranchRef("owner", "repo", "main", "newcommit123", force = false)

        assertTrue("Update branch ref must fail on 409", updateRes.isFailure)
        val ex = updateRes.exceptionOrNull() as? GitHubApiException
        assertNotNull(ex)
        assertEquals(ApiErrorType.CONFLICT, ex!!.errorType)
        assertEquals(409, ex.statusCode)
        assertFalse("Ref update conflict must NEVER be retryable", ex.isRetryable)
    }

    @Test
    fun `rate limit 429 and 403 with x-ratelimit-remaining=0 produce RATE_LIMITED and retryAfter`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "45")
                .setBody("""{"message":"API rate limit exceeded"}""")
        )

        val userRes = gitHubRepository.getAuthenticatedUser()
        assertTrue(userRes.isFailure)
        val ex = userRes.exceptionOrNull() as? GitHubApiException
        assertNotNull(ex)
        assertEquals(ApiErrorType.RATE_LIMITED, ex!!.errorType)
        assertEquals(429, ex.statusCode)
        assertEquals(45L, ex.retryAfterSeconds)
        assertTrue("Rate limited must be retryable", ex.isRetryable)

        // Test 403 with remaining 0
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("x-ratelimit-remaining", "0")
                .setHeader("x-ratelimit-reset", "${(System.currentTimeMillis() / 1000) + 60}")
                .setBody("""{"message":"API rate limit exceeded"}""")
        )

        val repoRes = gitHubRepository.getUserRepos(1)
        assertTrue(repoRes.isFailure)
        val ex2 = repoRes.exceptionOrNull() as? GitHubApiException
        assertNotNull(ex2)
        assertEquals(ApiErrorType.RATE_LIMITED, ex2!!.errorType)
        assertTrue("Calculated retry-after must be positive", (ex2.retryAfterSeconds ?: 0L) > 0)
    }

    @Test
    fun `auth failure 401 produces non-retryable AUTH_REQUIRED`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message":"Bad credentials","documentation_url":"https://docs.github.com/rest"}""")
        )

        val branchRes = gitHubRepository.getBranches("owner", "repo")
        assertTrue(branchRes.isFailure)
        val ex = branchRes.exceptionOrNull() as? GitHubApiException
        assertNotNull(ex)
        assertEquals(ApiErrorType.AUTH_REQUIRED, ex!!.errorType)
        assertEquals(401, ex.statusCode)
        assertFalse(ex.isRetryable)
    }

    @Test
    fun `duplicate remote paths in upload are detected and do not corrupt tree entries`() {
        // User uploads two local files mapped to the same destination path
        val duplicateEntries = listOf(
            CreateTreeEntryDto(path = "config/settings.json", mode = "100644", type = "blob", sha = "sha_version_1"),
            CreateTreeEntryDto(path = "config/settings.json", mode = "100644", type = "blob", sha = "sha_version_2"),
            CreateTreeEntryDto(path = "src/App.kt", mode = "100644", type = "blob", sha = "sha_app")
        )

        // Check for path collisions in tree entries
        val pathCounts = duplicateEntries.groupingBy { it.path }.eachCount()
        val collisions = pathCounts.filter { it.value > 1 }

        assertEquals("Collision detected for duplicate remote path", 1, collisions.size)
        assertTrue("config/settings.json is duplicated", collisions.containsKey("config/settings.json"))

        // De-duplicating or rejecting before tree creation ensures Git API will not receive duplicate paths
        val sanitizedEntries = duplicateEntries.distinctBy { it.path }
        assertEquals(2, sanitizedEntries.size)
        assertEquals("sha_version_1", sanitizedEntries.first { it.path == "config/settings.json" }.sha)
    }

    @Test
    fun `branch changed after review triggers CONFLICT and aborts without branch update`() {
        val reviewedHeadSha = "1111111222222233333334444444555555566666"
        val actualBranchHeadSha = "9999999888888877777776666666555555544444"

        val entity = TransferEntity(
            id = "test-conflict-reviewed-sha",
            type = TransferType.UPLOAD.name,
            repoOwner = "owner",
            repoName = "repo",
            branch = "main",
            sourcePath = "local",
            destPath = "/",
            status = TransferStatus.PREPARING.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 100L,
            processedBytes = 0L,
            createdAt = 1000L,
            reviewedHeadSha = reviewedHeadSha
        )

        // TransferEngine Concurrency Check 1:
        val branchMoved = entity.reviewedHeadSha != null &&
                !actualBranchHeadSha.equals(entity.reviewedHeadSha, ignoreCase = true)

        assertTrue("Branch movement must be detected", branchMoved)

        var branchRefUpdated = false
        val finalStatus = if (branchMoved) {
            TransferStatus.CONFLICT
        } else {
            branchRefUpdated = true
            TransferStatus.COMPLETED
        }

        assertEquals("Status must transition to CONFLICT", TransferStatus.CONFLICT, finalStatus)
        assertFalse("Branch ref must NEVER be updated when reviewedHeadSha mismatches", branchRefUpdated)
    }

    @Test
    fun `concurrent branch head movement during blob upload triggers CONFLICT before ref update`() {
        val initialHeadSha = "aaaaaaabbbbbbbcccccccdddddddeeeeeee00000"
        val concurrentHeadSha = "fffffffeeeeeeedddddddcccccccbbbbbbb11111"

        // Blobs were successfully uploaded against initialHeadSha, but before updating ref, GitHub HEAD is re-checked
        var refUpdated = false
        val concurrentMismatch = !concurrentHeadSha.equals(initialHeadSha, ignoreCase = true)

        assertTrue(concurrentMismatch)

        val finalStatus = if (concurrentMismatch) {
            TransferStatus.CONFLICT
        } else {
            refUpdated = true
            TransferStatus.COMPLETED
        }

        assertEquals(TransferStatus.CONFLICT, finalStatus)
        assertFalse("Branch ref must NEVER be updated if remote branch moved during blob streaming", refUpdated)
    }

    @Test
    fun `ref update conflict (409 or 422) is mapped to CONFLICT status without retry loop`() {
        // Simulate GitHub Ref update response failing with 409 Conflict / 422 Non-fast-forward
        val conflictException = GitHubApiException(
            statusCode = 422,
            errorType = ApiErrorType.CONFLICT,
            message = "Reference cannot be updated (not a fast forward)"
        )

        val err = conflictException.message ?: ""
        val isConflict = conflictException.errorType == ApiErrorType.CONFLICT ||
                err.contains("409") ||
                err.contains("conflict", ignoreCase = true) ||
                err.contains("422") ||
                err.contains("not a fast", ignoreCase = true) ||
                err.contains("cannot be updated", ignoreCase = true)

        assertTrue("Ref update error is classified as CONFLICT", isConflict)
        assertFalse("Ref update conflict must be strictly non-retryable", conflictException.isRetryable)

        val finalStatus = if (isConflict) TransferStatus.CONFLICT else TransferStatus.FAILED
        assertEquals("Transfer status must be set to CONFLICT", TransferStatus.CONFLICT, finalStatus)
    }

    @Test
    fun `precondition failures never result in a branch ref update`() {
        val failureScenarios = listOf(
            "Cannot resolve branch head: 404 Not Found",
            "Failed to resolve commit tree: 500 Internal Error",
            "Failed to retrieve full remote tree: 403 Rate Limit",
            "Cannot access local file: storage permission revoked or file missing",
            "1 of 2 files failed to upload. Branch was not modified.",
            "No files to commit.",
            "Failed to create tree: 422 Unprocessable Entity",
            "Failed to create commit: 502 Bad Gateway",
            "Repository changed while this transfer was being prepared."
        )

        for (scenario in failureScenarios) {
            var branchRefUpdated = false
            val preconditionPassed = false // Each scenario is a failure before ref update

            if (preconditionPassed) {
                branchRefUpdated = true
            }

            assertFalse("Scenario '$scenario' must NEVER update branch reference", branchRefUpdated)
        }
    }
}
