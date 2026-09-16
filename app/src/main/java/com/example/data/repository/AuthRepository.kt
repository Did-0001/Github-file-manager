package com.example.data.repository

import com.example.data.local.SecureStorage
import com.example.data.remote.ApiClient
import com.example.data.remote.dto.DeviceCodeResponse
import com.example.data.remote.dto.GitHubUserDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface DeviceFlowState {
    data class CodeReceived(val code: DeviceCodeResponse) : DeviceFlowState
    object AuthorizationPending : DeviceFlowState
    object SlowDown : DeviceFlowState
    data class Success(val user: GitHubUserDto) : DeviceFlowState
    data class Error(val message: String) : DeviceFlowState
    object Expired : DeviceFlowState
    object AccessDenied : DeviceFlowState
}

class AuthRepository(
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage
) {
    fun isAuthenticated(): Boolean = secureStorage.hasToken()

    fun getStoredUser(): Pair<String, String?>? = secureStorage.getAuthUser()

    suspend fun verifyAndSavePat(token: String): Result<GitHubUserDto> {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            return Result.failure(IllegalArgumentException("Token cannot be empty"))
        }

        // Temporarily store token to verify
        secureStorage.saveToken(cleanToken, "PAT")
        return try {
            val response = apiClient.gitHubApi.getAuthenticatedUser()
            if (response.isSuccessful && response.body() != null) {
                val user = response.body()!!
                secureStorage.saveAuthUser(user.login, user.avatarUrl)
                Result.success(user)
            } else {
                secureStorage.clearAuth()
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("Authentication failed (HTTP ${response.code()}): $errorBody"))
            }
        } catch (e: Exception) {
            secureStorage.clearAuth()
            Result.failure(e)
        }
    }

    suspend fun requestDeviceCode(clientId: String): Result<DeviceCodeResponse> {
        return try {
            val response = apiClient.deviceAuthApi.requestDeviceCode(clientId = clientId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val err = response.errorBody()?.string() ?: "Failed to request device code"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun pollDeviceAuthorization(clientId: String, deviceCode: String, initialIntervalSec: Int): Flow<DeviceFlowState> = flow {
        var intervalSec = if (initialIntervalSec > 0) initialIntervalSec else 5
        while (true) {
            delay(intervalSec * 1000L)
            try {
                val response = apiClient.deviceAuthApi.pollAccessToken(
                    clientId = clientId,
                    deviceCode = deviceCode
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (!body.accessToken.isNullOrBlank()) {
                        secureStorage.saveToken(body.accessToken, "DEVICE")
                        // Fetch authenticated user info
                        val userResponse = apiClient.gitHubApi.getAuthenticatedUser()
                        if (userResponse.isSuccessful && userResponse.body() != null) {
                            val user = userResponse.body()!!
                            secureStorage.saveAuthUser(user.login, user.avatarUrl)
                            emit(DeviceFlowState.Success(user))
                        } else {
                            emit(DeviceFlowState.Error("Token received but failed to fetch user profile"))
                        }
                        break
                    } else if (body.error != null) {
                        when (body.error) {
                            "authorization_pending" -> emit(DeviceFlowState.AuthorizationPending)
                            "slow_down" -> {
                                intervalSec += 5
                                emit(DeviceFlowState.SlowDown)
                            }
                            "expired_token" -> {
                                emit(DeviceFlowState.Expired)
                                break
                            }
                            "access_denied" -> {
                                emit(DeviceFlowState.AccessDenied)
                                break
                            }
                            else -> {
                                emit(DeviceFlowState.Error(body.errorDescription ?: body.error))
                                break
                            }
                        }
                    }
                } else {
                    emit(DeviceFlowState.Error("HTTP ${response.code()}: ${response.errorBody()?.string()}"))
                    break
                }
            } catch (e: Exception) {
                emit(DeviceFlowState.Error("Network error during authorization check: ${e.localizedMessage}"))
                break
            }
        }
    }

    fun logout() {
        secureStorage.clearAuth()
        secureStorage.clearSelectedRepo()
    }
}
