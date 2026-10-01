package com.example.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.example.data.local.dao.RepoDao
import com.example.data.local.entity.CachedRepoEntity
import com.example.data.remote.dto.GitHubBranchDto
import com.example.data.remote.dto.GitHubContentDto
import com.example.data.remote.dto.GitHubRepoDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class CacheExpirationPolicy(val durationMillis: Long, val displayName: String) {
    MINUTES_5(5 * 60 * 1000L, "5 Minutes"),
    MINUTES_15(15 * 60 * 1000L, "15 Minutes (Default)"),
    HOURS_1(60 * 60 * 1000L, "1 Hour"),
    MANUAL(Long.MAX_VALUE, "Manual Refresh Only");

    companion object {
        fun fromName(name: String?): CacheExpirationPolicy {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MINUTES_15
        }
    }
}

data class CacheEntry<T>(
    val data: T,
    val timestampMillis: Long
) {
    fun isExpired(policy: CacheExpirationPolicy, currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        if (policy == CacheExpirationPolicy.MANUAL) return false
        val age = currentTimeMillis - timestampMillis
        return age > policy.durationMillis
    }
}

data class CacheStats(
    val repoCount: Int = 0,
    val branchCount: Int = 0,
    val directoryCount: Int = 0,
    val fileContentCount: Int = 0,
    val totalContentBytes: Long = 0L,
    val policy: CacheExpirationPolicy = CacheExpirationPolicy.MINUTES_15
)

class GitHubCacheManager(
    context: Context? = null,
    private val repoDao: RepoDao? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val prefs: SharedPreferences? = context?.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "github_cache_settings"
        private const val PREF_EXPIRATION_POLICY = "pref_cache_expiration_policy"
        const val MAX_CONTENT_CACHE_BYTES: Long = 10 * 1024 * 1024L // 10MB bounded LRU
        const val MAX_CONTENT_ITEMS: Int = 50
    }

    private var _policy = CacheExpirationPolicy.fromName(
        prefs?.getString(PREF_EXPIRATION_POLICY, CacheExpirationPolicy.MINUTES_15.name)
    )

    // 1. Repositories Cache
    @Volatile
    private var repoCacheEntry: CacheEntry<List<GitHubRepoDto>>? = null

    // 2. Branches Cache: "$owner/$repo" -> CacheEntry<List<GitHubBranchDto>>
    private val branchesCache = ConcurrentHashMap<String, CacheEntry<List<GitHubBranchDto>>>()

    // 3. Branch Details Cache: "$owner/$repo:$branch" -> CacheEntry<GitHubBranchDto>
    private val branchDetailsCache = ConcurrentHashMap<String, CacheEntry<GitHubBranchDto>>()

    // 4. Directory Contents Cache: "$owner/$repo:$branch:$cleanPath" -> CacheEntry<List<GitHubContentDto>>
    private val directoryCache = ConcurrentHashMap<String, CacheEntry<List<GitHubContentDto>>>()

    // 5. Bounded File Content Cache (LRU in-memory cache)
    private val contentLruCache = object : LinkedHashMap<String, Pair<String, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Int>>?): Boolean {
            return size > MAX_CONTENT_ITEMS
        }
    }
    private val contentTimestamps = ConcurrentHashMap<String, Long>()

    private val _statsFlow = MutableStateFlow(calculateStats())
    val statsFlow: StateFlow<CacheStats> = _statsFlow.asStateFlow()

    var expirationPolicy: CacheExpirationPolicy
        get() = _policy
        set(value) {
            _policy = value
            prefs?.edit()?.putString(PREF_EXPIRATION_POLICY, value.name)?.apply()
            updateStats()
        }

    fun getPolicy(): CacheExpirationPolicy = _policy

    fun setPolicy(newPolicy: CacheExpirationPolicy) {
        expirationPolicy = newPolicy
    }

    // --- REPOSITORY METADATA ---

    fun getRepos(bypassCache: Boolean = false, currentTimeMillis: Long = System.currentTimeMillis()): List<GitHubRepoDto>? {
        if (bypassCache) return null
        val entry = repoCacheEntry ?: return null
        if (entry.isExpired(_policy, currentTimeMillis)) {
            repoCacheEntry = null
            updateStats()
            return null
        }
        return entry.data
    }

    fun putRepos(repos: List<GitHubRepoDto>, timestampMillis: Long = System.currentTimeMillis()) {
        repoCacheEntry = CacheEntry(repos, timestampMillis)
        updateStats()

        // Optionally persist to Room repoDao for offline fallback
        repoDao?.let { dao ->
            scope.launch {
                try {
                    val entities = repos.map { dto ->
                        CachedRepoEntity(
                            id = dto.id,
                            owner = dto.owner.login,
                            name = dto.name,
                            fullName = dto.fullName,
                            isPrivate = dto.isPrivate,
                            defaultBranch = dto.defaultBranch,
                            description = dto.description,
                            updatedAt = dto.updatedAt
                        )
                    }
                    dao.clearAll()
                    dao.insertAll(entities)
                } catch (_: Exception) {
                    // Non-critical local persistence failure
                }
            }
        }
    }

    // --- BRANCH METADATA ---

    fun getBranches(
        owner: String,
        repo: String,
        bypassCache: Boolean = false,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): List<GitHubBranchDto>? {
        if (bypassCache) return null
        val key = "$owner/$repo".lowercase()
        val entry = branchesCache[key] ?: return null
        if (entry.isExpired(_policy, currentTimeMillis)) {
            branchesCache.remove(key)
            updateStats()
            return null
        }
        return entry.data
    }

    fun putBranches(
        owner: String,
        repo: String,
        branches: List<GitHubBranchDto>,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val key = "$owner/$repo".lowercase()
        branchesCache[key] = CacheEntry(branches, timestampMillis)
        updateStats()
    }

    fun getBranchDetails(
        owner: String,
        repo: String,
        branch: String,
        bypassCache: Boolean = false,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): GitHubBranchDto? {
        if (bypassCache) return null
        val key = "$owner/$repo:$branch".lowercase()
        val entry = branchDetailsCache[key] ?: return null
        if (entry.isExpired(_policy, currentTimeMillis)) {
            branchDetailsCache.remove(key)
            updateStats()
            return null
        }
        return entry.data
    }

    fun putBranchDetails(
        owner: String,
        repo: String,
        branch: String,
        details: GitHubBranchDto,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val key = "$owner/$repo:$branch".lowercase()
        branchDetailsCache[key] = CacheEntry(details, timestampMillis)
        updateStats()
    }

    // --- DIRECTORY METADATA ---

    fun getDirectoryContents(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        bypassCache: Boolean = false,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): List<GitHubContentDto>? {
        if (bypassCache) return null
        val cleanPath = path.trimStart('/').trimEnd('/')
        val key = "$owner/$repo:$branch:$cleanPath".lowercase()
        val entry = directoryCache[key] ?: return null
        if (entry.isExpired(_policy, currentTimeMillis)) {
            directoryCache.remove(key)
            updateStats()
            return null
        }
        return entry.data
    }

    fun putDirectoryContents(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        contents: List<GitHubContentDto>,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val cleanPath = path.trimStart('/').trimEnd('/')
        val key = "$owner/$repo:$branch:$cleanPath".lowercase()
        directoryCache[key] = CacheEntry(contents, timestampMillis)
        updateStats()
    }

    // --- BOUNDED FILE CONTENT CACHE ---

    fun getFileContent(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        bypassCache: Boolean = false,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): String? {
        if (bypassCache) return null
        val cleanPath = path.trimStart('/')
        val key = "$owner/$repo:$branch:$cleanPath".lowercase()
        val timestamp = contentTimestamps[key] ?: return null
        if (_policy != CacheExpirationPolicy.MANUAL) {
            val age = currentTimeMillis - timestamp
            if (age > _policy.durationMillis) {
                synchronized(contentLruCache) {
                    contentLruCache.remove(key)
                }
                contentTimestamps.remove(key)
                updateStats()
                return null
            }
        }
        val cached = synchronized(contentLruCache) {
            contentLruCache.get(key)
        }
        return cached?.first
    }

    fun putFileContent(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        content: String,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val cleanPath = path.trimStart('/')
        val key = "$owner/$repo:$branch:$cleanPath".lowercase()
        val sizeInBytes = content.toByteArray(Charsets.UTF_8).size
        synchronized(contentLruCache) {
            contentLruCache.put(key, Pair(content, sizeInBytes))
        }
        contentTimestamps[key] = timestampMillis
        updateStats()
    }

    // --- EVICTION AND CLEAR ---

    fun evictExpired(currentTimeMillis: Long = System.currentTimeMillis()) {
        if (_policy == CacheExpirationPolicy.MANUAL) return

        if (repoCacheEntry?.isExpired(_policy, currentTimeMillis) == true) {
            repoCacheEntry = null
        }

        branchesCache.entries.removeIf { it.value.isExpired(_policy, currentTimeMillis) }
        branchDetailsCache.entries.removeIf { it.value.isExpired(_policy, currentTimeMillis) }
        directoryCache.entries.removeIf { it.value.isExpired(_policy, currentTimeMillis) }

        val expiredContentKeys = contentTimestamps.filter { (_, time) ->
            currentTimeMillis - time > _policy.durationMillis
        }.keys

        synchronized(contentLruCache) {
            for (k in expiredContentKeys) {
                contentLruCache.remove(k)
                contentTimestamps.remove(k)
            }
        }
        updateStats()
    }

    /**
     * Clear all metadata and content cache.
     * Invoked manually from SettingsScreen or automatically on user sign-out to ensure
     * private repository data and contents do not persist.
     */
    fun clearAllCache() {
        repoCacheEntry = null
        branchesCache.clear()
        branchDetailsCache.clear()
        directoryCache.clear()
        synchronized(contentLruCache) {
            contentLruCache.clear()
        }
        contentTimestamps.clear()

        repoDao?.let { dao ->
            scope.launch {
                try {
                    dao.clearAll()
                } catch (_: Exception) {}
            }
        }

        updateStats()
    }

    fun clearFileContentCache() {
        synchronized(contentLruCache) {
            contentLruCache.clear()
        }
        contentTimestamps.clear()
        updateStats()
    }

    fun clearRepoCache(owner: String, repo: String) {
        val prefix = "$owner/$repo".lowercase()
        branchesCache.remove(prefix)
        branchDetailsCache.keys.removeIf { it.startsWith(prefix) }
        directoryCache.keys.removeIf { it.startsWith(prefix) }
        val contentKeysToRemove = contentTimestamps.keys.filter { it.startsWith(prefix) }
        synchronized(contentLruCache) {
            for (k in contentKeysToRemove) {
                contentLruCache.remove(k)
                contentTimestamps.remove(k)
            }
        }
        updateStats()
    }

    fun getStats(): CacheStats = calculateStats()

    private fun calculateStats(): CacheStats {
        val repos = if (repoCacheEntry != null) (repoCacheEntry?.data?.size ?: 0) else 0
        val branches = branchesCache.values.sumOf { it.data.size }
        val dirs = directoryCache.size
        val (filesCount, bytes) = synchronized(contentLruCache) {
            Pair(contentLruCache.size, contentLruCache.values.sumOf { it.second.toLong() })
        }
        return CacheStats(
            repoCount = repos,
            branchCount = branches,
            directoryCount = dirs,
            fileContentCount = filesCount,
            totalContentBytes = bytes,
            policy = _policy
        )
    }

    private fun updateStats() {
        _statsFlow.value = calculateStats()
    }
}
