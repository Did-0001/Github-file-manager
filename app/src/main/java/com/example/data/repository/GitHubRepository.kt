package com.example.data.repository

import com.example.data.remote.ApiClient
import com.example.data.remote.dto.*
import com.squareup.moshi.Types
import okhttp3.ResponseBody
import retrofit2.Response

class GitHubRepository(private val apiClient: ApiClient) {

    private val contentListAdapter by lazy {
        val type = Types.newParameterizedType(List::class.java, GitHubContentDto::class.java)
        apiClient.moshi.adapter<List<GitHubContentDto>>(type)
    }

    private val singleContentAdapter by lazy {
        apiClient.moshi.adapter(GitHubContentDto::class.java)
    }

    suspend fun getAuthenticatedUser(): Result<GitHubUserDto> {
        return try {
            val response = apiClient.gitHubApi.getAuthenticatedUser()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserRepos(page: Int = 1): Result<List<GitHubRepoDto>> {
        return try {
            val response = apiClient.gitHubApi.listUserRepos(page = page, perPage = 100)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllUserRepos(): Result<List<GitHubRepoDto>> {
        val allRepos = mutableListOf<GitHubRepoDto>()
        var page = 1
        while (true) {
            val pageRes = getUserRepos(page)
            if (pageRes.isFailure) {
                if (allRepos.isNotEmpty()) return Result.success(allRepos)
                return pageRes
            }
            val list = pageRes.getOrThrow()
            allRepos.addAll(list)
            if (list.size < 100) break
            page++
        }
        return Result.success(allRepos.distinctBy { it.id })
    }

    suspend fun createRepo(name: String, description: String?, isPrivate: Boolean): Result<GitHubRepoDto> {
        return try {
            val req = CreateRepoRequest(name = name, description = description, isPrivate = isPrivate, autoInit = true)
            val response = apiClient.gitHubApi.createRepo(req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBranches(owner: String, repo: String, page: Int = 1): Result<List<GitHubBranchDto>> {
        return try {
            val response = apiClient.gitHubApi.listBranches(owner, repo, perPage = 100)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllBranches(owner: String, repo: String): Result<List<GitHubBranchDto>> {
        val allBranches = mutableListOf<GitHubBranchDto>()
        var page = 1
        while (true) {
            val pageRes = getBranches(owner, repo, page)
            if (pageRes.isFailure) {
                if (allBranches.isNotEmpty()) return Result.success(allBranches)
                return pageRes
            }
            val list = pageRes.getOrThrow()
            allBranches.addAll(list)
            if (list.size < 100) break
            page++
        }
        return Result.success(allBranches.distinctBy { it.name })
    }

    suspend fun getDirectoryContents(
        owner: String,
        repo: String,
        path: String,
        branch: String?
    ): Result<List<GitHubContentDto>> {
        return try {
            val cleanPath = path.trimStart('/').trimEnd('/')
            if (cleanPath.isEmpty()) {
                val response = apiClient.gitHubApi.getRootContents(owner, repo, branch)
                if (response.isSuccessful && response.body() != null) {
                    val sorted = response.body()!!.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    return Result.success(sorted)
                } else {
                    return Result.failure(Exception(parseError(response)))
                }
            }

            val response = apiClient.gitHubApi.getContents(owner, repo, cleanPath, branch)
            if (response.isSuccessful && response.body() != null) {
                val bodyString = response.body()!!.string().trim()
                if (bodyString.startsWith("[")) {
                    val list = contentListAdapter.fromJson(bodyString) ?: emptyList()
                    val sorted = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    Result.success(sorted)
                } else if (bodyString.startsWith("{")) {
                    val single = singleContentAdapter.fromJson(bodyString)
                    if (single != null) {
                        Result.success(listOf(single))
                    } else {
                        Result.success(emptyList())
                    }
                } else {
                    Result.success(emptyList())
                }
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFileDetails(
        owner: String,
        repo: String,
        path: String,
        branch: String?
    ): Result<GitHubContentDto> {
        return try {
            val cleanPath = path.trimStart('/')
            val response = apiClient.gitHubApi.getContents(owner, repo, cleanPath, branch)
            if (response.isSuccessful && response.body() != null) {
                val bodyString = response.body()!!.string().trim()
                val item = singleContentAdapter.fromJson(bodyString)
                if (item != null) {
                    Result.success(item)
                } else {
                    Result.failure(Exception("Failed to parse file response"))
                }
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createOrUpdateFile(
        owner: String,
        repo: String,
        path: String,
        contentBase64: String,
        commitMessage: String,
        branch: String,
        sha: String? = null
    ): Result<Unit> {
        return try {
            val cleanPath = path.trimStart('/')
            val body = CreateOrUpdateFileRequest(
                message = commitMessage,
                content = contentBase64,
                sha = sha,
                branch = branch
            )
            val response = apiClient.gitHubApi.createOrUpdateFile(owner, repo, cleanPath, body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(
        owner: String,
        repo: String,
        path: String,
        commitMessage: String,
        branch: String,
        sha: String
    ): Result<Unit> {
        return try {
            val cleanPath = path.trimStart('/')
            val body = DeleteFileRequest(
                message = commitMessage,
                sha = sha,
                branch = branch
            )
            val response = apiClient.gitHubApi.deleteFile(owner, repo, cleanPath, body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadZipball(owner: String, repo: String, branch: String): Result<ResponseBody> {
        return try {
            val response = apiClient.gitHubApi.downloadZipball(owner, repo, branch)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadRaw(url: String): Result<ResponseBody> {
        return try {
            val response = apiClient.gitHubApi.downloadRawFile(url)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Low-level Git operations for batch atomic commit & wipe
    suspend fun getBranchHeadSha(owner: String, repo: String, branch: String): Result<String> {
        return try {
            val response = apiClient.gitHubApi.getRef(owner, repo, branch)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.obj.sha)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCommitTreeSha(owner: String, repo: String, commitSha: String): Result<String> {
        return try {
            val response = apiClient.gitHubApi.getCommit(owner, repo, commitSha)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.tree.sha)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTree(owner: String, repo: String, treeSha: String, recursive: Boolean = false): Result<GitTreeResponse> {
        return try {
            val response = apiClient.gitHubApi.getTree(owner, repo, treeSha, if (recursive) 1 else null)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFullTree(owner: String, repo: String, rootTreeSha: String): Result<List<GitTreeItemDto>> {
        return try {
            val rootRes = getTree(owner, repo, rootTreeSha, recursive = true)
            if (rootRes.isFailure) return Result.failure(rootRes.exceptionOrNull()!!)
            val rootTree = rootRes.getOrThrow()
            if (!rootTree.truncated) {
                return Result.success(rootTree.tree)
            }

            // Truncated tree recovery: traverse subtrees explicitly
            val allItems = mutableListOf<GitTreeItemDto>()
            allItems.addAll(rootTree.tree)

            val pendingSubtrees = ArrayDeque<Pair<String, String>>()
            for (item in rootTree.tree) {
                if (item.type == "tree") {
                    pendingSubtrees.add(Pair(item.path, item.sha))
                }
            }

            while (pendingSubtrees.isNotEmpty()) {
                val (prefix, sha) = pendingSubtrees.removeFirst()
                val subRes = getTree(owner, repo, sha, recursive = false)
                if (subRes.isSuccess) {
                    for (subItem in subRes.getOrThrow().tree) {
                        val subPath = "$prefix/${subItem.path}"
                        val prefixedItem = subItem.copy(path = subPath)
                        allItems.add(prefixedItem)
                        if (subItem.type == "tree") {
                            pendingSubtrees.add(Pair(subPath, subItem.sha))
                        }
                    }
                }
            }
            Result.success(allItems.distinctBy { it.path })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createBlob(owner: String, repo: String, base64Content: String): Result<String> {
        return try {
            val req = CreateBlobRequest(content = base64Content, encoding = "base64")
            val response = apiClient.gitHubApi.createBlob(owner, repo, req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.sha)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createBlobStream(owner: String, repo: String, requestBody: okhttp3.RequestBody): Result<String> {
        return try {
            val response = apiClient.gitHubApi.createBlobStream(owner, repo, requestBody)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.sha)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFilesBatch(
        owner: String,
        repo: String,
        branch: String,
        pathsToDelete: List<String>,
        commitMessage: String
    ): Result<String> {
        return try {
            val headSha = getBranchHeadSha(owner, repo, branch).getOrThrow()
            val baseTreeSha = getCommitTreeSha(owner, repo, headSha).getOrThrow()

            // In GitHub Git Data API, specifying sha = null deletes the entry from base_tree
            val entries = pathsToDelete.map { path ->
                CreateTreeEntryDto(
                    path = path.trimStart('/'),
                    mode = "100644",
                    type = "blob",
                    sha = null
                )
            }

            val newTreeSha = createTree(owner, repo, baseTreeSha, entries).getOrThrow()
            val newCommitSha = createCommit(owner, repo, commitMessage, newTreeSha, headSha).getOrThrow()
            updateBranchRef(owner, repo, branch, newCommitSha).getOrThrow()
            Result.success(newCommitSha)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createTree(
        owner: String,
        repo: String,
        baseTreeSha: String?,
        entries: List<CreateTreeEntryDto>
    ): Result<String> {
        return try {
            val req = CreateTreeRequest(baseTree = baseTreeSha, tree = entries)
            val response = apiClient.gitHubApi.createTree(owner, repo, req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.sha)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createCommit(
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentCommitSha: String
    ): Result<String> {
        return try {
            val req = CreateCommitRequest(message = message, tree = treeSha, parents = listOf(parentCommitSha))
            val response = apiClient.gitHubApi.createCommit(owner, repo, req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.sha)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateBranchRef(
        owner: String,
        repo: String,
        branch: String,
        commitSha: String,
        force: Boolean = false
    ): Result<Unit> {
        return try {
            val req = UpdateRefRequest(sha = commitSha, force = force)
            val response = apiClient.gitHubApi.updateRef(owner, repo, branch, req)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseError(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseError(response: Response<*>): String {
        val code = response.code()
        val errorText = try {
            response.errorBody()?.string() ?: ""
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            401 -> "Unauthorized: Check your GitHub Personal Access Token or permissions."
            403 -> "Forbidden: GitHub rate limit reached or insufficient repository permissions. ($errorText)"
            404 -> "Not Found: Repository, branch, or file does not exist or you lack access."
            409 -> "Conflict: Branch has moved remotely or cannot be updated."
            422 -> "Unprocessable Entity: Invalid path or Git validation rejected. ($errorText)"
            else -> "GitHub API error (HTTP $code): $errorText"
        }
    }
}
