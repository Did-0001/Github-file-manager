package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.SelectedRepoInfo
import com.example.ui.screens.auth.AuthDialog
import com.example.ui.screens.download.DownloadScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.repository.RepoBrowserScreen
import com.example.ui.screens.repository.RepoSelectorDialog
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.transfers.TransfersScreen
import com.example.ui.screens.upload.UploadScreen
import com.example.ui.theme.GhDarkAccentBlue
import com.example.ui.theme.GhDarkAccentGreen
import com.example.ui.theme.GhDarkAccentPurple
import com.example.ui.viewmodel.MainViewModel

enum class NavigationTab(val title: String, val icon: ImageVector, val tag: String) {
    HOME("Home", Icons.Default.Home, "nav_home"),
    UPLOAD("Upload", Icons.Default.CloudUpload, "nav_upload"),
    DOWNLOAD("Download", Icons.Default.CloudDownload, "nav_download"),
    EXPLORER("Explorer", Icons.Default.Folder, "nav_explorer"),
    TRANSFERS("Transfers", Icons.Default.SwapVert, "nav_transfers"),
    SETTINGS("Settings", Icons.Default.Settings, "nav_settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var currentTab by remember { mutableStateOf(NavigationTab.HOME) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showRepoSelectorDialog by remember { mutableStateOf(false) }
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }
    var pendingDownloadIsFile by remember { mutableStateOf(false) }

    val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
    val authUser by viewModel.authUser.collectAsStateWithLifecycle()
    val selectedRepo by viewModel.selectedRepo.collectAsStateWithLifecycle()
    val userRepos by viewModel.userRepos.collectAsStateWithLifecycle()
    val isLoadingRepos by viewModel.isLoadingRepos.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle(initialValue = emptyList())

    val currentBrowsePath by viewModel.currentBrowsePath.collectAsStateWithLifecycle()
    val repoContents by viewModel.repoContents.collectAsStateWithLifecycle()
    val isLoadingContents by viewModel.isLoadingContents.collectAsStateWithLifecycle()
    val contentsError by viewModel.contentsError.collectAsStateWithLifecycle()

    val scannedFiles by viewModel.scannedFiles.collectAsStateWithLifecycle()
    val excludedItems by viewModel.excludedItems.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val ignoreRules by viewModel.ignoreRules.collectAsStateWithLifecycle()
    val diffReport by viewModel.diffReport.collectAsStateWithLifecycle()
    val isCalculatingDiff by viewModel.isCalculatingDiff.collectAsStateWithLifecycle()
    val preflightReport by viewModel.preflightReport.collectAsStateWithLifecycle()
    val deviceFlowState by viewModel.deviceFlowState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "GitHub File Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (selectedRepo != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showRepoSelectorDialog = true }
                                    .testTag("topbar_repo_pill")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ForkRight,
                                        contentDescription = null,
                                        tint = GhDarkAccentPurple,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "${selectedRepo?.name}:${selectedRepo?.branch}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = GhDarkAccentPurple,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAuthDialog = true },
                        modifier = Modifier.testTag("topbar_auth_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (isAuthenticated) GhDarkAccentGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isAuthenticated) Icons.Default.Check else Icons.Default.AccountCircle,
                                contentDescription = "Account",
                                tint = if (isAuthenticated) GhDarkAccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("bottom_nav_bar")
            ) {
                NavigationTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title, fontSize = 11.sp, fontWeight = if (currentTab == tab) FontWeight.Bold else FontWeight.Normal) },
                        modifier = Modifier.testTag(tab.tag)
                    )
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                NavigationTab.HOME -> {
                    HomeScreen(
                        isAuthenticated = isAuthenticated,
                        authUser = authUser,
                        selectedRepo = selectedRepo,
                        recentTransfers = transfers,
                        onOpenAuth = { showAuthDialog = true },
                        onOpenRepoSelector = { showRepoSelectorDialog = true },
                        onNavigateUpload = { currentTab = NavigationTab.UPLOAD },
                        onNavigateDownload = { currentTab = NavigationTab.DOWNLOAD },
                        onNavigateRepository = { currentTab = NavigationTab.EXPLORER },
                        onNavigateTransfers = { currentTab = NavigationTab.TRANSFERS }
                    )
                }

                NavigationTab.UPLOAD -> {
                    UploadScreen(
                        selectedRepo = selectedRepo,
                        onScanFolder = { uri -> viewModel.scanFolder(uri) },
                        onScanFiles = { uris -> viewModel.scanFiles(uris) },
                        scannedFiles = scannedFiles,
                        excludedItems = excludedItems,
                        isScanning = isScanning,
                        ignoreRules = ignoreRules,
                        onUpdateIgnoreRules = { rules -> viewModel.updateIgnoreRules(rules) },
                        onStartUpload = { destinationDir, commitMsg, isWipe, wipeMode ->
                            viewModel.startUpload(destinationDir, commitMsg, isWipe, wipeMode) {
                                currentTab = NavigationTab.TRANSFERS
                            }
                        },
                        onOpenRepoSelector = { showRepoSelectorDialog = true },
                        diffReport = diffReport,
                        isCalculatingDiff = isCalculatingDiff,
                        preflightReport = preflightReport,
                        onRunPreflight = { dest, isWipe, wipeMode -> viewModel.runPreflightAndDiff(dest, isWipe, wipeMode) }
                    )
                }

                NavigationTab.DOWNLOAD -> {
                    DownloadScreen(
                        selectedRepo = selectedRepo,
                        initialPath = pendingDownloadPath,
                        initialIsFile = pendingDownloadIsFile,
                        onOpenRepoSelector = { showRepoSelectorDialog = true },
                        onStartDownload = { remotePath, config ->
                            pendingDownloadPath = null
                            pendingDownloadIsFile = false
                            viewModel.startDownload(remotePath, config) {
                                currentTab = NavigationTab.TRANSFERS
                            }
                        }
                    )
                }

                NavigationTab.EXPLORER -> {
                    RepoBrowserScreen(
                        selectedRepo = selectedRepo,
                        currentPath = currentBrowsePath,
                        contents = repoContents,
                        isLoading = isLoadingContents,
                        errorMessage = contentsError,
                        onNavigatePath = { path -> viewModel.browsePath(path) },
                        onRefresh = { viewModel.refreshContents() },
                        onCreateFile = { name, content, commitMsg, cb -> viewModel.createFile(name, content, commitMsg, cb) },
                        onCreateDirectory = { name, cb -> viewModel.createDirectory(name, cb) },
                        onDeleteFile = { file, commitMsg, cb -> viewModel.deleteFile(file, commitMsg, cb) },
                        onDeleteFilesBatch = { paths, commitMsg, cb -> viewModel.deleteFilesBatch(paths, commitMsg, cb) },
                        onLoadFileContent = { file, cb -> viewModel.loadRawFileContent(file, cb) },
                        onUpdateFile = { file, content, commitMsg, cb -> viewModel.updateFile(file, content, commitMsg, cb) },
                        onDownloadFile = { file ->
                            pendingDownloadPath = file.path
                            pendingDownloadIsFile = true
                            currentTab = NavigationTab.DOWNLOAD
                        },
                        onDownloadCurrentFolder = { folder ->
                            pendingDownloadPath = folder
                            pendingDownloadIsFile = false
                            currentTab = NavigationTab.DOWNLOAD
                        },
                        onOpenRepoSelector = { showRepoSelectorDialog = true }
                    )
                }

                NavigationTab.TRANSFERS -> {
                    TransfersScreen(
                        transfers = transfers,
                        onPauseTransfer = { id -> viewModel.pauseTransfer(id) },
                        onResumeTransfer = { id -> viewModel.resumeTransfer(id) },
                        onCancelTransfer = { id -> viewModel.cancelTransfer(id) },
                        onRetryTransfer = { id, failedOnly -> viewModel.retryTransfer(id, failedOnly) },
                        onDeleteTransfer = { id -> viewModel.deleteTransfer(id) },
                        onClearCompleted = { viewModel.clearCompletedTransfers() },
                        onClearAll = { viewModel.clearAllTransfers() },
                        getSpeedForTransfer = { id -> viewModel.getSpeedForTransfer(id) },
                        getItemsForTransfer = { id -> viewModel.getItemsForTransfer(id) }
                    )
                }

                NavigationTab.SETTINGS -> {
                    SettingsScreen(
                        isAuthenticated = isAuthenticated,
                        authUser = authUser,
                        selectedRepo = selectedRepo,
                        onOpenAuth = { showAuthDialog = true },
                        onSignOut = { viewModel.signOut() },
                        onOpenRepoSelector = { showRepoSelectorDialog = true }
                    )
                }
            }
        }
    }

    // Auth Dialog
    if (showAuthDialog) {
        AuthDialog(
            onDismiss = { showAuthDialog = false },
            onVerifyPat = { token, cb -> viewModel.verifyPat(token, cb) },
            onRequestDeviceCode = { clientId, cb -> viewModel.requestDeviceCode(clientId, cb) },
            deviceFlowState = deviceFlowState,
            onCancelDeviceFlow = { viewModel.cancelDeviceFlow() }
        )
    }

    // Repo Selector Dialog
    if (showRepoSelectorDialog) {
        RepoSelectorDialog(
            repos = userRepos,
            isLoading = isLoadingRepos,
            onDismiss = { showRepoSelectorDialog = false },
            onRefreshRepos = { viewModel.loadUserRepos() },
            onSelectRepo = { repo, branch -> viewModel.selectRepo(repo, branch) },
            onFetchBranches = { owner, repo -> viewModel.fetchBranches(owner, repo) },
            onCreateRepo = { name, desc, isPrivate, cb -> viewModel.createRepo(name, desc, isPrivate, cb) }
        )
    }
}
