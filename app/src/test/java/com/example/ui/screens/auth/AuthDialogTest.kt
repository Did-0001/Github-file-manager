package com.example.ui.screens.auth

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.remote.dto.DeviceCodeResponse
import com.example.data.repository.DeviceFlowState
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun patTab_initialState_verifyButtonDisabled() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = {},
                    onVerifyPat = { _, _ -> },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = null,
                    onCancelDeviceFlow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("auth_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("GitHub Authentication").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal Access Token").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pat_input_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("verify_pat_button").assertIsNotEnabled()
    }

    @Test
    fun patTab_inputToken_enablesVerifyButton_andTogglesVisibility() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = {},
                    onVerifyPat = { _, _ -> },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = null,
                    onCancelDeviceFlow = {}
                )
            }
        }

        val patInput = composeTestRule.onNodeWithTag("pat_input_field")
        patInput.performTextInput("ghp_1234567890abcdef")

        val verifyBtn = composeTestRule.onNodeWithTag("verify_pat_button")
        verifyBtn.assertIsEnabled()

        val toggleBtn = composeTestRule.onNodeWithTag("toggle_token_visibility")
        toggleBtn.assertIsDisplayed()
        toggleBtn.performClick()
    }

    @Test
    fun patTab_verifySuccess_displaysSuccessMessage() {
        var tokenVerified: String? = null

        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = {},
                    onVerifyPat = { token, callback ->
                        tokenVerified = token
                        callback(true, "octocat")
                    },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = null,
                    onCancelDeviceFlow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("pat_input_field").performTextInput("ghp_validToken")
        composeTestRule.onNodeWithTag("verify_pat_button").performScrollTo().performClick()

        assertEquals("ghp_validToken", tokenVerified)
        composeTestRule.onNodeWithTag("pat_success_message").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Authenticated successfully as @octocat!").assertExists()
    }

    @Test
    fun patTab_verifyFailure_displaysErrorMessage() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = {},
                    onVerifyPat = { _, callback ->
                        callback(false, "Bad credentials: 401 Unauthorized")
                    },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = null,
                    onCancelDeviceFlow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("pat_input_field").performTextInput("ghp_invalidToken")
        composeTestRule.onNodeWithTag("verify_pat_button").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("pat_error_message").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Bad credentials: 401 Unauthorized").assertExists()
    }

    @Test
    fun closeButton_invokesOnDismiss() {
        var dismissed = false

        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = { dismissed = true },
                    onVerifyPat = { _, _ -> },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = null,
                    onCancelDeviceFlow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("auth_dialog_close").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun tabSwitch_toDeviceFlow_displaysClientIdAndStartButton() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = {},
                    onVerifyPat = { _, _ -> },
                    onRequestDeviceCode = { _, _ -> },
                    deviceFlowState = null,
                    onCancelDeviceFlow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("tab_device").performClick()

        composeTestRule.onNodeWithText("GitHub Device Authorization").assertIsDisplayed()
        composeTestRule.onNodeWithTag("oauth_client_id_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_device_flow_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_device_flow_button").assertIsEnabled()
    }

    @Test
    fun deviceFlow_startSuccess_displaysUserCodeAndCopyButton() {
        var requestedClientId: String? = null
        val fakeDeviceCodeResponse = DeviceCodeResponse(
            deviceCode = "dc_fake_123",
            userCode = "ABCD-1234",
            verificationUri = "https://github.com/login/device",
            expiresIn = 900,
            interval = 5
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = {},
                    onVerifyPat = { _, _ -> },
                    onRequestDeviceCode = { clientId, callback ->
                        requestedClientId = clientId
                        callback(true, null, fakeDeviceCodeResponse)
                    },
                    deviceFlowState = DeviceFlowState.AuthorizationPending,
                    onCancelDeviceFlow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("tab_device").performClick()
        composeTestRule.onNodeWithTag("start_device_flow_button").performScrollTo().performClick()

        assertEquals("Ov23liYf57l0s2eNn4aP", requestedClientId)
        composeTestRule.onNodeWithTag("device_code_display_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("device_user_code_text").assertExists()
        composeTestRule.onNodeWithText("ABCD-1234").assertExists()
        composeTestRule.onNodeWithTag("copy_device_code_button").assertExists()
        composeTestRule.onNodeWithTag("open_verification_url_button").assertExists()
        composeTestRule.onNodeWithTag("device_flow_status_text").assertExists()
    }

    @Test
    fun deviceFlow_cancelButton_resetsCodeAndInvokesCallback() {
        var cancelFlowInvoked = false
        val fakeDeviceCodeResponse = DeviceCodeResponse(
            deviceCode = "dc_fake_123",
            userCode = "ABCD-1234",
            verificationUri = "https://github.com/login/device",
            expiresIn = 900,
            interval = 5
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                AuthDialog(
                    onDismiss = {},
                    onVerifyPat = { _, _ -> },
                    onRequestDeviceCode = { _, callback ->
                        callback(true, null, fakeDeviceCodeResponse)
                    },
                    deviceFlowState = DeviceFlowState.AuthorizationPending,
                    onCancelDeviceFlow = { cancelFlowInvoked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("tab_device").performClick()
        composeTestRule.onNodeWithTag("start_device_flow_button").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("device_code_display_card").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("cancel_device_flow_button").performScrollTo().performClick()
        assertTrue(cancelFlowInvoked)
        composeTestRule.onNodeWithTag("device_code_display_card").assertDoesNotExist()
    }
}
