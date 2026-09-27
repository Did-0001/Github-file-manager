package com.example.domain.engine

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import com.example.data.remote.ApiErrorType
import com.example.data.remote.GitHubApiException
import com.example.data.remote.dto.CreateTreeEntryDto
import com.example.data.remote.dto.GitHubContentDto
import com.example.data.remote.dto.GitTreeItemDto
import com.example.data.repository.GitHubRepository
import com.example.data.repository.TransferRepository
import com.example.domain.model.*
import com.example.domain.worker.TransferWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class TransferEngine(
    private val context: Context,
    private val gitHubRepository: GitHubRepository,
    private val transferRepository: TransferRepository,
    private val engineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedTransfers = ConcurrentHashMap.newKeySet<String>()
    private val executionMutex = Mutex()

    init {
        reconcileTransfers()
    }

    fun reconcileTransfers() {
        engineScope.launch {
            try {
                val activeEntities = transferRepository.getActiveTransfersSync()
                val workManager = WorkManager.getInstance(context)
                for (entity in activeEntities) {
                    val status = try { TransferStatus.valueOf(entity.status) } catch (_: Exception) { null }
                    if (status == null || !status.isActive) {
                        continue
                    }
                    val workInfos = workManager.getWorkInfosForUniqueWork("transfer_${entity.id}").get()
                    val isRunningOrEnqueued = workInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
                    }
                    if (!isRunningOrEnqueued) {
                        updateTransferStatus(entity.copy(status = TransferStatus.QUEUED.name))
                        val request = OneTimeWorkRequestBuilder<TransferWorker>()
                            .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to entity.id))
                            .addTag("transfer_${entity.id}")
                            .build()
                        workManager.enqueueUniqueWork(
                            "transfer_${entity.id}",
                            ExistingWorkPolicy.KEEP,
                            request
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Speed calculation
    private val transferSpeeds = ConcurrentHashMap<String, Long>() // bytes per second

    fun getTransferSpeed(transferId: String): Long = transferSpeeds[transferId] ?: 0L

    // 1. SAF Scanner
    suspend fun scanLocalFolder(
        treeUri: Uri,
        ignoreRules: IgnoreRules = IgnoreRules()
    ): Pair<List<FileScanItem>, List<DiffItem>> = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext Pair(emptyList(), emptyList())

        val scannedFiles = mutableListOf<FileScanItem>()
        val excludedItems = mutableListOf<DiffItem>()

        suspend fun traverse(dir: DocumentFile, currentPath: String) {
            val children = dir.listFiles()
            for (child in children) {
                val name = child.name ?: continue
                val relativePath = if (currentPath.isEmpty()) name else "$currentPath/$name"

                if (child.isDirectory) {
                    val (ignored, reason) = ignoreRules.isIgnored(relativePath, true, 0L)
                    if (ignored) {
                        excludedItems.add(
                            DiffItem(
                                localPath = relativePath,
                                remotePath = "",
                                changeType = DiffChangeType.EXCLUDED,
                                sizeBytes = 0,
                                reason = reason ?: "Excluded folder rule ($name)"
                            )
                        )
                    } else {
                        traverse(child, relativePath)
                    }
                } else {
                    val size = child.length()
                    val (ignored, reason) = ignoreRules.isIgnored(relativePath, false, size)
                    if (ignored) {
                        excludedItems.add(
                            DiffItem(
                                localPath = relativePath,
                                remotePath = "",
                                changeType = DiffChangeType.EXCLUDED,
                                sizeBytes = size,
                                reason = reason ?: "Matched exclude rule ($name)"
                            )
                        )
                    } else {
                        scannedFiles.add(
                            FileScanItem(
                                uri = child.uri,
                                relativePath = relativePath,
                                sizeBytes = size,
                                isDirectory = false
                            )
                        )
                    }
                }
            }
        }

        traverse(rootDoc, "")
        Pair(scannedFiles, excludedItems)
    }

    suspend fun scanLocalFiles(
        uris: List<Uri>,
        ignoreRules: IgnoreRules = IgnoreRules()
    ): Pair<List<FileScanItem>, List<DiffItem>> = withContext(Dispatchers.IO) {
        val scannedFiles = mutableListOf<FileScanItem>()
        val excludedItems = mutableListOf<DiffItem>()

        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: continue
            val name = doc.name ?: "file_${UUID.randomUUID().toString().take(6)}"
            val size = doc.length()

            val (ignored, reason) = ignoreRules.isIgnored(name, false, size)
            if (ignored) {
                excludedItems.add(
                    DiffItem(
                        localPath = name,
                        remotePath = "",
                        changeType = DiffChangeType.EXCLUDED,
                        sizeBytes = size,
                        reason = reason ?: "Matched exclude rule"
                    )
                )
            } else {
                scannedFiles.add(
                    FileScanItem(
                        uri = uri,
                        relativePath = name,
                        sizeBytes = size,
                        isDirectory = false
                    )
                )
            }
        }

        Pair(scannedFiles, excludedItems)
    }

    // 2. Preflight Safety Validation
    suspend fun runPreflight(
        owner: String,
        repo: String,
        branch: String,
        destinationDir: String,
        files: List<FileScanItem>,
        isWipe: Boolean
    ): PreflightReport = withContext(Dispatchers.IO) {
        val checks = mutableListOf<PreflightCheckItem>()
        var warnings = 0
        var errors = 0
        val totalBytes = files.sumOf { it.sizeBytes }

        // A. Branch & Repo Reachability
        val branchRes = gitHubRepository.getBranchHeadSha(owner, repo, branch)
        if (branchRes.isSuccess) {
            checks.add(
                PreflightCheckItem(
                    title = "Repository & Branch Access",
                    level = CheckLevel.PASS,
                    detail = "Branch '$branch' reachable. Current HEAD: ${branchRes.getOrThrow().take(8)}"
                )
            )
        } else {
            errors++
            checks.add(
                PreflightCheckItem(
                    title = "Repository & Branch Access",
                    level = CheckLevel.FAIL,
                    detail = "Cannot access branch '$branch': ${branchRes.exceptionOrNull()?.message}"
                )
            )
        }

        // B. GitHub 100MB Hard File Limit
        val max100Mb = 100L * 1024 * 1024
        val oversizeFiles = files.filter { it.sizeBytes > max100Mb }
        if (oversizeFiles.isNotEmpty()) {
            errors += oversizeFiles.size
            oversizeFiles.forEach { f ->
                checks.add(
                    PreflightCheckItem(
                        title = "File Size Limit Exceeded (>100MB)",
                        level = CheckLevel.FAIL,
                        detail = "${f.relativePath} is ${formatBytes(f.sizeBytes)}. GitHub rejects files >100MB without Git LFS."
                    )
                )
            }
        } else {
            checks.add(
                PreflightCheckItem(
                    title = "File Size Ceiling",
                    level = CheckLevel.PASS,
                    detail = "All ${files.size} files are within GitHub's 100MB per-file upload limit."
                )
            )
        }

        // C. Warning for Large Batches (>50MB total or >500 files)
        if (totalBytes > 50L * 1024 * 1024) {
            warnings++
            checks.add(
                PreflightCheckItem(
                    title = "Large Transfer Payload",
                    level = CheckLevel.WARN,
                    detail = "Total upload payload is ${formatBytes(totalBytes)}. Ensure steady network connectivity."
                )
            )
        }

        if (files.size > 500) {
            warnings++
            checks.add(
                PreflightCheckItem(
                    title = "High File Count",
                    level = CheckLevel.WARN,
                    detail = "${files.size} files to upload. Upload rate limiting will throttle to prevent 403 secondary rate limits."
                )
            )
        }

        // D. Wipe-Before-Upload Warning
        if (isWipe) {
            warnings++
            checks.add(
                PreflightCheckItem(
                    title = "Destructive Wipe-Before-Upload Enabled",
                    level = CheckLevel.WARN,
                    detail = "All remote files in the branch not present in the local upload will be deleted."
                )
            )
        }

        // E. Destination path validation
        val normalized = normalizeDestination(destinationDir)
        if (normalized.contains("//") || normalized.contains("..")) {
            errors++
            checks.add(
                PreflightCheckItem(
                    title = "Destination Path Syntax",
                    level = CheckLevel.FAIL,
                    detail = "Destination path '$destinationDir' contains invalid traversal tokens."
                )
            )
        } else {
            checks.add(
                PreflightCheckItem(
                    title = "Destination Path",
                    level = CheckLevel.PASS,
                    detail = if (normalized.isEmpty()) "Target: Repository root (/)" else "Target folder: /$normalized/"
                )
            )
        }

        PreflightReport(
            checks = checks,
            totalFiles = files.size,
            totalBytes = totalBytes,
            warningsCount = warnings,
            errorsCount = errors
        )
    }

    // 3. Diff Calculation with Exact Git Blob Hash Comparison
    suspend fun calculateDiff(
        owner: String,
        repo: String,
        branch: String,
        destinationDir: String,
        localFiles: List<FileScanItem>,
        excludedItems: List<DiffItem>,
        isWipe: Boolean,
        wipeMode: WipeMode = if (isWipe) WipeMode.FULL_BRANCH else WipeMode.NONE
    ): DiffReport = withContext(Dispatchers.IO) {
        val normalizedDest = normalizeDestination(destinationDir)

        // Fetch remote tree using full tree recursion handling
        val headShaRes = gitHubRepository.getBranchHeadSha(owner, repo, branch)
        val remoteMap = mutableMapOf<String, GitTreeItemDto>()
        val reviewedHeadSha = headShaRes.getOrNull()

        if (headShaRes.isSuccess) {
            val treeShaRes = gitHubRepository.getCommitTreeSha(owner, repo, headShaRes.getOrThrow())
            if (treeShaRes.isFailure) {
                throw treeShaRes.exceptionOrNull() ?: GitHubApiException(ApiErrorType.SERVER_ERROR, message = "Failed to resolve commit tree SHA")
            }
            val fullTreeRes = gitHubRepository.getFullTree(owner, repo, treeShaRes.getOrThrow())
            if (fullTreeRes.isFailure) {
                throw fullTreeRes.exceptionOrNull() ?: GitHubApiException(ApiErrorType.SERVER_ERROR, message = "Failed to fetch full remote tree")
            }
            for (item in fullTreeRes.getOrThrow()) {
                if (item.type == "blob") {
                    remoteMap[item.path] = item
                }
            }
        } else {
            val ex = headShaRes.exceptionOrNull()
            if (ex is GitHubApiException && ex.errorType == ApiErrorType.NOT_FOUND) {
                // Legitimate new branch - remote tree is empty
            } else if (ex != null) {
                throw ex
            }
        }

        val diffItems = mutableListOf<DiffItem>()
        var added = 0
        var modified = 0
        var unchanged = 0
        val handledRemotePaths = mutableSetOf<String>()

        for (local in localFiles) {
            val finalPath = buildFinalGitHubPath(normalizedDest, local.relativePath)
            handledRemotePaths.add(finalPath)

            val remoteItem = remoteMap[finalPath]
            if (remoteItem == null) {
                added++
                diffItems.add(
                    DiffItem(
                        localPath = local.relativePath,
                        remotePath = finalPath,
                        changeType = DiffChangeType.ADDED,
                        sizeBytes = local.sizeBytes
                    )
                )
            } else {
                // Compute exact streaming Git blob SHA
                val localSha = try {
                    GitBlobHasher.calculateSha(context, local.uri, local.sizeBytes)
                } catch (e: Exception) {
                    null
                }

                if (localSha != null && localSha.equals(remoteItem.sha, ignoreCase = true)) {
                    unchanged++
                    diffItems.add(
                        DiffItem(
                            localPath = local.relativePath,
                            remotePath = finalPath,
                            changeType = DiffChangeType.UNCHANGED,
                            sizeBytes = local.sizeBytes,
                            reason = "Identical Git blob SHA ($localSha)"
                        )
                    )
                } else {
                    modified++
                    diffItems.add(
                        DiffItem(
                            localPath = local.relativePath,
                            remotePath = finalPath,
                            changeType = DiffChangeType.MODIFIED,
                            sizeBytes = local.sizeBytes,
                            reason = if (localSha != null) "Blob SHA differs: remote=${remoteItem.sha.take(8)}, local=${localSha.take(8)}" else null
                        )
                    )
                }
            }
        }

        // Deleted items if wipe is enabled
        var deleted = 0
        if (wipeMode == WipeMode.FULL_BRANCH) {
            for ((remPath, remItem) in remoteMap) {
                if (!handledRemotePaths.contains(remPath)) {
                    deleted++
                    diffItems.add(
                        DiffItem(
                            localPath = "",
                            remotePath = remPath,
                            changeType = DiffChangeType.DELETED,
                            sizeBytes = remItem.size,
                            reason = "Removed by full-branch wipe"
                        )
                    )
                }
            }
        } else if (wipeMode == WipeMode.DESTINATION) {
            val prefix = if (normalizedDest.isEmpty()) "" else "$normalizedDest/"
            for ((remPath, remItem) in remoteMap) {
                if (remPath.startsWith(prefix) && !handledRemotePaths.contains(remPath)) {
                    deleted++
                    diffItems.add(
                        DiffItem(
                            localPath = "",
                            remotePath = remPath,
                            changeType = DiffChangeType.DELETED,
                            sizeBytes = remItem.size,
                            reason = "Removed by destination-folder wipe"
                        )
                    )
                }
            }
        }

        diffItems.addAll(excludedItems)

        DiffReport(
            added = added,
            modified = modified,
            deleted = deleted,
            unchanged = unchanged,
            excluded = excludedItems.size,
            items = diffItems,
            reviewedHeadSha = reviewedHeadSha
        )
    }

    // 4. Memory-Safe Upload Execution
    fun startUpload(
        owner: String,
        repo: String,
        branch: String,
        destinationDir: String,
        files: List<FileScanItem>,
        commitMessage: String,
        isWipe: Boolean,
        wipeMode: WipeMode = if (isWipe) WipeMode.FULL_BRANCH else WipeMode.NONE,
        reviewedHeadSha: String? = null,
        onCreated: (String) -> Unit
    ): String {
        val transferId = UUID.randomUUID().toString()
        val normalizedDest = normalizeDestination(destinationDir)
        val totalBytes = files.sumOf { it.sizeBytes }

        val entity = TransferEntity(
            id = transferId,
            type = TransferType.UPLOAD.name,
            repoOwner = owner,
            repoName = repo,
            branch = branch,
            sourcePath = "Local Files (${files.size})",
            destPath = if (normalizedDest.isEmpty()) "/" else "/$normalizedDest/",
            status = TransferStatus.QUEUED.name,
            totalFiles = files.size,
            processedFiles = 0,
            totalBytes = totalBytes,
            processedBytes = 0L,
            commitMessage = commitMessage,
            isWipe = isWipe || wipeMode != WipeMode.NONE,
            wipeMode = wipeMode.name,
            reviewedHeadSha = reviewedHeadSha,
            createdAt = System.currentTimeMillis()
        )

        // Create item entities WITHOUT hashing synchronously on the UI thread!
        val itemEntities = files.map { f ->
            val finalPath = buildFinalGitHubPath(normalizedDest, f.relativePath)
            TransferItemEntity(
                transferId = transferId,
                relativePath = f.relativePath,
                githubPath = finalPath,
                sizeBytes = f.sizeBytes,
                status = "PENDING",
                sha = null,
                blobSha = null,
                localUri = f.uri.toString(),
                processedBytes = 0L
            )
        }

        // Persist records first, enqueue WorkManager, and only call onCreated when durable
        engineScope.launch {
            try {
                transferRepository.insertTransfer(entity)
                transferRepository.insertItems(itemEntities)

                val request = OneTimeWorkRequestBuilder<TransferWorker>()
                    .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to transferId))
                    .addTag("transfer_$transferId")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "transfer_$transferId",
                    ExistingWorkPolicy.KEEP,
                    request
                )

                withContext(Dispatchers.Main) {
                    onCreated(transferId)
                }
            } catch (e: Exception) {
                // Preserve failure in a recoverable/visible state if entity was already inserted
                try {
                    val persisted = transferRepository.getTransfer(transferId)
                    if (persisted != null) {
                        transferRepository.updateTransfer(
                            persisted.copy(
                                status = TransferStatus.FAILED.name,
                                errorMessage = "Failed to queue transfer worker: ${e.message ?: e.javaClass.simpleName}"
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }
        return transferId
    }

    private suspend fun executeUploadJob(
        transferId: String,
        initialEntity: TransferEntity? = null,
        onProgress: ((currentFile: String?, processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val persistedEntity = transferRepository.getTransfer(transferId) ?: initialEntity ?: return@withContext
        var entity = persistedEntity
        val reviewedHeadSha = persistedEntity.reviewedHeadSha
        try {
            // STEP 1: PREPARING, HEAD VERIFICATION & HASHING (On IO Thread)
            updateTransferStatus(entity.copy(status = TransferStatus.PREPARING.name))

            val headShaRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (headShaRes.isFailure) {
                failTransfer(entity, "Cannot resolve branch head: ${headShaRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val headCommitSha = headShaRes.getOrThrow()

            // Concurrency Check 1: Verify branch head has not moved since preflight review
            if (reviewedHeadSha != null && !headCommitSha.equals(reviewedHeadSha, ignoreCase = true)) {
                updateTransferStatus(
                    entity.copy(
                        status = TransferStatus.CONFLICT.name,
                        errorMessage = "Repository changed while this transfer was being prepared. No branch overwrite was performed. (Preview HEAD: ${reviewedHeadSha.take(7)}, Current HEAD: ${headCommitSha.take(7)})."
                    )
                )
                return@withContext
            }

            // Fetch remote tree map for unchanged blob reuse
            val remoteMap = mutableMapOf<String, GitTreeItemDto>()
            val treeShaRes = gitHubRepository.getCommitTreeSha(entity.repoOwner, entity.repoName, headCommitSha)
            if (treeShaRes.isFailure) {
                failTransfer(entity, "Failed to resolve commit tree: ${treeShaRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val currentRootTreeSha = treeShaRes.getOrThrow()
            val fullTreeRes = gitHubRepository.getFullTree(entity.repoOwner, entity.repoName, currentRootTreeSha)
            if (fullTreeRes.isFailure) {
                failTransfer(entity, "Failed to retrieve full remote tree: ${fullTreeRes.exceptionOrNull()?.message}")
                return@withContext
            }
            for (item in fullTreeRes.getOrThrow()) {
                if (item.type == "blob") {
                    remoteMap[item.path] = item
                }
            }

            // Compute missing local hashes on background thread
            val itemsToProcess = transferRepository.getItemsForTransferSync(transferId).toMutableList()
            for ((idx, item) in itemsToProcess.withIndex()) {
                if (pausedTransfers.contains(transferId) || !coroutineContext.isActive) {
                    updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
                    return@withContext
                }
                if (item.sha == null && item.localUri != null) {
                    try {
                        val sha = GitBlobHasher.calculateSha(context, Uri.parse(item.localUri), item.sizeBytes)
                        val updated = item.copy(sha = sha)
                        itemsToProcess[idx] = updated
                        transferRepository.updateItem(updated)
                    } catch (e: Exception) {
                        // Keep sha null, will upload normally
                    }
                }
            }

            val wipeMode = try {
                WipeMode.valueOf(entity.wipeMode)
            } catch (_: Exception) {
                if (entity.isWipe) WipeMode.FULL_BRANCH else WipeMode.NONE
            }

            // Base tree for tree creation: null if FULL_BRANCH wipe, otherwise current root tree
            val baseTreeSha = if (wipeMode == WipeMode.FULL_BRANCH) null else currentRootTreeSha

            // STEP 2: UPLOADING BLOBS (Memory-Safe Streaming with Carry Buffer)
            updateTransferStatus(entity.copy(status = TransferStatus.UPLOADING.name))
            val treeEntries = mutableListOf<CreateTreeEntryDto>()
            val handledPaths = mutableSetOf<String>()

            var processedFiles = 0
            var processedBytes = 0L
            var lastSpeedTimestamp = System.currentTimeMillis()
            var lastSampleBytes = 0L
            var smoothedSpeed = 0.0

            for (item in itemsToProcess) {
                if (pausedTransfers.contains(transferId) || !coroutineContext.isActive) {
                    updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
                    return@withContext
                }

                handledPaths.add(item.githubPath)

                // If item was already successfully uploaded (e.g. from previous run / resume)
                if (item.status == "SUCCESS" && !item.blobSha.isNullOrEmpty()) {
                    treeEntries.add(
                        CreateTreeEntryDto(
                            path = item.githubPath,
                            mode = "100644",
                            type = "blob",
                            sha = item.blobSha
                        )
                    )
                    processedFiles++
                    processedBytes += item.sizeBytes
                    continue
                }

                // Check if file on GitHub is already identical in SHA and size (blob reuse!)
                val remoteExisting = remoteMap[item.githubPath]
                if (!entity.isWipe && remoteExisting != null && !item.sha.isNullOrEmpty() && item.sha.equals(remoteExisting.sha, ignoreCase = true)) {
                    treeEntries.add(
                        CreateTreeEntryDto(
                            path = item.githubPath,
                            mode = "100644",
                            type = "blob",
                            sha = remoteExisting.sha
                        )
                    )
                    transferRepository.updateItem(
                        item.copy(status = "SUCCESS", blobSha = remoteExisting.sha, processedBytes = item.sizeBytes, errorMessage = null)
                    )
                    processedFiles++
                    processedBytes += item.sizeBytes
                    continue
                }

                // Update current file status
                entity = entity.copy(
                    currentFile = item.relativePath,
                    processedFiles = processedFiles,
                    processedBytes = processedBytes
                )
                transferRepository.updateTransfer(entity)
                transferRepository.updateItem(item.copy(status = "IN_PROGRESS"))

                val fileUri = item.localUri?.let { Uri.parse(it) }
                if (fileUri == null) {
                    transferRepository.updateItem(
                        item.copy(status = "FAILED", errorMessage = "Missing local file URI")
                    )
                    continue
                }

                // Explicit check for local file URI accessibility and permission validity
                val canReadUri = try {
                    val stream = context.contentResolver.openInputStream(fileUri)
                    if (stream != null) {
                        stream.close()
                        true
                    } else {
                        false
                    }
                } catch (sec: SecurityException) {
                    false
                } catch (_: Exception) {
                    false
                }

                if (!canReadUri) {
                    val errMsg = "Cannot access local file: storage permission revoked or file missing"
                    transferRepository.updateItem(
                        item.copy(status = "FAILED", errorMessage = errMsg, retryCount = item.retryCount + 1)
                    )
                    entity = entity.copy(errorMessage = errMsg)
                    transferRepository.updateTransfer(entity)
                    continue
                }

                // Stream file as base64 chunked body directly into OkHttp sink
                var uploadedBlobSha: String? = null
                var attempt = 0
                var lastErr: String? = null

                while (attempt < 3 && uploadedBlobSha == null && coroutineContext.isActive && !pausedTransfers.contains(transferId)) {
                    attempt++
                    try {
                        val streamingBody = StreamingBlobRequestBody(
                            context = context,
                            uri = fileUri,
                            size = item.sizeBytes
                        ) { bytesSent ->
                            val currentProcessed = processedBytes + bytesSent
                            val now = System.currentTimeMillis()
                            val elapsed = (now - lastSpeedTimestamp) / 1000.0
                            if (elapsed >= 0.5) {
                                val delta = currentProcessed - lastSampleBytes
                                val instant = delta / elapsed
                                smoothedSpeed = if (smoothedSpeed == 0.0) instant else (0.7 * instant + 0.3 * smoothedSpeed)
                                transferSpeeds[transferId] = smoothedSpeed.toLong()
                                lastSpeedTimestamp = now
                                lastSampleBytes = currentProcessed
                            }
                        }

                        val blobRes = gitHubRepository.createBlobStream(entity.repoOwner, entity.repoName, streamingBody)
                        if (blobRes.isSuccess) {
                            uploadedBlobSha = blobRes.getOrThrow()
                        } else {
                            val errEx = blobRes.exceptionOrNull()
                            lastErr = errEx?.message
                            if (errEx is GitHubApiException && !errEx.isRetryable) {
                                break
                            }
                            delay(400L * attempt)
                        }
                    } catch (e: Exception) {
                        lastErr = e.localizedMessage
                        delay(400L * attempt)
                    }
                }

                if (uploadedBlobSha != null) {
                    treeEntries.add(
                        CreateTreeEntryDto(
                            path = item.githubPath,
                            mode = "100644",
                            type = "blob",
                            sha = uploadedBlobSha
                        )
                    )
                    transferRepository.updateItem(
                        item.copy(status = "SUCCESS", blobSha = uploadedBlobSha, processedBytes = item.sizeBytes, errorMessage = null)
                    )
                    processedFiles++
                    processedBytes += item.sizeBytes
                } else {
                    transferRepository.updateItem(
                        item.copy(status = "FAILED", errorMessage = lastErr ?: "Failed to upload blob to GitHub", retryCount = item.retryCount + 1)
                    )
                }

                entity = entity.copy(processedFiles = processedFiles, processedBytes = processedBytes)
                transferRepository.updateTransfer(entity)
            }

            // CRITICAL TRANSACTION BOUNDARY: Verify all items succeeded!
            val updatedItems = transferRepository.getItemsForTransferSync(transferId)
            val failedItems = updatedItems.filter { it.status == "FAILED" }
            if (failedItems.isNotEmpty()) {
                val summary = "${failedItems.size} of ${updatedItems.size} files failed to upload. Branch was not modified. Click Retry Failed Files to re-attempt."
                failTransfer(entity.copy(processedFiles = processedFiles, processedBytes = processedBytes), summary)
                return@withContext
            }

            // If WipeMode.DESTINATION: mark unhandled remote files under destination directory for deletion
            if (wipeMode == WipeMode.DESTINATION) {
                val normDest = normalizeDestination(entity.destPath)
                val prefix = if (normDest.isEmpty()) "" else "$normDest/"
                for ((remPath, remItem) in remoteMap) {
                    if (remPath.startsWith(prefix) && !handledPaths.contains(remPath)) {
                        treeEntries.add(
                            CreateTreeEntryDto(
                                path = remPath,
                                mode = remItem.mode ?: "100644",
                                type = "blob",
                                sha = null // null sha explicitly deletes entry in Git Tree API
                            )
                        )
                    }
                }
            }

            if (treeEntries.isEmpty()) {
                failTransfer(entity, "No files to commit.")
                return@withContext
            }

            // STEP 3: COMMITTING
            updateTransferStatus(entity.copy(status = TransferStatus.COMMITTING.name))

            // Create Git tree
            val newTreeRes = gitHubRepository.createTree(
                owner = entity.repoOwner,
                repo = entity.repoName,
                baseTreeSha = baseTreeSha,
                entries = treeEntries
            )
            if (newTreeRes.isFailure) {
                failTransfer(entity, "Failed to create tree: ${newTreeRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val newTreeSha = newTreeRes.getOrThrow()

            // Create Git commit
            val newCommitRes = gitHubRepository.createCommit(
                owner = entity.repoOwner,
                repo = entity.repoName,
                message = entity.commitMessage ?: "Commit via GitHub File Manager",
                treeSha = newTreeSha,
                parentCommitSha = headCommitSha
            )
            if (newCommitRes.isFailure) {
                failTransfer(entity, "Failed to create commit: ${newCommitRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val newCommitSha = newCommitRes.getOrThrow()

            // Concurrency Check 2: Verify branch head hasn't moved concurrently while blobs were being created
            val concurrentCheckRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (concurrentCheckRes.isSuccess && !concurrentCheckRes.getOrThrow().equals(headCommitSha, ignoreCase = true)) {
                updateTransferStatus(
                    entity.copy(
                        status = TransferStatus.CONFLICT.name,
                        errorMessage = "Repository changed while this transfer was being prepared. Remote branch was updated concurrently. No branch overwrite was performed."
                    )
                )
                return@withContext
            }

            // STEP 4: VERIFYING & UPDATING REF
            updateTransferStatus(entity.copy(status = TransferStatus.VERIFYING.name))
            val updateRefRes = gitHubRepository.updateBranchRef(
                owner = entity.repoOwner,
                repo = entity.repoName,
                branch = entity.branch,
                commitSha = newCommitSha,
                force = false
            )
            if (updateRefRes.isFailure) {
                val ex = updateRefRes.exceptionOrNull()
                val err = ex?.message ?: "Failed to update branch reference"
                val isConflict = (ex is GitHubApiException && ex.errorType == ApiErrorType.CONFLICT) ||
                    err.contains("409") ||
                    err.contains("conflict", ignoreCase = true) ||
                    err.contains("422") ||
                    err.contains("not a fast", ignoreCase = true) ||
                    err.contains("cannot be updated", ignoreCase = true)
                if (isConflict) {
                    updateTransferStatus(
                        entity.copy(
                            status = TransferStatus.CONFLICT.name,
                            errorMessage = "Repository changed while this transfer was being prepared. Reference update rejected due to concurrent changes. No branch overwrite was performed."
                        )
                    )
                } else {
                    failTransfer(entity, err)
                }
                return@withContext
            }

            // Concurrency Check 3: Verify branch head actually points to newly created commit
            val verifyHeadRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (verifyHeadRes.isFailure || !verifyHeadRes.getOrThrow().equals(newCommitSha, ignoreCase = true)) {
                updateTransferStatus(
                    entity.copy(
                        status = TransferStatus.CONFLICT.name,
                        errorMessage = "Branch verification failed after update: expected HEAD $newCommitSha but found ${verifyHeadRes.getOrNull()}. Repository may have been modified concurrently."
                    )
                )
                return@withContext
            }

            // Mark completed
            updateTransferStatus(
                entity.copy(
                    status = TransferStatus.COMPLETED.name,
                    completedAt = System.currentTimeMillis(),
                    currentFile = null,
                    processedFiles = updatedItems.size,
                    processedBytes = entity.totalBytes
                )
            )
        } catch (e: CancellationException) {
            if (pausedTransfers.contains(transferId)) {
                updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
            } else {
                updateTransferStatus(entity.copy(status = TransferStatus.CANCELLED.name))
            }
        } catch (e: Exception) {
            failTransfer(entity, "Unexpected error: ${e.localizedMessage}")
        } finally {
            activeJobs.remove(transferId)
            transferSpeeds.remove(transferId)
        }
    }

    // 5. Resumability & Retry Engine
    fun resumeTransfer(transferId: String) {
        pausedTransfers.remove(transferId)

        engineScope.launch {
            val entity = transferRepository.getTransfer(transferId) ?: return@launch
            updateTransferStatus(entity.copy(status = TransferStatus.QUEUED.name))
            try {
                val request = OneTimeWorkRequestBuilder<TransferWorker>()
                    .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to transferId))
                    .addTag("transfer_$transferId")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "transfer_$transferId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
            } catch (_: Exception) {}
        }
    }

    fun retryTransfer(transferId: String, failedOnly: Boolean = true) {
        pausedTransfers.remove(transferId)

        engineScope.launch {
            val entity = transferRepository.getTransfer(transferId) ?: return@launch
            if (failedOnly) {
                transferRepository.resetFailedItems(transferId)
            } else {
                transferRepository.resetAllItems(transferId)
            }
            updateTransferStatus(entity.copy(status = TransferStatus.QUEUED.name, errorMessage = null))
            try {
                val request = OneTimeWorkRequestBuilder<TransferWorker>()
                    .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to transferId))
                    .addTag("transfer_$transferId")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "transfer_$transferId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
            } catch (_: Exception) {}
        }
    }

    fun pauseTransfer(transferId: String) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork("transfer_$transferId")
        } catch (_: Exception) {}
        pausedTransfers.add(transferId)
        activeJobs[transferId]?.cancel()
        engineScope.launch {
            transferRepository.getTransfer(transferId)?.let {
                transferRepository.updateTransfer(it.copy(status = TransferStatus.PAUSED.name))
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork("transfer_$transferId")
        } catch (_: Exception) {}
        pausedTransfers.remove(transferId)
        activeJobs[transferId]?.cancel()
        engineScope.launch {
            transferRepository.getTransfer(transferId)?.let {
                transferRepository.updateTransfer(it.copy(status = TransferStatus.CANCELLED.name))
            }
        }
    }

    // 6. Download Execution (Single file, Recursive directory, Entire repository ZIP)
    fun startDownload(
        owner: String,
        repo: String,
        branch: String,
        remotePath: String, // file or folder or "" for whole repo
        config: DownloadConfig,
        onCreated: (String) -> Unit
    ): String {
        val transferId = UUID.randomUUID().toString()

        val sourceDescription = when {
            config.selectedPaths.isNotEmpty() -> "${config.selectedPaths.size} selected items"
            remotePath.isEmpty() -> "Entire Repository ($branch)"
            else -> remotePath
        }

        val entity = TransferEntity(
            id = transferId,
            type = TransferType.DOWNLOAD.name,
            repoOwner = owner,
            repoName = repo,
            branch = branch,
            sourcePath = sourceDescription,
            destPath = config.destinationTreeUri.toString(),
            status = TransferStatus.QUEUED.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 0L,
            processedBytes = 0L,
            createdAt = System.currentTimeMillis(),
            asZip = config.asZip,
            overwritePolicy = config.overwritePolicy.name,
            preserveStructure = config.preserveStructure,
            createRepoFolder = config.createRepoFolder,
            downloadScope = config.downloadScope.name
        )

        // Persist record first, enqueue WorkManager, and only call onCreated when durable
        engineScope.launch {
            try {
                transferRepository.insertTransfer(entity)

                val request = OneTimeWorkRequestBuilder<TransferWorker>()
                    .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to transferId))
                    .addTag("transfer_$transferId")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "transfer_$transferId",
                    ExistingWorkPolicy.KEEP,
                    request
                )

                withContext(Dispatchers.Main) {
                    onCreated(transferId)
                }
            } catch (e: Exception) {
                // Preserve failure in a recoverable/visible state if entity was already inserted
                try {
                    val persisted = transferRepository.getTransfer(transferId)
                    if (persisted != null) {
                        transferRepository.updateTransfer(
                            persisted.copy(
                                status = TransferStatus.FAILED.name,
                                errorMessage = "Failed to queue transfer worker: ${e.message ?: e.javaClass.simpleName}"
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }
        return transferId
    }

    suspend fun executeTransferById(
        transferId: String,
        onProgress: ((currentFile: String?, processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val entity = transferRepository.getTransfer(transferId) ?: return@withContext
        if (entity.status == TransferStatus.PAUSED.name || entity.status == TransferStatus.CANCELLED.name) {
            return@withContext
        }
        executionMutex.withLock {
            val freshEntity = transferRepository.getTransfer(transferId) ?: return@withLock
            if (freshEntity.status == TransferStatus.PAUSED.name || freshEntity.status == TransferStatus.CANCELLED.name) {
                return@withLock
            }
            val currentJob = coroutineContext[Job]
            if (currentJob != null) {
                activeJobs[transferId] = currentJob
            }
            try {
                if (freshEntity.type == TransferType.UPLOAD.name) {
                    executeUploadJob(transferId, freshEntity, onProgress)
                } else if (freshEntity.type == TransferType.DOWNLOAD.name) {
                    executeDownloadJob(transferId, freshEntity, null, null, onProgress)
                }
            } finally {
                activeJobs.remove(transferId)
            }
        }
    }

    private fun verifyZipIntegrity(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(java.io.BufferedInputStream(stream)).use { zis ->
                    var entryCount = 0
                    var entry = zis.nextEntry
                    val buf = ByteArray(4096)
                    while (entry != null) {
                        entryCount++
                        while (zis.read(buf) != -1) {}
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    entryCount > 0
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun executeDownloadJob(
        transferId: String,
        initialEntity: TransferEntity? = null,
        initialRemotePath: String? = null,
        initialConfig: DownloadConfig? = null,
        onProgress: ((currentFile: String?, processedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val persistedEntity = transferRepository.getTransfer(transferId) ?: initialEntity ?: return@withContext
        var entity = persistedEntity
        try {
            updateTransferStatus(entity.copy(status = TransferStatus.PREPARING.name))

            val destinationUri = Uri.parse(entity.destPath)
            val destDoc = DocumentFile.fromTreeUri(context, destinationUri)
                ?: DocumentFile.fromSingleUri(context, destinationUri)

            if (destDoc == null || !destDoc.canWrite()) {
                failTransfer(entity, "Cannot access local destination storage: permission revoked or directory missing")
                return@withContext
            }

            val remotePath = initialRemotePath ?: (if (entity.sourcePath.startsWith("Entire Repository")) "" else entity.sourcePath)
            val isZip = initialConfig?.asZip ?: entity.asZip
            val policy = initialConfig?.overwritePolicy ?: try { OverwritePolicy.valueOf(entity.overwritePolicy) } catch (_: Exception) { OverwritePolicy.OVERWRITE }

            // Discover and populate items if not already present
            var existingItems = transferRepository.getItemsForTransferSync(transferId)
            if (existingItems.isEmpty()) {
                if (remotePath.isEmpty() && isZip) {
                    val zipFileName = "${entity.repoName}-${entity.branch}.zip"
                    val item = TransferItemEntity(
                        transferId = transferId,
                        relativePath = zipFileName,
                        githubPath = "",
                        sizeBytes = 0L,
                        status = "PENDING"
                    )
                    transferRepository.insertItems(listOf(item))
                } else {
                    // Query GitHub for target blobs
                    val headShaRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
                    if (headShaRes.isFailure) {
                        failTransfer(entity, "Cannot resolve branch head for download: ${headShaRes.exceptionOrNull()?.message}")
                        return@withContext
                    }
                    val treeShaRes = gitHubRepository.getCommitTreeSha(entity.repoOwner, entity.repoName, headShaRes.getOrThrow())
                    if (treeShaRes.isFailure) {
                        failTransfer(entity, "Cannot resolve commit tree: ${treeShaRes.exceptionOrNull()?.message}")
                        return@withContext
                    }
                    val fullTreeRes = gitHubRepository.getFullTree(entity.repoOwner, entity.repoName, treeShaRes.getOrThrow())
                    if (fullTreeRes.isFailure) {
                        failTransfer(entity, "Cannot retrieve repository tree: ${fullTreeRes.exceptionOrNull()?.message}")
                        return@withContext
                    }

                    val allBlobs = fullTreeRes.getOrThrow().filter { it.type == "blob" }
                    val cleanRemote = remotePath.trimStart('/').trimEnd('/')
                    val explicitSelectedPaths = initialConfig?.selectedPaths ?: emptyList()

                    val matchedBlobs = if (explicitSelectedPaths.isNotEmpty()) {
                        val normalizedSelected = explicitSelectedPaths.map { it.trimStart('/').trimEnd('/') }.toSet()
                        allBlobs.filter { blob ->
                            normalizedSelected.contains(blob.path) || normalizedSelected.any { sel -> blob.path.startsWith("$sel/") }
                        }
                    } else if (cleanRemote.isEmpty()) {
                        allBlobs
                    } else {
                        allBlobs.filter { it.path == cleanRemote || it.path.startsWith("$cleanRemote/") }
                    }

                    if (matchedBlobs.isEmpty()) {
                        // Fallback to directory contents API
                        val contentsRes = gitHubRepository.getDirectoryContents(entity.repoOwner, entity.repoName, cleanRemote, entity.branch)
                        if (contentsRes.isSuccess) {
                            val items = contentsRes.getOrThrow().filter { it.isFile }.map {
                                TransferItemEntity(
                                    transferId = transferId,
                                    relativePath = it.name,
                                    githubPath = it.path,
                                    sizeBytes = it.size,
                                    status = "PENDING",
                                    sha = it.sha
                                )
                            }
                            transferRepository.insertItems(items)
                        } else {
                            failTransfer(entity, "Target path '$remotePath' not found in repository.")
                            return@withContext
                        }
                    } else {
                        val items = matchedBlobs.map { blob ->
                            val rel = if (explicitSelectedPaths.isNotEmpty()) {
                                // For multi-selected items, if an item is a single file directly selected, use its filename
                                val directMatch = explicitSelectedPaths.find { it.trimStart('/') == blob.path }
                                if (directMatch != null) {
                                    blob.path.substringAfterLast('/')
                                } else {
                                    // It belongs to a selected subfolder, preserve path under that folder
                                    val parentFolder = explicitSelectedPaths.find { blob.path.startsWith(it.trimStart('/') + "/") }
                                    if (parentFolder != null) {
                                        blob.path.removePrefix(parentFolder.trimStart('/') + "/")
                                    } else {
                                        blob.path
                                    }
                                }
                            } else if (cleanRemote.isEmpty()) {
                                blob.path
                            } else if (blob.path == cleanRemote) {
                                // Single selected file: relative path is just its file name
                                cleanRemote.substringAfterLast('/')
                            } else {
                                blob.path.removePrefix("$cleanRemote/").removePrefix("/")
                            }
                            TransferItemEntity(
                                transferId = transferId,
                                relativePath = rel,
                                githubPath = blob.path,
                                sizeBytes = blob.size,
                                status = "PENDING",
                                sha = blob.sha
                            )
                        }
                        transferRepository.insertItems(items)
                    }
                }

                existingItems = transferRepository.getItemsForTransferSync(transferId)
                val totalBytes = existingItems.sumOf { it.sizeBytes }
                entity = entity.copy(totalFiles = existingItems.size, totalBytes = totalBytes)
                transferRepository.updateTransfer(entity)
            }

            // Case A: Whole repository ZIP download
            if (remotePath.isEmpty() && isZip) {
                updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name, currentFile = "Archive.zip"))
                val zipRes = gitHubRepository.downloadZipball(entity.repoOwner, entity.repoName, entity.branch)
                if (zipRes.isFailure) {
                    failTransfer(entity, "Failed to download zipball: ${zipRes.exceptionOrNull()?.message}")
                    return@withContext
                }

                val body = zipRes.getOrThrow()
                val expectedContentLength = body.contentLength().takeIf { it > 0 } ?: 0L
                val zipFileName = "${entity.repoName}-${entity.branch}.zip"

                var targetFile = destDoc.findFile(zipFileName)
                var bytesCopied = 0L
                if (targetFile != null && policy == OverwritePolicy.SKIP) {
                    // skip
                } else {
                    if (targetFile != null && policy == OverwritePolicy.OVERWRITE) {
                        targetFile.delete()
                    }
                    val finalName = if (targetFile != null && policy == OverwritePolicy.KEEP_BOTH) {
                        resolveUniqueFileName(destDoc, zipFileName)
                    } else zipFileName

                    val partFileName = "$finalName.part_${UUID.randomUUID().toString().take(6)}"
                    val partFileDoc = destDoc.createFile("application/zip", partFileName)
                        ?: run {
                            failTransfer(entity, "Cannot create local zip file in destination")
                            return@withContext
                        }

                    var downloadSuccess = false
                    try {
                        val outputStream = context.contentResolver.openOutputStream(partFileDoc.uri)
                            ?: throw IOException("Failed to open output stream for destination ZIP: null stream returned")

                        outputStream.use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                var lastUpdate = System.currentTimeMillis()
                                while (input.read(buffer).also { read = it } != -1) {
                                    if (pausedTransfers.contains(transferId) || !coroutineContext.isActive) {
                                        throw CancellationException("Download paused")
                                    }
                                    out.write(buffer, 0, read)
                                    bytesCopied += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate >= 250) {
                                        entity = entity.copy(
                                            processedBytes = bytesCopied,
                                            totalBytes = if (expectedContentLength > 0) expectedContentLength else 0L
                                        )
                                        transferRepository.updateTransfer(entity)
                                        onProgress?.invoke(finalName, bytesCopied, expectedContentLength)
                                        lastUpdate = now
                                    }
                                }
                            }
                        }

                        // Content length check if expected length is known
                        if (expectedContentLength > 0 && bytesCopied != expectedContentLength) {
                            throw IOException("ZIP content length mismatch: expected $expectedContentLength bytes, received $bytesCopied bytes")
                        }

                        // Verify archive integrity before renaming and completing
                        if (!verifyZipIntegrity(partFileDoc.uri)) {
                            partFileDoc.delete()
                            failTransfer(entity, "Downloaded ZIP file failed archive integrity verification.")
                            return@withContext
                        }

                        val renameSuccess = partFileDoc.renameTo(finalName)
                        if (!renameSuccess) {
                            throw IOException("Failed to rename temporary ZIP file to '$finalName'")
                        }
                        downloadSuccess = true
                    } catch (e: Exception) {
                        partFileDoc.delete()
                        if (e is CancellationException) throw e
                        failTransfer(entity, "Failed to download ZIP: ${e.localizedMessage}")
                        return@withContext
                    }
                }

                existingItems.firstOrNull()?.let {
                    transferRepository.updateItem(it.copy(status = "SUCCESS", processedBytes = bytesCopied))
                }

                updateTransferStatus(
                    entity.copy(
                        status = TransferStatus.COMPLETED.name,
                        completedAt = System.currentTimeMillis(),
                        currentFile = null,
                        processedBytes = bytesCopied,
                        totalBytes = if (expectedContentLength > 0) expectedContentLength else bytesCopied
                    )
                )
                return@withContext
            }

            // Case B: Individual files or recursive hierarchy download
            updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name))

            val shouldCreateRepoFolder = initialConfig?.createRepoFolder ?: entity.createRepoFolder
            val shouldPreserveStructure = initialConfig?.preserveStructure ?: entity.preserveStructure

            val effectiveRoot: DocumentFile = if (shouldCreateRepoFolder) {
                destDoc.findFile(entity.repoName)?.takeIf { it.isDirectory }
                    ?: destDoc.createDirectory(entity.repoName)
                    ?: run {
                        val err = "Failed to create repository folder '${entity.repoName}' in destination storage."
                        failTransfer(entity, err)
                        return@withContext
                    }
            } else {
                destDoc
            }

            val dirCache = mutableMapOf<String, DocumentFile>()
            dirCache[""] = effectiveRoot

            var processedFiles = 0
            var processedBytes = 0L
            var lastSpeedTimestamp = System.currentTimeMillis()
            var lastSampleBytes = 0L
            var smoothedSpeed = 0.0

            for (item in existingItems) {
                if (pausedTransfers.contains(transferId) || !coroutineContext.isActive) {
                    updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
                    return@withContext
                }

                if (item.status == "SUCCESS" || item.status == "SKIPPED") {
                    processedFiles++
                    processedBytes += item.sizeBytes
                    continue
                }

                // Zip Slip & Traversal Protection: verify path does not escape
                if (ZipSecurityUtils.isPathTraversal(item.relativePath)) {
                    transferRepository.updateItem(
                        item.copy(status = "FAILED", errorMessage = "Illegal path traversal in filename: ${item.relativePath}")
                    )
                    continue
                }
                val normalizedRel = File(item.relativePath).normalize().path.replace('\\', '/')

                entity = entity.copy(currentFile = item.relativePath, processedFiles = processedFiles, processedBytes = processedBytes)
                transferRepository.updateTransfer(entity)
                transferRepository.updateItem(item.copy(status = "IN_PROGRESS"))
                onProgress?.invoke(item.relativePath, processedBytes, entity.totalBytes)

                val parentPath = if (shouldPreserveStructure && normalizedRel.contains('/')) normalizedRel.substringBeforeLast('/') else ""
                var fileName = normalizedRel.substringAfterLast('/')
                val parentDir = getOrCreateSubDir(effectiveRoot, parentPath, dirCache)
                if (parentDir == null) {
                    val err = "Failed to create directory structure for '$parentPath'. Aborting download."
                    transferRepository.updateItem(item.copy(status = "FAILED", errorMessage = err))
                    failTransfer(entity.copy(processedFiles = processedFiles, processedBytes = processedBytes), err)
                    return@withContext
                }

                val existing = parentDir.findFile(fileName)
                if (existing != null) {
                    val existingLength = existing.length()
                    var isExistingValid = false
                    if (item.sha != null && item.sha.isNotEmpty()) {
                        try {
                            val computedSha = GitBlobHasher.calculateSha(context, existing.uri, existingLength)
                            if (computedSha.equals(item.sha, ignoreCase = true)) {
                                isExistingValid = true
                            }
                        } catch (_: Exception) {}
                    } else if (item.sizeBytes > 0 && existingLength == item.sizeBytes) {
                        isExistingValid = true
                    }

                    if (isExistingValid) {
                        // Target already exists and is fully valid (e.g. crash recovery or prior download)
                        transferRepository.updateItem(item.copy(status = "SUCCESS", processedBytes = existingLength, errorMessage = null))
                        processedFiles++
                        processedBytes += existingLength
                        entity = entity.copy(processedFiles = processedFiles, processedBytes = processedBytes)
                        transferRepository.updateTransfer(entity)
                        continue
                    }

                    // Existing file is invalid or differing
                    if (policy == OverwritePolicy.SKIP) {
                        transferRepository.updateItem(item.copy(status = "SKIPPED", processedBytes = item.sizeBytes))
                        processedFiles++
                        processedBytes += item.sizeBytes
                        entity = entity.copy(processedFiles = processedFiles, processedBytes = processedBytes)
                        transferRepository.updateTransfer(entity)
                        continue
                    } else if (policy == OverwritePolicy.OVERWRITE) {
                        existing.delete()
                    } else if (policy == OverwritePolicy.KEEP_BOTH) {
                        fileName = resolveUniqueFileName(parentDir, fileName)
                    }
                }

                val rawUrl = buildRawDownloadUrl(entity.repoOwner, entity.repoName, entity.branch, item.githubPath)
                val rawRes = gitHubRepository.downloadRaw(rawUrl)

                if (rawRes.isSuccess) {
                    val responseBody = rawRes.getOrThrow()
                    val expectedContentLength = responseBody.contentLength().takeIf { it >= 0 } ?: -1L
                    val partFileName = "$fileName.part_${UUID.randomUUID().toString().take(6)}"
                    val partFileDoc = parentDir.createFile("application/octet-stream", partFileName)

                    if (partFileDoc == null) {
                        transferRepository.updateItem(
                            item.copy(status = "FAILED", errorMessage = "Failed to create local destination file")
                        )
                        continue
                    }

                    var downloadSuccess = false
                    var fileItemBytes = 0L
                    try {
                        val outputStream = context.contentResolver.openOutputStream(partFileDoc.uri)
                            ?: throw IOException("Failed to open output stream for destination: null stream returned")

                        outputStream.use { out ->
                            responseBody.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    if (pausedTransfers.contains(transferId) || !coroutineContext.isActive) {
                                        throw CancellationException("Download paused")
                                    }
                                    out.write(buffer, 0, read)
                                    fileItemBytes += read
                                    processedBytes += read
                                    val now = System.currentTimeMillis()
                                    val elapsed = (now - lastSpeedTimestamp) / 1000.0
                                    if (elapsed >= 0.5) {
                                        val delta = processedBytes - lastSampleBytes
                                        val instant = delta / elapsed
                                        smoothedSpeed = if (smoothedSpeed == 0.0) instant else (0.7 * instant + 0.3 * smoothedSpeed)
                                        transferSpeeds[transferId] = smoothedSpeed.toLong()
                                        lastSpeedTimestamp = now
                                        lastSampleBytes = processedBytes
                                    }
                                }
                            }
                        }

                        // Verify expected content length if known
                        if (expectedContentLength >= 0 && fileItemBytes != expectedContentLength) {
                            throw IOException("Content length mismatch: expected $expectedContentLength bytes, received $fileItemBytes bytes")
                        }
                        if (item.sizeBytes > 0 && expectedContentLength < 0 && fileItemBytes != item.sizeBytes) {
                            throw IOException("Content size mismatch: expected ${item.sizeBytes} bytes from tree metadata, received $fileItemBytes bytes")
                        }

                        // Verify Git blob SHA against downloaded bytes
                        if (item.sha != null && item.sha.isNotEmpty()) {
                            val computedSha = GitBlobHasher.calculateSha(context, partFileDoc.uri, fileItemBytes)
                            if (!computedSha.equals(item.sha, ignoreCase = true)) {
                                throw IOException("Git blob SHA mismatch: expected ${item.sha}, computed $computedSha")
                            }
                        }

                        // Rename part file to final file name
                        val renameSuccess = partFileDoc.renameTo(fileName)
                        if (!renameSuccess) {
                            throw IOException("Failed to rename temporary file to '$fileName'")
                        }
                        downloadSuccess = true
                    } catch (e: Exception) {
                        partFileDoc.delete()
                        if (e is CancellationException) throw e
                        transferRepository.updateItem(
                            item.copy(status = "FAILED", errorMessage = e.localizedMessage)
                        )
                    }

                    if (downloadSuccess) {
                        transferRepository.updateItem(
                            item.copy(status = "SUCCESS", processedBytes = fileItemBytes, errorMessage = null)
                        )
                        processedFiles++
                    }
                } else {
                    transferRepository.updateItem(
                        item.copy(status = "FAILED", errorMessage = rawRes.exceptionOrNull()?.message ?: "Failed to download raw file")
                    )
                }

                entity = entity.copy(processedFiles = processedFiles, processedBytes = processedBytes)
                transferRepository.updateTransfer(entity)
            }

            // Check if any items failed
            val endItems = transferRepository.getItemsForTransferSync(transferId)
            val failedList = endItems.filter { it.status == "FAILED" }
            if (failedList.isNotEmpty()) {
                val errSummary = "${failedList.size} of ${endItems.size} files failed to download. Click Retry Failed Files to re-attempt."
                failTransfer(entity.copy(processedFiles = processedFiles, processedBytes = processedBytes), errSummary)
            } else {
                updateTransferStatus(
                    entity.copy(
                        status = TransferStatus.COMPLETED.name,
                        completedAt = System.currentTimeMillis(),
                        currentFile = null,
                        processedFiles = endItems.size,
                        processedBytes = entity.totalBytes
                    )
                )
            }
        } catch (e: CancellationException) {
            if (pausedTransfers.contains(transferId)) {
                updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
            } else {
                updateTransferStatus(entity.copy(status = TransferStatus.CANCELLED.name))
            }
        } catch (e: Exception) {
            failTransfer(entity, "Download failed: ${e.localizedMessage}")
        } finally {
            activeJobs.remove(transferId)
            transferSpeeds.remove(transferId)
        }
    }

    private fun buildRawDownloadUrl(owner: String, repo: String, branch: String, path: String): String {
        val encodedBranch = branch.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val encodedPath = path.trimStart('/').split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "https://raw.githubusercontent.com/$owner/$repo/$encodedBranch/$encodedPath"
    }

    private fun resolveUniqueFileName(parentDir: DocumentFile, originalName: String): String {
        if (parentDir.findFile(originalName) == null) return originalName
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex > 0) originalName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = "$baseName ($counter)$extension"
            if (parentDir.findFile(candidate) == null) {
                return candidate
            }
            counter++
        }
    }

    private fun getOrCreateSubDir(
        root: DocumentFile,
        path: String,
        cache: MutableMap<String, DocumentFile>
    ): DocumentFile? {
        if (path.isEmpty()) return root
        cache[path]?.let { return it }

        val parts = path.split('/').filter { it.isNotEmpty() }
        var current = root
        var currentPath = ""

        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val cached = cache[currentPath]
            if (cached != null) {
                current = cached
            } else {
                val nextDir = current.findFile(part)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(part)
                    ?: return null
                cache[currentPath] = nextDir
                current = nextDir
            }
        }
        return current
    }

    private suspend fun updateTransferStatus(entity: TransferEntity) {
        transferRepository.updateTransfer(entity)
    }

    private suspend fun failTransfer(entity: TransferEntity, reason: String) {
        updateTransferStatus(
            entity.copy(
                status = TransferStatus.FAILED.name,
                errorMessage = reason,
                completedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun streamCopy(input: InputStream, output: OutputStream, onProgress: suspend (Long) -> Unit) {
        val buffer = ByteArray(8192)
        var read: Int
        var total = 0L
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            total += read
            onProgress(total)
        }
    }

    fun normalizeDestination(dest: String): String {
        return dest.trim().trimStart('/').trimEnd('/').replace('\\', '/')
    }

    fun buildFinalGitHubPath(normalizedDest: String, relativePath: String): String {
        val cleanRel = relativePath.trimStart('/').replace('\\', '/')
        return if (normalizedDest.isEmpty()) {
            cleanRel
        } else {
            "$normalizedDest/$cleanRel"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
