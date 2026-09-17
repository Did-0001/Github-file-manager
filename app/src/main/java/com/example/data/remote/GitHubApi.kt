package com.example.data.remote

import com.example.data.remote.dto.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface GitHubApi {

    // User
    @GET("user")
    suspend fun getAuthenticatedUser(): Response<GitHubUserDto>

    // Repositories
    @GET("user/repos")
    suspend fun listUserRepos(
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1,
        @Query("affiliation") affiliation: String = "owner,collaborator,organization_member"
    ): Response<List<GitHubRepoDto>>

    @POST("user/repos")
    suspend fun createRepo(
        @Body request: CreateRepoRequest
    ): Response<GitHubRepoDto>

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubRepoDto>

    // Branches
    @GET("repos/{owner}/{repo}/branches")
    suspend fun listBranches(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100
    ): Response<List<GitHubBranchDto>>

    @GET("repos/{owner}/{repo}/branches/{branch}")
    suspend fun getBranch(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String
    ): Response<GitHubBranchDto>

    // Directory Contents & Files (Real on-demand tree browsing)
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Query("ref") ref: String? = null
    ): Response<ResponseBody> // Parsed as list or single object depending on path

    @GET("repos/{owner}/{repo}/contents")
    suspend fun getRootContents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("ref") ref: String? = null
    ): Response<List<GitHubContentDto>>

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun createOrUpdateFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body body: CreateOrUpdateFileRequest
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "repos/{owner}/{repo}/contents/{path}", hasBody = true)
    suspend fun deleteFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body body: DeleteFileRequest
    ): Response<ResponseBody>

    // Raw download / zipball
    @Streaming
    @GET("repos/{owner}/{repo}/zipball/{ref}")
    suspend fun downloadZipball(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("ref") ref: String
    ): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun downloadRawFile(
        @Url url: String
    ): Response<ResponseBody>

    // Git Data API (Low-level objects for atomic commits and wipe)
    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String
    ): Response<GitRefResponse>

    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body body: UpdateRefRequest
    ): Response<GitRefResponse>

    @GET("repos/{owner}/{repo}/git/commits/{commit_sha}")
    suspend fun getCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("commit_sha") commitSha: String
    ): Response<GitCommitDto>

    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createCommit(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateCommitRequest
    ): Response<CreateCommitResponse>

    @GET("repos/{owner}/{repo}/git/trees/{tree_sha}")
    suspend fun getTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("tree_sha") treeSha: String,
        @Query("recursive") recursive: Int? = null
    ): Response<GitTreeResponse>

    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateTreeRequest
    ): Response<CreateTreeResponse>

    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlob(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateBlobRequest
    ): Response<CreateBlobResponse>

    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlobStream(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: okhttp3.RequestBody
    ): Response<CreateBlobResponse>
}
