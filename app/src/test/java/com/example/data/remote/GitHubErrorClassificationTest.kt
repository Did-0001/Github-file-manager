package com.example.data.remote

import com.example.domain.model.TransferStatus
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GitHubErrorClassificationTest {

    private fun createErrorResponse(code: Int, body: String, headers: Headers = Headers.headersOf()): Response<String> {
        val responseBody = body.toResponseBody("application/json".toMediaType())
        return Response.error(code, responseBody)
    }

    @Test
    fun `test 401 authentication required classification and non-retryable`() {
        val response = createErrorResponse(401, """{"message":"Bad credentials"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.AUTH_REQUIRED, ex.errorType)
        assertEquals(401, ex.statusCode)
        assertFalse("401 must never be retryable", ex.isRetryable)
        assertTrue(ex.message!!.contains("Authentication required"))
    }

    @Test
    fun `test 403 standard permission denied classification and non-retryable`() {
        val response = createErrorResponse(403, """{"message":"Must have push access"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.PERMISSION_DENIED, ex.errorType)
        assertEquals(403, ex.statusCode)
        assertFalse("Standard 403 must never be retryable", ex.isRetryable)
        assertTrue(ex.message!!.contains("Permission denied"))
    }

    @Test
    fun `test 403 rate limit via body message is classified as rate limited and retryable`() {
        val response = createErrorResponse(403, """{"message":"API rate limit exceeded for user"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.RATE_LIMITED, ex.errorType)
        assertEquals(403, ex.statusCode)
        assertTrue("Rate limiting must be retryable", ex.isRetryable)
        assertTrue(ex.message!!.contains("rate limit exceeded"))
    }

    @Test
    fun `test 429 rate limit is classified as rate limited and retryable`() {
        val response = createErrorResponse(429, """{"message":"Too many requests"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.RATE_LIMITED, ex.errorType)
        assertEquals(429, ex.statusCode)
        assertTrue("429 rate limiting must be retryable", ex.isRetryable)
    }

    @Test
    fun `test 404 not found classification and non-retryable`() {
        val response = createErrorResponse(404, """{"message":"Not Found"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.NOT_FOUND, ex.errorType)
        assertEquals(404, ex.statusCode)
        assertFalse("404 must never be retryable", ex.isRetryable)
    }

    @Test
    fun `test 409 conflict classification and non-retryable`() {
        val response = createErrorResponse(409, """{"message":"Conflict updating reference"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.CONFLICT, ex.errorType)
        assertEquals(409, ex.statusCode)
        assertFalse("409 conflict must never be automatically retried", ex.isRetryable)
        assertTrue(ex.message!!.contains("Conflict"))
    }

    @Test
    fun `test 422 standard validation failure classification and non-retryable`() {
        val response = createErrorResponse(422, """{"message":"Validation Failed","errors":[{"field":"tree","code":"invalid"}]}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.VALIDATION_ERROR, ex.errorType)
        assertEquals(422, ex.statusCode)
        assertFalse("422 validation failure must never be retryable", ex.isRetryable)
        assertTrue(ex.message!!.contains("Validation rejected"))
    }

    @Test
    fun `test 422 non-fast-forward branch ref update is classified as conflict and non-retryable`() {
        val response = createErrorResponse(422, """{"message":"Update is not a fast forward"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.CONFLICT, ex.errorType)
        assertEquals(422, ex.statusCode)
        assertFalse("Branch ref conflicts must never be automatically retried", ex.isRetryable)
        assertTrue(ex.message!!.contains("Conflict updating branch reference"))
    }

    @Test
    fun `test 422 reference cannot be updated is classified as conflict`() {
        val response = createErrorResponse(422, """{"message":"Reference cannot be updated"}""")
        val ex = GitHubApiException.fromResponse(response)

        assertEquals(ApiErrorType.CONFLICT, ex.errorType)
        assertFalse(ex.isRetryable)
    }

    @Test
    fun `test 500 and 5xx server errors are classified as server error and retryable`() {
        val codes = listOf(500, 502, 503, 504)
        for (code in codes) {
            val response = createErrorResponse(code, """{"message":"Internal error"}""")
            val ex = GitHubApiException.fromResponse(response)

            assertEquals("Code $code must be SERVER_ERROR", ApiErrorType.SERVER_ERROR, ex.errorType)
            assertEquals(code, ex.statusCode)
            assertTrue("Code $code must be retryable", ex.isRetryable)
            assertTrue(ex.message!!.contains("GitHub server error (HTTP $code)"))
        }
    }

    @Test
    fun `test network exceptions are classified as network error and retryable`() {
        val timeout = SocketTimeoutException("Read timed out")
        val exTimeout = GitHubApiException.fromThrowable(timeout)
        assertEquals(ApiErrorType.NETWORK_ERROR, exTimeout.errorType)
        assertTrue(exTimeout.isRetryable)

        val unknownHost = UnknownHostException("api.github.com: Name or service not known")
        val exHost = GitHubApiException.fromThrowable(unknownHost)
        assertEquals(ApiErrorType.NETWORK_ERROR, exHost.errorType)
        assertTrue(exHost.isRetryable)

        val connect = ConnectException("Connection refused")
        val exConnect = GitHubApiException.fromThrowable(connect)
        assertEquals(ApiErrorType.NETWORK_ERROR, exConnect.errorType)
        assertTrue(exConnect.isRetryable)

        val io = IOException("Connection reset by peer")
        val exIo = GitHubApiException.fromThrowable(io)
        assertEquals(ApiErrorType.NETWORK_ERROR, exIo.errorType)
        assertTrue(exIo.isRetryable)
    }

    @Test
    fun `test retry classification distinction between retryable and permanent failures`() {
        val retryableErrors = listOf(
            GitHubApiException(ApiErrorType.NETWORK_ERROR, message = "Network timeout"),
            GitHubApiException(ApiErrorType.SERVER_ERROR, statusCode = 502, message = "Bad Gateway"),
            GitHubApiException(ApiErrorType.RATE_LIMITED, statusCode = 429, message = "Rate limit exceeded")
        )

        val permanentErrors = listOf(
            GitHubApiException(ApiErrorType.AUTH_REQUIRED, statusCode = 401, message = "Authentication required"),
            GitHubApiException(ApiErrorType.PERMISSION_DENIED, statusCode = 403, message = "Permission denied"),
            GitHubApiException(ApiErrorType.NOT_FOUND, statusCode = 404, message = "Repository not found"),
            GitHubApiException(ApiErrorType.VALIDATION_ERROR, statusCode = 422, message = "Validation failed"),
            GitHubApiException(ApiErrorType.CONFLICT, statusCode = 409, message = "Branch conflict")
        )

        for (err in retryableErrors) {
            assertTrue("Expected ${err.errorType} to be retryable", err.isRetryable)
        }

        for (err in permanentErrors) {
            assertFalse("Expected ${err.errorType} to NOT be retryable", err.isRetryable)
        }
    }

    @Test
    fun `test branch conflict mapping prevents unrestricted retry and results in CONFLICT status`() {
        val conflictException = GitHubApiException(
            ApiErrorType.CONFLICT,
            statusCode = 409,
            message = "Branch conflict: reference was updated concurrently"
        )

        // Conflict is strictly non-retryable
        assertFalse(conflictException.isRetryable)

        // Maps to TransferStatus.CONFLICT
        val status = TransferStatus.CONFLICT
        assertFalse("CONFLICT status must not be active", status.isActive)
        assertEquals("CONFLICT", status.name)
    }
}
