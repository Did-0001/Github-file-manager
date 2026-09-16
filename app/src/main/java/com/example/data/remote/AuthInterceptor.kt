package com.example.data.remote

import com.example.data.local.SecureStorage
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val secureStorage: SecureStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Accept", "application/vnd.github.v3+json")

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

        return response
    }
}

object RateLimitTracker {
    @Volatile var remaining: Int? = null
    @Volatile var limit: Int? = null
    @Volatile var resetEpochSeconds: Long? = null
}
