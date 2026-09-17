package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.SecureStorage
import com.example.data.local.SelectedRepoInfo
import com.example.data.local.entity.TransferEntity
import com.example.data.remote.ApiClient
import com.example.data.remote.dto.GitHubBranchDto
import com.example.data.remote.dto.GitHubContentDto
import com.example.data.remote.dto.GitHubRepoDto
import com.example.data.repository.*
import com.example.domain.engine.TransferEngine
import com.example.domain.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val secureStorage = SecureStorage(application)
    private val apiClient = ApiClient(secureStorage)
    private val authRepository = AuthRepository(apiClient, secureStorage)
    private val gitHubRepository = GitHubRepository(apiClient)
    private val database = AppDatabase.getInstance(application)
    private val transferRepository = TransferRepository(database)
    val transferEngine = TransferEngine(application, gitHubRepository, transferRepository)

    // Auth State
    private val _isAuthenticated = MutableStateFlow(secureStorage.hasToken())
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _authUser = MutableStateFlow<Pair<String, String?>?>(null)
    val authUser: StateFlow<Pair<String, String?>?> = _authUser.asStateFlow()

    private val _deviceFlowState = MutableStateFlow<DeviceFlowState?>(null)
    val deviceFlowState: StateFlow<DeviceFlowState?> = _deviceFlowState.asStateFlow()

    private var deviceFlowJob: Job? = null

    // Selected Repo & Branch
    private val _selectedRepo = MutableStateFlow<SelectedRepoInfo?>(secureStorage.getSelectedRepo())
    val selectedRepo: StateFlow<SelectedRepoInfo?> = _selectedRepo.asStateFlow()

    // User Repositories list
    private val _userRepos = MutableStateFlow<List<GitHubRepoDto>>(emptyList())
    val userRepos: StateFlow<List<GitHubRepoDto>> = _userRepos.asStateFlow()

    private val _isLoadingRepos = MutableStateFlow(false)
    val isLoadingRepos: StateFlow<Boolean> = _isLoadingRepos.asStateFlow()

    // Repository Browser State
    private val _currentBrowsePath = MutableStateFlow("")
    val currentBrowsePath: StateFlow<String> = _currentBrowsePath.asStateFlow()

    private val _repoContents = MutableStateFlow<List<GitHubContentDto>>(emptyList())
    val repoContents: StateFlow<List<GitHubContentDto>> = _repoContents.asStateFlow()

    private val _isLoadingContents = MutableStateFlow(false)
    val isLoadingContents: StateFlow<Boolean> = _isLoadingContents.asStateFlow()

    private val _contentsError = MutableStateFlow<String?>(null)
    val contentsError: StateFlow<String?> = _contentsError.asStateFlow()

    // Transfers
    val transfers: Flow<List<TransferEntity>> = transferRepository.allTransfers

    // Upload preparation state
    private val _scannedFiles = MutableStateFlow<List<FileScanItem>>(emptyList())
    val scannedFiles: StateFlow<List<FileScanItem>> = _scannedFiles.asStateFlow()

    private val _excludedItems = MutableStateFlow<List<DiffItem>>(emptyList())
    val excludedItems: StateFlow<List<DiffItem>> = _excludedItems.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _ignoreRules = MutableStateFlow(IgnoreRules())
    val ignoreRules: StateFlow<IgnoreRules> = _ignoreRules.asStateFlow()

    private val _diffReport = MutableStateFlow<DiffReport?>(null)
    val diffReport: StateFlow<DiffReport?> = _diffReport.asStateFlow()

    private val _isCalculatingDiff = MutableStateFlow(false)
    val isCalculatingDiff: StateFlow<Boolean> = _isCalculatingDiff.asStateFlow()

    private val _preflightReport = MutableStateFlow<PreflightReport?>(null)
    val preflightReport: StateFlow<PreflightReport?> = _preflightReport.asStateFlow()

    init {
        if (_isAuthenticated.value) {
            refreshUserProfile()
            loadUserRepos()
        }
        refreshContents()
    }

    // AUTH METHODS
    fun refreshUserProfile() {
        viewModelScope.launch {
            val userRes = gitHubRepository.getAuthenticatedUser()
            if (userRes.isSuccess) {
                val u = userRes.getOrThrow()
                _authUser.value = Pair(u.login, u.name)
            }
        }
    }

    fun verifyPat(token: String, callback: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = authRepository.verifyAndSavePat(token)
            if (res.isSuccess) {
                val u = res.getOrThrow()
                _isAuthenticated.value = true
                _authUser.value = Pair(u.login, u.name)
                loadUserRepos()
                callback(true, u.login)
            } else {
                callback(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun requestDeviceCode(clientId: String, callback: (Boolean, String?, Any?) -> Unit) {
        viewModelScope.launch {
            val res = authRepository.requestDeviceCode(clientId)
            if (res.isSuccess) {
                val response = res.getOrThrow()
                callback(true, null, response)

                deviceFlowJob?.cancel()
                deviceFlowJob = viewModelScope.launch {
                    authRepository.pollDeviceAuthorization(clientId, response.deviceCode, response.interval)
                        .collect { state ->
                            _deviceFlowState.value = state
                            if (state is DeviceFlowState.Success) {
                                _isAuthenticated.value = true
                                _authUser.value = Pair(state.user.login, state.user.name)
                                loadUserRepos()
                            }
                        }
                }
            } else {
                callback(false, res.exceptionOrNull()?.message, null)
            }
        }
    }

    fun cancelDeviceFlow() {
        deviceFlowJob?.cancel()
        _deviceFlowState.value = null
    }

    fun signOut() {
        authRepository.logout()
        _isAuthenticated.value = false
        _authUser.value = null
        _selectedRepo.value = null
        _userRepos.value = emptyList()
        _repoContents.value = emptyList()
    }

    // REPOSITORY METHODS
    fun loadUserRepos() {
        viewModelScope.launch {
            _isLoadingRepos.value = true
            val res = gitHubRepository.getAllUserRepos()
            if (res.isSuccess) {
                _userRepos.value = res.getOrThrow()
                // If no repo selected, select first
                if (_selectedRepo.value == null && _userRepos.value.isNotEmpty()) {
                    val first = _userRepos.value.first()
                    selectRepo(first, first.defaultBranch)
                }
            }
            _isLoadingRepos.value = false
        }
    }

    fun selectRepo(repo: GitHubRepoDto, branch: String) {
        val info = SelectedRepoInfo(
            owner = repo.owner.login,
            name = repo.name,
            branch = branch,
            defaultBranch = repo.defaultBranch,
            isPrivate = repo.isPrivate
        )
        secureStorage.saveSelectedRepo(info)
        _selectedRepo.value = info
        _currentBrowsePath.value = ""
        refreshContents()
    }

    suspend fun fetchBranches(owner: String, repo: String): List<GitHubBranchDto> {
        val res = gitHubRepository.getAllBranches(owner, repo)
        return res.getOrElse { emptyList() }
    }

    fun createRepo(name: String, description: String?, isPrivate: Boolean, callback: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = gitHubRepository.createRepo(name, description, isPrivate)
            if (res.isSuccess) {
                val created = res.getOrThrow()
                loadUserRepos()
                selectRepo(created, created.defaultBranch)
                callback(true, null)
            } else {
                callback(false, res.exceptionOrNull()?.message)
            }
        }
    }

    // BROWSER METHODS
    fun browsePath(path: String) {
        _currentBrowsePath.value = path
        refreshContents()
    }

    fun refreshContents() {
        val repo = _selectedRepo.value ?: return
        viewModelScope.launch {
            _isLoadingContents.value = true
            _contentsError.value = null
            val res = gitHubRepository.getDirectoryContents(
                owner = repo.owner,
                repo = repo.name,
                path = _currentBrowsePath.value,
                branch = repo.branch
            )
            if (res.isSuccess) {
                _repoContents.value = res.getOrThrow()
            } else {
                _contentsError.value = res.exceptionOrNull()?.message ?: "Failed to fetch repository contents."
            }
            _isLoadingContents.value = false
        }
    }

    fun createFile(fileName: String, content: String, commitMsg: String, callback: (Boolean, String?) -> Unit) {
        val repo = _selectedRepo.value ?: return
        val currentDir = _currentBrowsePath.value.trim('/')
        val filePath = if (currentDir.isEmpty()) fileName else "$currentDir/$fileName"

        viewModelScope.launch {
            val base64 = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val res = gitHubRepository.createOrUpdateFile(
                owner = repo.owner,
                repo = repo.name,
                path = filePath,
                contentBase64 = base64,
                commitMessage = commitMsg,
                branch = repo.branch
            )
            if (res.isSuccess) {
                refreshContents()
                callback(true, null)
            } else {
                callback(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun createDirectory(dirName: String, callback: (Boolean, String?) -> Unit) {
        val repo = _selectedRepo.value ?: return
        val currentDir = _currentBrowsePath.value.trim('/')
        val filePath = if (currentDir.isEmpty()) "$dirName/.gitkeep" else "$currentDir/$dirName/.gitkeep"

        viewModelScope.launch {
            val base64 = android.util.Base64.encodeToString("".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val res = gitHubRepository.createOrUpdateFile(
                owner = repo.owner,
                repo = repo.name,
                path = filePath,
                contentBase64 = base64,
                commitMessage = "Initialize $dirName with .gitkeep",
                branch = repo.branch
            )
            if (res.isSuccess) {
                refreshContents()
                callback(true, null)
            } else {
                callback(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun deleteFile(file: GitHubContentDto, commitMsg: String, callback: (Boolean, String?) -> Unit) {
        val repo = _selectedRepo.value ?: return
        viewModelScope.launch {
            val res = gitHubRepository.deleteFile(
                owner = repo.owner,
                repo = repo.name,
                path = file.path,
                commitMessage = commitMsg,
                branch = repo.branch,
                sha = file.sha
            )
            if (res.isSuccess) {
                refreshContents()
                callback(true, null)
            } else {
                callback(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun updateFile(file: GitHubContentDto, newContent: String, commitMsg: String, callback: (Boolean, String?) -> Unit) {
        val repo = _selectedRepo.value ?: return
        viewModelScope.launch {
            val base64 = android.util.Base64.encodeToString(newContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val res = gitHubRepository.createOrUpdateFile(
                owner = repo.owner,
                repo = repo.name,
                path = file.path,
                contentBase64 = base64,
                commitMessage = commitMsg,
                branch = repo.branch,
                sha = file.sha
            )
            if (res.isSuccess) {
                refreshContents()
                callback(true, null)
            } else {
                callback(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun loadRawFileContent(file: GitHubContentDto, callback: (Result<String>) -> Unit) {
        val repo = _selectedRepo.value ?: return
        viewModelScope.launch {
            val url = file.downloadUrl ?: "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/${repo.branch}/${file.path}"
            val res = gitHubRepository.downloadRaw(url)
            if (res.isSuccess) {
                try {
                    val text = res.getOrThrow().string()
                    callback(Result.success(text))
                } catch (e: Exception) {
                    callback(Result.failure(e))
                }
            } else {
                callback(Result.failure(res.exceptionOrNull() ?: Exception("Failed to load file")))
            }
        }
    }

    // UPLOAD & SCANNING METHODS
    fun scanFolder(uri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            val (files, excluded) = transferEngine.scanLocalFolder(uri, _ignoreRules.value)
            _scannedFiles.value = files
            _excludedItems.value = excluded
            _diffReport.value = null
            _preflightReport.value = null
            _isScanning.value = false
        }
    }

    fun scanFiles(uris: List<Uri>) {
        viewModelScope.launch {
            _isScanning.value = true
            val (files, excluded) = transferEngine.scanLocalFiles(uris, _ignoreRules.value)
            _scannedFiles.value = files
            _excludedItems.value = excluded
            _diffReport.value = null
            _preflightReport.value = null
            _isScanning.value = false
        }
    }

    fun updateIgnoreRules(rules: IgnoreRules) {
        _ignoreRules.value = rules
    }

    fun runPreflightAndDiff(destinationDir: String, isWipe: Boolean) {
        val repo = _selectedRepo.value ?: return
        val files = _scannedFiles.value
        if (files.isEmpty()) return

        viewModelScope.launch {
            _isCalculatingDiff.value = true

            // Preflight
            val preflight = transferEngine.runPreflight(
                owner = repo.owner,
                repo = repo.name,
                branch = repo.branch,
                destinationDir = destinationDir,
                files = files,
                isWipe = isWipe
            )
            _preflightReport.value = preflight

            // Diff
            val diff = transferEngine.calculateDiff(
                owner = repo.owner,
                repo = repo.name,
                branch = repo.branch,
                destinationDir = destinationDir,
                localFiles = files,
                excludedItems = _excludedItems.value,
                isWipe = isWipe
            )
            _diffReport.value = diff
            _isCalculatingDiff.value = false
        }
    }

    fun startUpload(destinationDir: String, commitMessage: String, isWipe: Boolean, onStarted: (String) -> Unit) {
        val repo = _selectedRepo.value ?: return
        val files = _scannedFiles.value
        if (files.isEmpty()) return

        val transferId = transferEngine.startUpload(
            owner = repo.owner,
            repo = repo.name,
            branch = repo.branch,
            destinationDir = destinationDir,
            files = files,
            commitMessage = commitMessage,
            isWipe = isWipe,
            reviewedHeadSha = _diffReport.value?.reviewedHeadSha,
            onCreated = onStarted
        )
    }

    fun startDownload(remotePath: String, config: DownloadConfig, onStarted: (String) -> Unit) {
        val repo = _selectedRepo.value ?: return
        val transferId = transferEngine.startDownload(
            owner = repo.owner,
            repo = repo.name,
            branch = repo.branch,
            remotePath = remotePath,
            config = config,
            onCreated = onStarted
        )
    }

    // TRANSFER ACTIONS
    fun pauseTransfer(transferId: String) {
        transferEngine.pauseTransfer(transferId)
    }

    fun resumeTransfer(transferId: String) {
        transferEngine.resumeTransfer(transferId)
    }

    fun cancelTransfer(transferId: String) {
        transferEngine.cancelTransfer(transferId)
    }

    fun retryTransfer(transferId: String, failedOnly: Boolean = true) {
        transferEngine.retryTransfer(transferId, failedOnly)
    }

    fun deleteTransfer(transferId: String) {
        viewModelScope.launch {
            transferRepository.deleteTransfer(transferId)
        }
    }

    fun clearAllTransfers() {
        viewModelScope.launch {
            transferRepository.clearAll()
        }
    }

    fun clearCompletedTransfers() {
        viewModelScope.launch {
            transferRepository.clearCompleted()
        }
    }

    fun getItemsForTransfer(transferId: String): Flow<List<com.example.data.local.entity.TransferItemEntity>> {
        return transferRepository.getItemsForTransfer(transferId)
    }

    fun deleteFilesBatch(paths: List<String>, commitMessage: String, onResult: (Boolean, String?) -> Unit) {
        val repo = _selectedRepo.value ?: return
        viewModelScope.launch {
            val res = gitHubRepository.deleteFilesBatch(repo.owner, repo.name, repo.branch, paths, commitMessage)
            if (res.isSuccess) {
                refreshContents()
                onResult(true, null)
            } else {
                onResult(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun getSpeedForTransfer(transferId: String): Long {
        return transferEngine.getTransferSpeed(transferId)
    }
}
