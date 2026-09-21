package com.example.data.remote

import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ApiErrorType {
    AUTH_REQUIRED,      // 401
    PERMISSION_DENIED,  // 403 (when not rate limited)
    NOT_FOUND,          // 404
    CONFLICT,           // 409 or ref/branch update conflict (422 not-fast-forward / cannot be updated)
    VALIDATION_ERROR,   // 422 validation failure or 4xx client error
    RATE_LIMITED,       // 429 or 403 with rate limit indication
    NETWORK_ERROR,      // socket timeout, host unreachable, connection failure, IOException
    SERVER_ERROR;       // 500, 502, 503, 504, 5xx

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

    val isRetryable: Boolean
        get() = errorType.isRetryable

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

            val isRefConflict = code == 409 || (code == 422 && (
                rawError.contains("not a fast", ignoreCase = true) ||
                rawError.contains("cannot be updated", ignoreCase = true) ||
                rawError.contains("conflict", ignoreCase = true)
            ))

            val (errorType, message) = when {
                code == 401 -> Pair(ApiErrorType.AUTH_REQUIRED, "Authentication required. Please check your GitHub Personal Access Token.")
                isRateLimit -> Pair(ApiErrorType.RATE_LIMITED, "GitHub API rate limit exceeded.${if (calculatedRetryAfter != null) " Retry after $calculatedRetryAfter seconds." else ""}")
                code == 403 -> Pair(ApiErrorType.PERMISSION_DENIED, "Permission denied: You lack permission for this repository action. ($rawError)")
                code == 404 -> Pair(ApiErrorType.NOT_FOUND, "Resource not found: Repository, branch, commit, or file does not exist. ($rawError)")
                isRefConflict -> Pair(ApiErrorType.CONFLICT, "Conflict updating branch reference: The remote branch has moved or was updated concurrently. ($rawError)")
                code == 422 -> Pair(ApiErrorType.VALIDATION_ERROR, "Validation rejected by GitHub Git engine: Invalid path, mode, or SHA. ($rawError)")
                code in 500..599 -> Pair(ApiErrorType.SERVER_ERROR, "GitHub server error (HTTP $code). The operation may be retried.")
                code in 400..499 -> Pair(ApiErrorType.VALIDATION_ERROR, "GitHub client error (HTTP $code): $rawError")
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

            if (throwable is HttpException) {
                val response = throwable.response()
                if (response != null) {
                    return fromResponse(response)
                }
            }

            val isNetwork = throwable is SocketTimeoutException ||
                throwable is UnknownHostException ||
                throwable is ConnectException ||
                throwable is NoRouteToHostException ||
                throwable is InterruptedIOException ||
                throwable is IOException ||
                throwable.cause is IOException

            val errorType = when {
                isNetwork -> ApiErrorType.NETWORK_ERROR
                throwable is IllegalArgumentException -> ApiErrorType.VALIDATION_ERROR
                throwable is IllegalStateException -> ApiErrorType.VALIDATION_ERROR
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

