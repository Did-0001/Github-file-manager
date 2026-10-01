package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SecureStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var secureStorage: SecureStorage

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context = ApplicationProvider.getApplicationContext<Context>()
        secureStorage = SecureStorage(context)
        secureStorage.saveToken("ghp_secret_token_1234567890")
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        secureStorage.clearAuth()
    }

    @Test
    fun `github api endpoint receives bearer token and github api headers`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("x-ratelimit-remaining", "4999")
                .setHeader("x-ratelimit-limit", "5000")
                .setBody("{}")
        )

        val baseUrl = mockWebServer.url("/").toString()
        val authInterceptor = AuthInterceptor(secureStorage, customBaseUrl = baseUrl)
        val client = OkHttpClient.Builder().addInterceptor(authInterceptor).build()

        val request = Request.Builder().url(mockWebServer.url("/user")).build()
        val response = client.newCall(request).execute()
        response.close()

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer ghp_secret_token_1234567890", recordedRequest.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", recordedRequest.getHeader("Accept"))
        assertEquals("2022-11-28", recordedRequest.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun `raw githubusercontent host NEVER receives bearer token or github api headers`() {
        val authInterceptor = AuthInterceptor(secureStorage, customBaseUrl = "https://api.github.com/")

        // Intercept a request destined to raw.githubusercontent.com
        val rawRequest = Request.Builder()
            .url("https://raw.githubusercontent.com/owner/repo/main/README.md")
            .build()

        val dummyChain = object : okhttp3.Interceptor.Chain {
            var interceptedRequest: Request? = null
            override fun request(): Request = rawRequest
            override fun proceed(request: Request): okhttp3.Response {
                interceptedRequest = request
                return okhttp3.Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(okhttp3.ResponseBody.create(null, "raw file content"))
                    .build()
            }
            override fun call(): okhttp3.Call = throw UnsupportedOperationException()
            override fun connectTimeoutMillis(): Int = 0
            override fun connection(): okhttp3.Connection? = null
            override fun readTimeoutMillis(): Int = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 0
        }

        val response = authInterceptor.intercept(dummyChain)
        response.close()

        val forwarded = dummyChain.interceptedRequest
        assertNotNull(forwarded)
        assertNull("Authorization header must NOT be attached to raw.githubusercontent.com", forwarded!!.header("Authorization"))
        assertNull("Accept vnd.github header must NOT be attached to raw.githubusercontent.com", forwarded.header("Accept"))
        assertNull("X-GitHub-Api-Version must NOT be attached to raw.githubusercontent.com", forwarded.header("X-GitHub-Api-Version"))
    }

    @Test
    fun `unintended external host NEVER receives bearer token`() {
        val authInterceptor = AuthInterceptor(secureStorage, customBaseUrl = "https://api.github.com/")

        val externalRequest = Request.Builder()
            .url("https://external-service.example.com/api/data")
            .build()

        val dummyChain = object : okhttp3.Interceptor.Chain {
            var interceptedRequest: Request? = null
            override fun request(): Request = externalRequest
            override fun proceed(request: Request): okhttp3.Response {
                interceptedRequest = request
                return okhttp3.Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(okhttp3.ResponseBody.create(null, "ok"))
                    .build()
            }
            override fun call(): okhttp3.Call = throw UnsupportedOperationException()
            override fun connectTimeoutMillis(): Int = 0
            override fun connection(): okhttp3.Connection? = null
            override fun readTimeoutMillis(): Int = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 0
        }

        val response = authInterceptor.intercept(dummyChain)
        response.close()

        val forwarded = dummyChain.interceptedRequest
        assertNotNull(forwarded)
        assertNull("Authorization header must NOT be attached to external hosts", forwarded!!.header("Authorization"))
    }

    @Test
    fun `requests without token do not add authorization header`() {
        secureStorage.clearAuth()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val baseUrl = mockWebServer.url("/").toString()
        val authInterceptor = AuthInterceptor(secureStorage, customBaseUrl = baseUrl)
        val client = OkHttpClient.Builder().addInterceptor(authInterceptor).build()

        val request = Request.Builder().url(mockWebServer.url("/user")).build()
        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockWebServer.takeRequest()
        assertNull("No auth header should be sent when token is cleared", recorded.getHeader("Authorization"))
    }
}
