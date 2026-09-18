package com.example.data.remote

import com.example.data.local.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val secureStorage: SecureStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

        val token = secureStorage.getToken()
        if (!token.isNullOrBlank() && original.header("Authorization") == null) {
            builder.header("Authorization", "Bearer $token")
        }

        val response = chain.proceed(builder.build())

        // Rate limit monitoring
        response.header("x-ratelimit-remaining")?.toIntOrNull()?.let { remaining ->
            RateLimitTracker.remaining = remaining
        }
        response.header("x-ratelimit-limit")?.toIntOrNull()?.let { limit ->
            RateLimitTracker.limit = limit
        }
        response.header("x-ratelimit-reset")?.toLongOrNull()?.let { reset ->
            RateLimitTracker.resetEpochSeconds = reset
        }
        response.header("Retry-After")?.toLongOrNull()?.let { retryAfter ->
            RateLimitTracker.recordBackoff(retryAfter)
        }

        return response
    }
}

object RateLimitTracker {
    @Volatile var remaining: Int? = null
    @Volatile var limit: Int? = null
    @Volatile var resetEpochSeconds: Long? = null
    @Volatile var backoffUntilEpochMs: Long = 0L

    fun isRateLimited(): Boolean {
        if (System.currentTimeMillis() < backoffUntilEpochMs) return true
        val rem = remaining
        val resetSec = resetEpochSeconds
        if (rem != null && rem <= 0 && resetSec != null) {
            val nowSec = System.currentTimeMillis() / 1000
            return nowSec < resetSec
        }
        return false
    }

    fun recordBackoff(seconds: Long) {
        val until = System.currentTimeMillis() + (seconds * 1000L)
        if (until > backoffUntilEpochMs) {
            backoffUntilEpochMs = until
        }
    }

    fun getRemainingWaitSeconds(): Long {
        val nowMs = System.currentTimeMillis()
        if (backoffUntilEpochMs > nowMs) {
            return (backoffUntilEpochMs - nowMs) / 1000L
        }
        val resetSec = resetEpochSeconds
        if (remaining != null && remaining!! <= 0 && resetSec != null) {
            val nowSec = nowMs / 1000L
            return (resetSec - nowSec).coerceAtLeast(0L)
        }
        return 0L
    }
}
