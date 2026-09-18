package com.example.data.remote

import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ApiErrorType {
    AUTH_REQUIRED,
    PERMISSION_DENIED,
    NOT_FOUND,
    CONFLICT,
    VALIDATION_ERROR,
    RATE_LIMITED,
    NETWORK_ERROR,
    SERVER_ERROR;

    val isRetryable: Boolean
        get() = this == RATE_LIMITED || this == NETWORK_ERROR || this == SERVER_ERROR
}

class GitHubApiException(
    val errorType: ApiErrorType,
    val statusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    companion object {
        fun fromResponse(response: Response<*>): GitHubApiException {
            val code = response.code()
            val rawError = try {
                response.errorBody()?.string() ?: ""
            } catch (_: Exception) {
                ""
            }

            // Check Retry-After header or rate limit reset
            val retryAfterHeader = response.headers()["Retry-After"]?.toLongOrNull()
            val resetHeader = response.headers()["x-ratelimit-reset"]?.toLongOrNull()
            val remainingHeader = response.headers()["x-ratelimit-remaining"]?.toIntOrNull()

            val calculatedRetryAfter = retryAfterHeader ?: if (remainingHeader == 0 && resetHeader != null) {
                val nowSec = System.currentTimeMillis() / 1000
                (resetHeader - nowSec).coerceAtLeast(1L)
            } else null

            val isRateLimit = code == 429 || (code == 403 && (remainingHeader == 0 || rawError.contains("rate limit", ignoreCase = true)))

            val (errorType, message) = when {
                code == 401 -> Pair(ApiErrorType.AUTH_REQUIRED, "Authentication required. Please check your GitHub Personal Access Token.")
                isRateLimit -> Pair(ApiErrorType.RATE_LIMITED, "GitHub API rate limit exceeded.${if (calculatedRetryAfter != null) " Retry after $calculatedRetryAfter seconds." else ""}")
                code == 403 -> Pair(ApiErrorType.PERMISSION_DENIED, "Access forbidden: You lack permission for this repository action. ($rawError)")
                code == 404 -> Pair(ApiErrorType.NOT_FOUND, "Resource not found: Repository, branch, commit, or file does not exist. ($rawError)")
                code == 409 -> Pair(ApiErrorType.CONFLICT, "Branch concurrency conflict: The remote branch has moved or was updated concurrently. ($rawError)")
                code == 422 -> Pair(ApiErrorType.VALIDATION_ERROR, "Validation rejected by GitHub Git engine: Invalid path, mode, or SHA. ($rawError)")
                code in listOf(500, 502, 503, 504) -> Pair(ApiErrorType.SERVER_ERROR, "GitHub server error (HTTP $code). The operation may be retried.")
                else -> Pair(ApiErrorType.SERVER_ERROR, "GitHub API error (HTTP $code): $rawError")
            }

            return GitHubApiException(
                errorType = errorType,
                statusCode = code,
                retryAfterSeconds = calculatedRetryAfter,
                message = message
            )
        }

        fun fromThrowable(throwable: Throwable): GitHubApiException {
            if (throwable is GitHubApiException) return throwable
            val errorType = when (throwable) {
                is SocketTimeoutException -> ApiErrorType.NETWORK_ERROR
                is UnknownHostException -> ApiErrorType.NETWORK_ERROR
                is IOException -> ApiErrorType.NETWORK_ERROR
                else -> ApiErrorType.SERVER_ERROR
            }
            return GitHubApiException(
                errorType = errorType,
                statusCode = null,
                retryAfterSeconds = null,
                message = throwable.localizedMessage ?: "Network or connection error",
                cause = throwable
            )
        }
    }
}
