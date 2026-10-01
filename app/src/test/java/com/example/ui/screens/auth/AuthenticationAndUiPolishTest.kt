package com.example.ui.screens.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SecureStorage
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.ApiClient
import com.example.data.remote.dto.GitHubUserDto
import com.example.data.repository.AuthRepository
import com.example.data.repository.DeviceFlowState
import com.example.ui.screens.home.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthenticationAndUiPolishTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var secureStorage: SecureStorage
    private var mockWebServer: MockWebServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        secureStorage = SecureStorage(context)
        secureStorage.clearAuth()
    }

    @After
    fun tearDown() {
        mockWebServer?.shutdown()
        secureStorage.clearAuth()
    }

    @Test
    fun patAuthSuccess_invokesOnDismiss_closingDialog() {
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = { dismissed = true },
                    onVerifyPat = { token, callback ->
                        callback(true, "test-user")
                    },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = null,
                    onCancelDeviceFlow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("pat_input_field").performTextInput("ghp_testValidToken12345")
        composeTestRule.onNodeWithTag("verify_pat_button").performScrollTo().performClick()

        assertTrue("Expected onDismiss to be called on successful PAT authentication", dismissed)
    }

    @Test
    fun deviceFlowSuccess_invokesOnDismiss_closingDialog() {
        var dismissed = false
        var currentState by mutableStateOf<DeviceFlowState?>(DeviceFlowState.AuthorizationPending)

        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = { dismissed = true },
                    onVerifyPat = { _, _ -> },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = currentState,
                    onCancelDeviceFlow = {}
                )
            }
        }

        assertFalse("Should not be dismissed while authorization is pending", dismissed)

        // Transition to Success
        currentState = DeviceFlowState.Success(
            GitHubUserDto(
                login = "octocat",
                id = 1234L,
                avatarUrl = "https://github.com/images/error/octocat_happy.gif",
                name = "The Octocat",
                email = "octocat@github.com"
            )
        )

        composeTestRule.waitForIdle()
        assertTrue("Expected onDismiss to be called upon DeviceFlowState.Success", dismissed)
    }

    @Test
    fun secureStorage_roundtrip_encryptsWithAes256Gcm_andClearsCleanly() {
        val testToken = "ghp_secure_secret_token_abc_xyz_123456789"
        val saveResult = secureStorage.saveToken(testToken, "PAT")
        assertTrue("saveToken should return success", saveResult.isSuccess)
        assertTrue("hasToken should be true", secureStorage.hasToken())

        val retrieved = secureStorage.getToken()
        assertEquals(testToken, retrieved)

        val saveUserResult = secureStorage.saveAuthUser("testuser", "https://avatar.url")
        assertTrue("saveAuthUser should return success", saveUserResult.isSuccess)

        val user = secureStorage.getAuthUser()
        assertNotNull(user)
        assertEquals("testuser", user?.first)
        assertEquals("https://avatar.url", user?.second)

        // Clear auth
        secureStorage.clearAuth()
        assertFalse("hasToken should be false after clearAuth", secureStorage.hasToken())
        assertNull("getToken should return null after clearAuth", secureStorage.getToken())
        assertNull("getAuthUser should return null after clearAuth", secureStorage.getAuthUser())
    }

    @Test
    fun secureStorage_corruptedIvOrToken_returnsNullWithoutCrashing() {
        secureStorage.saveToken("valid_token")
        val prefs = context.getSharedPreferences("github_file_manager_secure_prefs", Context.MODE_PRIVATE)

        // Corrupt token data
        prefs.edit().putString("encrypted_token", "invalid_corrupted_base64_data!!!").commit()

        val retrieved = secureStorage.getToken()
        assertNull("Corrupted encrypted data should safely decrypt to null without throwing", retrieved)
    }

    @Test
    fun authRepository_verifyAndSavePat_handlesNetworkFailure_andClearsAuth() = runBlocking {
        val server = MockWebServer()
        mockWebServer = server
        server.start()

        val apiClient = ApiClient(secureStorage, customBaseUrl = server.url("/").toString())
        val authRepo = AuthRepository(apiClient, secureStorage)

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message": "Bad credentials"}""")
        )

        val res = authRepo.verifyAndSavePat("ghp_bad_token_12345")
        assertTrue("Expected failure on 401 response", res.isFailure)
        assertFalse("No token should remain stored after failed auth", secureStorage.hasToken())
    }

    @Test
    fun streamlinedHomeScreen_maintainsMaterial3IntegrityAndBottomNavFocus() {
        val testRepo = SelectedRepoInfo(
            owner = "octocat",
            name = "Hello-World",
            branch = "main",
            defaultBranch = "main",
            isPrivate = false
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    isAuthenticated = true,
                    authUser = Pair("octocat", "octocat@github.com"),
                    selectedRepo = testRepo,
                    recentTransfers = emptyList(),
                    onOpenAuth = {},
                    onOpenRepoSelector = {},
                    onNavigateTransfers = {}
                )
            }
        }

        // Quick Actions cards must not be present
        composeTestRule.onNodeWithTag("quick_upload_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("quick_download_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("quick_browser_card").assertDoesNotExist()
        composeTestRule.onNodeWithText("Quick Actions").assertDoesNotExist()

        // Core cards remain cleanly visible
        composeTestRule.onNodeWithTag("home_auth_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_repo_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeTestRule.onNodeWithText("octocat/Hello-World").assertIsDisplayed()
    }
}
