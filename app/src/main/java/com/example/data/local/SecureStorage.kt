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

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

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
        return keyGenerator.generateKey()
    }

    @Synchronized
    fun saveToken(token: String, method: String = "PAT") {
        try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

            prefs.edit()
                .putString(PREF_ENCRYPTED_TOKEN, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString(PREF_TOKEN_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREF_AUTH_METHOD, method)
                .apply()
        } catch (e: Exception) {
            // Log without exposing token
            android.util.Log.e("SecureStorage", "Failed to encrypt token safely", e)
        }
    }

    @Synchronized
    fun getToken(): String? {
        val encryptedBase64 = prefs.getString(PREF_ENCRYPTED_TOKEN, null) ?: return null
        val ivBase64 = prefs.getString(PREF_TOKEN_IV, null) ?: return null

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) return null
            val secretKey = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

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

    fun saveAuthUser(username: String, avatarUrl: String?) {
        prefs.edit()
            .putString(PREF_AUTH_USERNAME, username)
            .putString(PREF_AUTH_AVATAR, avatarUrl)
            .apply()
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
