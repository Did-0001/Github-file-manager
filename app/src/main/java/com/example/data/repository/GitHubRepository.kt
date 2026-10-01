package com.example.data.repository

import com.example.data.cache.GitHubCacheManager
import com.example.data.remote.ApiClient
import com.example.data.remote.ApiErrorType
import com.example.data.remote.GitHubApiException
import com.example.data.remote.dto.*
import com.squareup.moshi.Types
import okhttp3.ResponseBody
import retrofit2.Response

class GitHubRepository(
    private val apiClient: ApiClient,
    val cacheManager: GitHubCacheManager? = null
) {

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
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getUserRepos(page: Int = 1): Result<List<GitHubRepoDto>> {
        return try {
            val response = apiClient.gitHubApi.listUserRepos(page = page, perPage = 100)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getAllUserRepos(bypassCache: Boolean = false): Result<List<GitHubRepoDto>> {
        if (!bypassCache) {
            val cached = cacheManager?.getRepos()
            if (cached != null) {
                return Result.success(cached)
            }
        }
        val allRepos = mutableListOf<GitHubRepoDto>()
        var page = 1
        while (true) {
            val pageRes = getUserRepos(page)
            if (pageRes.isFailure) {
                return pageRes
            }
            val list = pageRes.getOrThrow()
            allRepos.addAll(list)
            if (list.size < 100) break
            page++
        }
        val distinct = allRepos.distinctBy { it.id }
        cacheManager?.putRepos(distinct)
        return Result.success(distinct)
    }

    suspend fun createRepo(name: String, description: String?, isPrivate: Boolean): Result<GitHubRepoDto> {
        return try {
            val req = CreateRepoRequest(name = name, description = description, isPrivate = isPrivate, autoInit = true)
            val response = apiClient.gitHubApi.createRepo(req)
            if (response.isSuccessful && response.body() != null) {
                val created = response.body()!!
                cacheManager?.clearAllCache()
                Result.success(created)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getBranches(owner: String, repo: String, page: Int = 1): Result<List<GitHubBranchDto>> {
        return try {
            val response = apiClient.gitHubApi.listBranches(owner, repo, perPage = 100, page = page)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getAllBranches(owner: String, repo: String, bypassCache: Boolean = false): Result<List<GitHubBranchDto>> {
        if (!bypassCache) {
            val cached = cacheManager?.getBranches(owner, repo)
            if (cached != null) {
                return Result.success(cached)
            }
        }
        val allBranches = mutableListOf<GitHubBranchDto>()
        var page = 1
        while (true) {
            val pageRes = getBranches(owner, repo, page)
            if (pageRes.isFailure) {
                return pageRes
            }
            val list = pageRes.getOrThrow()
            allBranches.addAll(list)
            if (list.size < 100) break
            page++
        }
        val distinct = allBranches.distinctBy { it.name }
        cacheManager?.putBranches(owner, repo, distinct)
        return Result.success(distinct)
    }

    suspend fun getBranchDetails(
        owner: String,
        repo: String,
        branch: String,
        bypassCache: Boolean = false
    ): Result<GitHubBranchDto> {
        if (!bypassCache) {
            val cached = cacheManager?.getBranchDetails(owner, repo, branch)
            if (cached != null) {
                return Result.success(cached)
            }
        }
        return try {
            val response = apiClient.gitHubApi.getBranch(owner, repo, branch)
            if (response.isSuccessful && response.body() != null) {
                val details = response.body()!!
                cacheManager?.putBranchDetails(owner, repo, branch, details)
                Result.success(details)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun createBranch(
        owner: String,
        repo: String,
        newBranchName: String,
        sourceBranchSha: String
    ): Result<GitRefResponse> {
        return try {
            val ref = "refs/heads/$newBranchName"
            val response = apiClient.gitHubApi.createRef(owner, repo, CreateRefRequest(ref = ref, sha = sourceBranchSha))
            if (response.isSuccessful && response.body() != null) {
                cacheManager?.clearRepoCache(owner, repo)
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun renameBranch(
        owner: String,
        repo: String,
        oldBranchName: String,
        newBranchName: String
    ): Result<GitHubBranchDto> {
        return try {
            val response = apiClient.gitHubApi.renameBranch(owner, repo, oldBranchName, RenameBranchRequest(newName = newBranchName))
            if (response.isSuccessful && response.body() != null) {
                cacheManager?.clearRepoCache(owner, repo)
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun deleteBranch(
        owner: String,
        repo: String,
        branchName: String
    ): Result<Unit> {
        return try {
            val response = apiClient.gitHubApi.deleteRef(owner, repo, branchName)
            if (response.isSuccessful) {
                cacheManager?.clearRepoCache(owner, repo)
                Result.success(Unit)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun setDefaultBranch(
        owner: String,
        repo: String,
        newDefaultBranch: String
    ): Result<GitHubRepoDto> {
        return try {
            val response = apiClient.gitHubApi.updateRepo(owner, repo, UpdateRepoRequest(defaultBranch = newDefaultBranch))
            if (response.isSuccessful && response.body() != null) {
                cacheManager?.clearRepoCache(owner, repo)
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    companion object {
        fun validateBranchName(name: String, existingBranches: List<String>): String? {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "Branch name cannot be empty"
            if (existingBranches.any { it.equals(trimmed, ignoreCase = true) }) {
                return "A branch named '$trimmed' already exists"
            }
            if (trimmed.startsWith("/") || trimmed.startsWith("-") || trimmed.startsWith(".")) {
                return "Branch name cannot start with '/', '-', or '.'"
            }
            if (trimmed.endsWith("/") || trimmed.endsWith(".") || trimmed.endsWith(".lock")) {
                return "Branch name cannot end with '/', '.', or '.lock'"
            }
            if (trimmed.contains("..") || trimmed.contains("//") || trimmed.contains("@{")) {
                return "Branch name contains invalid character sequences ('..', '//', '@{')"
            }
            val invalidChars = listOf(' ', '~', '^', ':', '?', '*', '[', '\\')
            if (trimmed.any { it in invalidChars }) {
                return "Branch name contains invalid characters (spaces, ~, ^, :, ?, *, [, \\)"
            }
            return null
        }
    }

    suspend fun getDirectoryContents(
        owner: String,
        repo: String,
        path: String,
        branch: String?,
        bypassCache: Boolean = false
    ): Result<List<GitHubContentDto>> {
        val targetBranch = branch ?: "HEAD"
        if (!bypassCache) {
            val cached = cacheManager?.getDirectoryContents(owner, repo, targetBranch, path)
            if (cached != null) {
                return Result.success(cached)
            }
        }
        return try {
            val cleanPath = path.trimStart('/').trimEnd('/')
            if (cleanPath.isEmpty()) {
                val response = apiClient.gitHubApi.getRootContents(owner, repo, branch)
                if (response.isSuccessful && response.body() != null) {
                    val list = response.body()!!
                    // If directory has 1000 items, Contents API is truncated -> fetch via Git Tree API
                    if (list.size >= 1000) {
                        val treeRes = getDirectoryContentsViaTree(owner, repo, cleanPath, branch)
                        if (treeRes.isSuccess) {
                            val items = treeRes.getOrThrow()
                            cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, items)
                            return treeRes
                        }
                    }
                    val sorted = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, sorted)
                    return Result.success(sorted)
                } else if (response.code() in listOf(403, 409, 422)) {
                    val treeRes = getDirectoryContentsViaTree(owner, repo, cleanPath, branch)
                    if (treeRes.isSuccess) {
                        val items = treeRes.getOrThrow()
                        cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, items)
                        return treeRes
                    }
                    return Result.failure(GitHubApiException.fromResponse(response))
                } else {
                    return Result.failure(GitHubApiException.fromResponse(response))
                }
            }

            val response = apiClient.gitHubApi.getContents(owner, repo, cleanPath, branch)
            if (response.isSuccessful && response.body() != null) {
                val bodyString = response.body()!!.string().trim()
                if (bodyString.startsWith("[")) {
                    val list = contentListAdapter.fromJson(bodyString) ?: emptyList()
                    // If directory has 1000 items, Contents API is truncated -> fetch via Git Tree API
                    if (list.size >= 1000) {
                        val treeRes = getDirectoryContentsViaTree(owner, repo, cleanPath, branch)
                        if (treeRes.isSuccess) {
                            val items = treeRes.getOrThrow()
                            cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, items)
                            return treeRes
                        }
                    }
                    val sorted = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, sorted)
                    Result.success(sorted)
                } else if (bodyString.startsWith("{")) {
                    val single = singleContentAdapter.fromJson(bodyString)
                    val result = if (single != null) listOf(single) else emptyList()
                    cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, result)
                    Result.success(result)
                } else {
                    Result.success(emptyList())
                }
            } else if (response.code() in listOf(403, 409, 422)) {
                val treeRes = getDirectoryContentsViaTree(owner, repo, cleanPath, branch)
                if (treeRes.isSuccess) {
                    val items = treeRes.getOrThrow()
                    cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, items)
                    treeRes
                } else {
                    Result.failure(GitHubApiException.fromResponse(response))
                }
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            val treeRes = getDirectoryContentsViaTree(owner, repo, path, branch)
            if (treeRes.isSuccess) {
                val items = treeRes.getOrThrow()
                cacheManager?.putDirectoryContents(owner, repo, targetBranch, path, items)
                treeRes
            } else {
                Result.failure(GitHubApiException.fromThrowable(e))
            }
        }
    }

    suspend fun getDirectoryContentsViaTree(
        owner: String,
        repo: String,
        path: String,
        branch: String?
    ): Result<List<GitHubContentDto>> {
        return try {
            val cleanPath = path.trimStart('/').trimEnd('/')
            val targetBranch = branch ?: "HEAD"
            val headRes = getBranchHeadSha(owner, repo, targetBranch)
            if (headRes.isFailure) return Result.failure(headRes.exceptionOrNull() ?: Exception("Failed to resolve branch ref"))
            val commitSha = headRes.getOrThrow()

            val treeShaRes = getCommitTreeSha(owner, repo, commitSha)
            if (treeShaRes.isFailure) return Result.failure(treeShaRes.exceptionOrNull() ?: Exception("Failed to resolve commit tree"))
            var currentTreeSha = treeShaRes.getOrThrow()

            if (cleanPath.isNotEmpty()) {
                val segments = cleanPath.split('/')
                for (segment in segments) {
                    val treeRes = getTree(owner, repo, currentTreeSha, recursive = false)
                    if (treeRes.isFailure) return Result.failure(treeRes.exceptionOrNull() ?: Exception("Failed to fetch subtree"))
                    val treeItems = treeRes.getOrThrow().tree
                    val matchingSubtree = treeItems.firstOrNull { it.path == segment && it.type == "tree" }
                        ?: return Result.success(emptyList())
                    currentTreeSha = matchingSubtree.sha
                }
            }

            val treeRes = getTree(owner, repo, currentTreeSha, recursive = false)
            if (treeRes.isFailure) return Result.failure(treeRes.exceptionOrNull() ?: Exception("Failed to fetch tree"))
            val treeResponse = treeRes.getOrThrow()
            val rawItems = if (!treeResponse.truncated) {
                treeResponse.tree
            } else {
                val fullRes = getFullTree(owner, repo, currentTreeSha)
                if (fullRes.isFailure) return Result.failure(fullRes.exceptionOrNull() ?: Exception("Failed to fetch full tree"))
                fullRes.getOrThrow()
            }

            val dtoList = rawItems.map { item ->
                val itemPath = if (cleanPath.isEmpty()) item.path else "$cleanPath/${item.path}"
                val isDir = item.type == "tree"
                GitHubContentDto(
                    name = item.path,
                    path = itemPath,
                    sha = item.sha,
                    size = item.size ?: 0,
                    type = if (isDir) "dir" else "file",
                    downloadUrl = null,
                    htmlUrl = ""
                )
            }
            val sorted = dtoList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            Result.success(sorted)
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
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
                    Result.failure(GitHubApiException(ApiErrorType.SERVER_ERROR, message = "Failed to parse file response"))
                }
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String,
        branch: String?,
        bypassCache: Boolean = false
    ): Result<String> {
        val targetBranch = branch ?: "HEAD"
        if (!bypassCache) {
            val cached = cacheManager?.getFileContent(owner, repo, targetBranch, path)
            if (cached != null) {
                return Result.success(cached)
            }
        }
        val detailsRes = getFileDetails(owner, repo, path, targetBranch)
        if (detailsRes.isFailure) return Result.failure(detailsRes.exceptionOrNull() ?: Exception("Failed to fetch file details"))
        val details = detailsRes.getOrThrow()
        val text = details.content?.let { raw ->
            try {
                if (details.encoding == "base64") {
                    val clean = raw.replace("\n", "").replace("\r", "")
                    String(android.util.Base64.decode(clean, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } else raw
            } catch (_: Exception) { raw }
        } ?: ""
        cacheManager?.putFileContent(owner, repo, targetBranch, path, text)
        return Result.success(text)
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
                cacheManager?.clearRepoCache(owner, repo)
                Result.success(Unit)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
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
                cacheManager?.clearRepoCache(owner, repo)
                Result.success(Unit)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun downloadZipball(owner: String, repo: String, branch: String): Result<ResponseBody> {
        return try {
            val response = apiClient.gitHubApi.downloadZipball(owner, repo, branch)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun downloadRaw(url: String): Result<ResponseBody> {
        return try {
            val response = apiClient.gitHubApi.downloadRawFile(url)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    // Low-level Git operations for batch atomic commit & wipe
    suspend fun getBranchHeadSha(owner: String, repo: String, branch: String): Result<String> {
        return try {
            val response = apiClient.gitHubApi.getRef(owner, repo, branch)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.obj.sha)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getCommitTreeSha(owner: String, repo: String, commitSha: String): Result<String> {
        return try {
            val response = apiClient.gitHubApi.getCommit(owner, repo, commitSha)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.tree.sha)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getTree(owner: String, repo: String, treeSha: String, recursive: Boolean = false): Result<GitTreeResponse> {
        return try {
            val response = apiClient.gitHubApi.getTree(owner, repo, treeSha, if (recursive) 1 else null)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun getFullTree(owner: String, repo: String, rootTreeSha: String): Result<List<GitTreeItemDto>> {
        return try {
            val rootRes = getTree(owner, repo, rootTreeSha, recursive = true)
            if (rootRes.isFailure) return Result.failure(rootRes.exceptionOrNull() ?: GitHubApiException(ApiErrorType.SERVER_ERROR, message = "Failed to fetch root tree"))
            val rootTree = rootRes.getOrThrow()
            if (!rootTree.truncated) {
                return Result.success(rootTree.tree)
            }

            // Truncated tree recovery: traverse subtrees explicitly
            val allItems = mutableListOf<GitTreeItemDto>()
            allItems.addAll(rootTree.tree)

            val visitedTreePaths = mutableSetOf<String>()
            val pendingSubtrees = ArrayDeque<Pair<String, String>>()
            for (item in rootTree.tree) {
                if (item.type == "tree") {
                    if (visitedTreePaths.add(item.path)) {
                        pendingSubtrees.add(Pair(item.path, item.sha))
                    }
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
                            if (visitedTreePaths.add(subPath)) {
                                pendingSubtrees.add(Pair(subPath, subItem.sha))
                            }
                        }
                    }
                } else {
                    // Truncated tree recovery must fail the entire tree enumeration on any subtree failure
                    val error = subRes.exceptionOrNull()
                    val apiException = if (error is GitHubApiException) {
                        error
                    } else {
                        GitHubApiException(
                            errorType = ApiErrorType.SERVER_ERROR,
                            message = "Truncated tree enumeration failed for subtree '$prefix' (sha: $sha): ${error?.message}",
                            cause = error
                        )
                    }
                    return Result.failure(apiException)
                }
            }
            Result.success(allItems.distinctBy { it.path })
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun createBlob(owner: String, repo: String, base64Content: String): Result<String> {
        return try {
            val req = CreateBlobRequest(content = base64Content, encoding = "base64")
            val response = apiClient.gitHubApi.createBlob(owner, repo, req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.sha)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun createBlobStream(owner: String, repo: String, requestBody: okhttp3.RequestBody): Result<String> {
        return try {
            val response = apiClient.gitHubApi.createBlobStream(owner, repo, requestBody)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.sha)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
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
            Result.failure(GitHubApiException.fromThrowable(e))
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
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }

    suspend fun createCommit(
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentCommitSha: String? = null,
        parents: List<String>? = null
    ): Result<String> {
        return try {
            val parentList = when {
                parents != null -> parents
                parentCommitSha != null -> listOf(parentCommitSha)
                else -> emptyList()
            }
            val req = CreateCommitRequest(message = message, tree = treeSha, parents = parentList)
            val response = apiClient.gitHubApi.createCommit(owner, repo, req)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.sha)
            } else {
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
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
                Result.failure(GitHubApiException.fromResponse(response))
            }
        } catch (e: Exception) {
            Result.failure(GitHubApiException.fromThrowable(e))
        }
    }
}

