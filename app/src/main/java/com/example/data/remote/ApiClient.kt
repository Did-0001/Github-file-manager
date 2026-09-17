package com.example.data.remote

import com.example.data.local.SecureStorage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class ApiClient(private val secureStorage: SecureStorage) {

    val moshi: Moshi = Moshi.Builder()
        .add(com.example.data.remote.dto.CreateTreeEntryDto::class.java, com.example.data.remote.dto.CreateTreeEntryJsonAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val safeLoggingInterceptor = HttpLoggingInterceptor { message ->
        // Redact any tokens or auth headers
        val redacted = if (message.contains("Authorization", ignoreCase = true) ||
            message.contains("Bearer", ignoreCase = true) ||
            message.contains("access_token", ignoreCase = true)
        ) {
            "[REDACTED AUTH HEADER/DATA]"
        } else {
            message
        }
        android.util.Log.d("GitHubHttp", redacted)
    }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val authInterceptor = AuthInterceptor(secureStorage)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(safeLoggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val gitHubApi: GitHubApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubApi::class.java)
    }

    val deviceAuthApi: DeviceAuthApi by lazy {
        val deviceClient = OkHttpClient.Builder()
            .addInterceptor(safeLoggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://github.com/")
            .client(deviceClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeviceAuthApi::class.java)
    }
}
