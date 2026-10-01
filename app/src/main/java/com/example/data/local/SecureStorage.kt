package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "github_file_manager_secure_prefs"
        private const val KEY_ALIAS = "github_file_manager_token_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        private const val PREF_ENCRYPTED_TOKEN = "encrypted_token"
        private const val PREF_TOKEN_IV = "token_iv"
        private const val PREF_AUTH_USERNAME = "auth_username"
        private const val PREF_AUTH_AVATAR = "auth_avatar"
        private const val PREF_AUTH_METHOD = "auth_method" // "PAT" or "DEVICE"

        private const val PREF_SELECTED_OWNER = "selected_repo_owner"
        private const val PREF_SELECTED_REPO = "selected_repo_name"
        private const val PREF_SELECTED_BRANCH = "selected_repo_branch"
        private const val PREF_DEFAULT_BRANCH = "selected_repo_default_branch"
        private const val PREF_REPO_PRIVATE = "selected_repo_is_private"
    }

    @Volatile
    private var fallbackKey: SecretKey? = null

    private fun getFallbackSecretKey(): SecretKey {
        fallbackKey?.let { return it }
        val rawKeyBase64 = prefs.getString("fallback_raw_key", null)
        val key = if (rawKeyBase64 != null) {
            val bytes = Base64.decode(rawKeyBase64, Base64.NO_WRAP)
            javax.crypto.spec.SecretKeySpec(bytes, "AES")
        } else {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(256)
            val generated = kg.generateKey()
            prefs.edit().putString("fallback_raw_key", Base64.encodeToString(generated.encoded, Base64.NO_WRAP)).apply()
            generated
        }
        fallbackKey = key
        return key
    }

    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                entry.secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()

                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (_: Exception) {
            // Software AES fallback when AndroidKeyStore provider is unavailable (e.g. JVM/Robolectric test environment)
            getFallbackSecretKey()
        }
    }

    @Synchronized
    fun saveToken(token: String, method: String = "PAT"): Result<Unit> {
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

            val committed = prefs.edit()
                .putString(PREF_ENCRYPTED_TOKEN, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString(PREF_TOKEN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREF_AUTH_METHOD, method)
                .commit()

            if (committed) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("SharedPreferences failed to commit token storage"))
            }
        } catch (e: Exception) {
            // Never log token contents or secrets
            android.util.Log.e("SecureStorage", "Failed to encrypt token safely", e)
            Result.failure(e)
        }
    }

    @Synchronized
    fun getToken(): String? {
        val encryptedBase64 = prefs.getString(PREF_ENCRYPTED_TOKEN, null) ?: return null
        val ivBase64 = prefs.getString(PREF_TOKEN_IV, null) ?: return null

        return try {
            val secretKey = getOrCreateSecretKey()
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "Failed to decrypt token", e)
            null
        }
    }

    fun hasToken(): Boolean = getToken() != null

    fun getAuthMethod(): String = prefs.getString(PREF_AUTH_METHOD, "PAT") ?: "PAT"

    fun saveAuthUser(username: String, avatarUrl: String?): Result<Unit> {
        return try {
            val committed = prefs.edit()
                .putString(PREF_AUTH_USERNAME, username)
                .putString(PREF_AUTH_AVATAR, avatarUrl)
                .commit()
            if (committed) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("SharedPreferences failed to commit auth user storage"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "Failed to save auth user profile", e)
            Result.failure(e)
        }
    }

    fun getAuthUser(): Pair<String, String?>? {
        val username = prefs.getString(PREF_AUTH_USERNAME, null) ?: return null
        val avatar = prefs.getString(PREF_AUTH_AVATAR, null)
        return Pair(username, avatar)
    }

    fun clearAuth() {
        prefs.edit()
            .remove(PREF_ENCRYPTED_TOKEN)
            .remove(PREF_TOKEN_IV)
            .remove(PREF_AUTH_USERNAME)
            .remove(PREF_AUTH_AVATAR)
            .remove(PREF_AUTH_METHOD)
            .apply()
    }

    fun saveSelectedRepo(
        owner: String,
        repo: String,
        branch: String,
        defaultBranch: String = branch,
        isPrivate: Boolean = false
    ) {
        prefs.edit()
            .putString(PREF_SELECTED_OWNER, owner)
            .putString(PREF_SELECTED_REPO, repo)
            .putString(PREF_SELECTED_BRANCH, branch)
            .putString(PREF_DEFAULT_BRANCH, defaultBranch)
            .putBoolean(PREF_REPO_PRIVATE, isPrivate)
            .apply()
    }

    fun saveSelectedRepo(info: SelectedRepoInfo) {
        saveSelectedRepo(info.owner, info.name, info.branch, info.defaultBranch, info.isPrivate)
    }

    fun getSelectedRepo(): SelectedRepoInfo? {
        val owner = prefs.getString(PREF_SELECTED_OWNER, null) ?: return null
        val repo = prefs.getString(PREF_SELECTED_REPO, null) ?: return null
        val branch = prefs.getString(PREF_SELECTED_BRANCH, "main") ?: "main"
        val defaultBranch = prefs.getString(PREF_DEFAULT_BRANCH, branch) ?: branch
        val isPrivate = prefs.getBoolean(PREF_REPO_PRIVATE, false)
        return SelectedRepoInfo(owner, repo, branch, defaultBranch, isPrivate)
    }

    fun updateSelectedBranch(branch: String) {
        prefs.edit().putString(PREF_SELECTED_BRANCH, branch).apply()
    }

    fun clearSelectedRepo() {
        prefs.edit()
            .remove(PREF_SELECTED_OWNER)
            .remove(PREF_SELECTED_REPO)
            .remove(PREF_SELECTED_BRANCH)
            .remove(PREF_DEFAULT_BRANCH)
            .remove(PREF_REPO_PRIVATE)
            .apply()
    }
}

data class SelectedRepoInfo(
    val owner: String,
    val name: String,
    val branch: String,
    val defaultBranch: String,
    val isPrivate: Boolean
) {
    val fullName: String get() = "$owner/$name"
}
