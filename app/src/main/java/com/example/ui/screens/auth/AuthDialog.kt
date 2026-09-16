package com.example.ui.screens.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.repository.DeviceFlowState
import com.example.ui.theme.GhDarkAccentBlue
import com.example.ui.theme.GhDarkAccentGreen
import com.example.ui.theme.GhDarkAccentOrange
import com.example.ui.theme.GhDarkAccentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthDialog(
    onDismiss: () -> Unit,
    onVerifyPat: (String, (Boolean, String?) -> Unit) -> Unit,
    onRequestDeviceCode: (String, (Boolean, String?, Any?) -> Unit) -> Unit,
    deviceFlowState: DeviceFlowState?,
    onCancelDeviceFlow: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: PAT, 1: Device Flow
    var patToken by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var patError by remember { mutableStateOf<String?>(null) }
    var patSuccessUser by remember { mutableStateOf<String?>(null) }

    // Device flow states
    var clientIdInput by remember { mutableStateOf("Ov23liYf57l0s2eNn4aP") } // Default device flow client id or custom
    var isStartingDeviceFlow by remember { mutableStateOf(false) }
    var userCode by remember { mutableStateOf<String?>(null) }
    var verificationUrl by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .padding(8.dp)
                .testTag("auth_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "GitHub Authentication",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Connect your account securely",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("auth_dialog_close")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Row
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Personal Access Token", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("tab_pat")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Device Flow", fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("tab_device")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedTab == 0) {
                        // PAT Login Content
                        Column {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Key,
                                            contentDescription = null,
                                            tint = GhDarkAccentBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Required GitHub Permissions",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "To browse repos, commit files, and update branches, create a PAT on GitHub with the 'repo' scope (or fine-grained with Contents Read/Write access). Tokens are encrypted in Android Keystore.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = patToken,
                                onValueChange = {
                                    patToken = it
                                    patError = null
                                },
                                label = { Text("GitHub Token (ghp_... or github_pat_...)") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { showPassword = !showPassword },
                                        modifier = Modifier.testTag("toggle_token_visibility")
                                    ) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (showPassword) "Hide Token" else "Show Token"
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("pat_input_field")
                            )

                            if (patError != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = patError!!,
                                    color = GhDarkAccentRed,
                                    fontSize = 12.sp
                                )
                            }

                            if (patSuccessUser != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = GhDarkAccentGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Authenticated successfully as @$patSuccessUser!",
                                        color = GhDarkAccentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    isVerifying = true
                                    patError = null
                                    onVerifyPat(patToken) { success, result ->
                                        isVerifying = false
                                        if (success) {
                                            patSuccessUser = result
                                        } else {
                                            patError = result ?: "Verification failed"
                                        }
                                    }
                                },
                                enabled = patToken.isNotBlank() && !isVerifying,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("verify_pat_button")
                            ) {
                                if (isVerifying) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Verifying Token...")
                                } else {
                                    Icon(imageVector = Icons.Default.LockOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Verify & Authenticate")
                                }
                            }
                        }
                    } else {
                        // Device Flow Content
                        Column {
                            Text(
                                text = "GitHub Device Authorization",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Authorizes the app directly in your browser without entering passwords or client secrets inside the app.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = clientIdInput,
                                onValueChange = { clientIdInput = it },
                                label = { Text("OAuth Client ID") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("oauth_client_id_input")
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            if (userCode == null) {
                                Button(
                                    onClick = {
                                        isStartingDeviceFlow = true
                                        onRequestDeviceCode(clientIdInput) { success, err, responseObj ->
                                            isStartingDeviceFlow = false
                                            if (success && responseObj != null) {
                                                val codeResp = responseObj as com.example.data.remote.dto.DeviceCodeResponse
                                                userCode = codeResp.userCode
                                                verificationUrl = codeResp.verificationUri
                                            }
                                        }
                                    },
                                    enabled = clientIdInput.isNotBlank() && !isStartingDeviceFlow,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("start_device_flow_button")
                                ) {
                                    if (isStartingDeviceFlow) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Requesting Device Code...")
                                    } else {
                                        Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Start Device Flow")
                                    }
                                }
                            } else {
                                // User code received
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, GhDarkAccentBlue),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Enter this code on GitHub:",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = userCode ?: "",
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = GhDarkAccentBlue,
                                            letterSpacing = 4.sp
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))

                                        Row {
                                            OutlinedButton(
                                                onClick = {
                                                    userCode?.let {
                                                        clipboardManager.setText(AnnotatedString(it))
                                                    }
                                                },
                                                modifier = Modifier.testTag("copy_device_code_button")
                                            ) {
                                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Copy Code")
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Button(
                                                onClick = {
                                                    val url = verificationUrl ?: "https://github.com/login/device"
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    context.startActivity(intent)
                                                },
                                                modifier = Modifier.testTag("open_verification_url_button")
                                            ) {
                                                Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Open GitHub")
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Polling state indicator
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val statusText = when (deviceFlowState) {
                                        is DeviceFlowState.AuthorizationPending -> "Waiting for authorization in browser..."
                                        is DeviceFlowState.SlowDown -> "Polling slowed down by GitHub..."
                                        is DeviceFlowState.Success -> "Authorized as @${deviceFlowState.user.login}!"
                                        is DeviceFlowState.Expired -> "Device code expired. Please restart."
                                        is DeviceFlowState.AccessDenied -> "Access was denied on GitHub."
                                        is DeviceFlowState.Error -> deviceFlowState.message
                                        else -> "Listening for GitHub confirmation..."
                                    }
                                    Text(
                                        text = statusText,
                                        fontSize = 12.sp,
                                        color = if (deviceFlowState is DeviceFlowState.Error || deviceFlowState is DeviceFlowState.AccessDenied) GhDarkAccentRed else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedButton(
                                    onClick = {
                                        userCode = null
                                        onCancelDeviceFlow()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cancel_device_flow_button")
                                ) {
                                    Text("Cancel Flow")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
