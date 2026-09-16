package com.example.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubUserDto(
    @Json(name = "login") val login: String,
    @Json(name = "id") val id: Long,
    @Json(name = "avatar_url") val avatarUrl: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "email") val email: String?
)

@JsonClass(generateAdapter = true)
data class RepoOwnerDto(
    @Json(name = "login") val login: String,
    @Json(name = "avatar_url") val avatarUrl: String?
)

@JsonClass(generateAdapter = true)
data class GitHubRepoDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "full_name") val fullName: String,
    @Json(name = "private") val isPrivate: Boolean,
    @Json(name = "owner") val owner: RepoOwnerDto,
    @Json(name = "description") val description: String?,
    @Json(name = "default_branch") val defaultBranch: String = "main",
    @Json(name = "updated_at") val updatedAt: String?,
    @Json(name = "size") val size: Long = 0L
)

@JsonClass(generateAdapter = true)
data class BranchCommitDto(
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class GitHubBranchDto(
    @Json(name = "name") val name: String,
    @Json(name = "commit") val commit: BranchCommitDto,
    @Json(name = "protected") val isProtected: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GitHubContentDto(
    @Json(name = "name") val name: String,
    @Json(name = "path") val path: String,
    @Json(name = "sha") val sha: String,
    @Json(name = "size") val size: Long = 0L,
    @Json(name = "type") val type: String, // "file", "dir", "submodule"
    @Json(name = "download_url") val downloadUrl: String?,
    @Json(name = "html_url") val htmlUrl: String? = null,
    @Json(name = "content") val content: String? = null,
    @Json(name = "encoding") val encoding: String? = null
) {
    val isDirectory: Boolean get() = type == "dir"
    val isFile: Boolean get() = type == "file"
}

@JsonClass(generateAdapter = true)
data class GitObjectDto(
    @Json(name = "sha") val sha: String,
    @Json(name = "type") val type: String? = null
)

@JsonClass(generateAdapter = true)
data class GitRefResponse(
    @Json(name = "ref") val ref: String,
    @Json(name = "object") val obj: GitObjectDto
)

@JsonClass(generateAdapter = true)
data class GitCommitDto(
    @Json(name = "sha") val sha: String,
    @Json(name = "tree") val tree: GitObjectDto
)

@JsonClass(generateAdapter = true)
data class GitTreeItemDto(
    @Json(name = "path") val path: String,
    @Json(name = "mode") val mode: String,
    @Json(name = "type") val type: String, // "blob" or "tree"
    @Json(name = "sha") val sha: String,
    @Json(name = "size") val size: Long = 0L
)

@JsonClass(generateAdapter = true)
data class GitTreeResponse(
    @Json(name = "sha") val sha: String,
    @Json(name = "tree") val tree: List<GitTreeItemDto> = emptyList(),
    @Json(name = "truncated") val truncated: Boolean = false
)

@JsonClass(generateAdapter = true)
data class CreateTreeEntryDto(
    @Json(name = "path") val path: String,
    @Json(name = "mode") val mode: String = "100644",
    @Json(name = "type") val type: String = "blob",
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class CreateTreeRequest(
    @Json(name = "base_tree") val baseTree: String? = null,
    @Json(name = "tree") val tree: List<CreateTreeEntryDto>
)

@JsonClass(generateAdapter = true)
data class CreateTreeResponse(
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class CreateBlobRequest(
    @Json(name = "content") val content: String,
    @Json(name = "encoding") val encoding: String = "base64"
)

@JsonClass(generateAdapter = true)
data class CreateBlobResponse(
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class CreateCommitRequest(
    @Json(name = "message") val message: String,
    @Json(name = "tree") val tree: String,
    @Json(name = "parents") val parents: List<String>
)

@JsonClass(generateAdapter = true)
data class CreateCommitResponse(
    @Json(name = "sha") val sha: String
)

@JsonClass(generateAdapter = true)
data class UpdateRefRequest(
    @Json(name = "sha") val sha: String,
    @Json(name = "force") val force: Boolean = false
)

@JsonClass(generateAdapter = true)
data class CreateRepoRequest(
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "private") val isPrivate: Boolean = false,
    @Json(name = "auto_init") val autoInit: Boolean = true
)

@JsonClass(generateAdapter = true)
data class CreateOrUpdateFileRequest(
    @Json(name = "message") val message: String,
    @Json(name = "content") val content: String, // base64
    @Json(name = "sha") val sha: String? = null,
    @Json(name = "branch") val branch: String
)

@JsonClass(generateAdapter = true)
data class DeleteFileRequest(
    @Json(name = "message") val message: String,
    @Json(name = "sha") val sha: String,
    @Json(name = "branch") val branch: String
)

@JsonClass(generateAdapter = true)
data class DeviceCodeRequest(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "scope") val scope: String = "repo,read:user"
)

@JsonClass(generateAdapter = true)
data class DeviceCodeResponse(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_uri") val verificationUri: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "interval") val interval: Int = 5
)

@JsonClass(generateAdapter = true)
data class DeviceTokenRequest(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "grant_type") val grantType: String = "urn:ietf:params:oauth:grant-type:device_code"
)

@JsonClass(generateAdapter = true)
data class DeviceTokenResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)
