package com.example.domain.engine

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.entity.TransferEntity
import com.example.data.local.entity.TransferItemEntity
import com.example.data.remote.dto.CreateTreeEntryDto
import com.example.data.remote.dto.GitHubContentDto
import com.example.data.remote.dto.GitTreeItemDto
import com.example.data.repository.GitHubRepository
import com.example.data.repository.TransferRepository
import com.example.domain.model.*
import kotlinx.coroutines.*
import java.io.File
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
            if (treeShaRes.isSuccess) {
                val fullTreeRes = gitHubRepository.getFullTree(owner, repo, treeShaRes.getOrThrow())
                if (fullTreeRes.isSuccess) {
                    for (item in fullTreeRes.getOrThrow()) {
                        if (item.type == "blob") {
                            remoteMap[item.path] = item
                        }
                    }
                }
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
            isWipe = isWipe,
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

        onCreated(transferId)

        val job = engineScope.launch {
            transferRepository.insertTransfer(entity)
            transferRepository.insertItems(itemEntities)
            executeUploadJob(transferId, entity, reviewedHeadSha)
        }
        activeJobs[transferId] = job
        return transferId
    }

    private suspend fun executeUploadJob(
        transferId: String,
        initialEntity: TransferEntity,
        reviewedHeadSha: String? = null
    ) = withContext(Dispatchers.IO) {
        var entity = initialEntity
        try {
            // STEP 1: PREPARING, HEAD VERIFICATION & HASHING (On IO Thread)
            updateTransferStatus(entity.copy(status = TransferStatus.PREPARING.name))

            val headShaRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (headShaRes.isFailure) {
                failTransfer(entity, "Cannot resolve branch head: ${headShaRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val headCommitSha = headShaRes.getOrThrow()

            // Point 21: Verify branch head has not moved since preflight review
            if (reviewedHeadSha != null && !headCommitSha.equals(reviewedHeadSha, ignoreCase = true)) {
                failTransfer(
                    entity,
                    "Remote branch moved since preview (reviewed: ${reviewedHeadSha.take(7)}, current: ${headCommitSha.take(7)}). Upload cancelled to protect against concurrent overwrites. Please calculate a fresh diff."
                )
                return@withContext
            }

            // Fetch remote tree map for unchanged blob reuse
            val remoteMap = mutableMapOf<String, GitTreeItemDto>()
            val treeShaRes = gitHubRepository.getCommitTreeSha(entity.repoOwner, entity.repoName, headCommitSha)
            var currentRootTreeSha: String? = null
            if (treeShaRes.isSuccess) {
                currentRootTreeSha = treeShaRes.getOrThrow()
                val fullTreeRes = gitHubRepository.getFullTree(entity.repoOwner, entity.repoName, currentRootTreeSha)
                if (fullTreeRes.isSuccess) {
                    for (item in fullTreeRes.getOrThrow()) {
                        if (item.type == "blob") {
                            remoteMap[item.path] = item
                        }
                    }
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

            // Base tree for tree creation
            val baseTreeSha = if (entity.isWipe) null else currentRootTreeSha

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
                            lastErr = blobRes.exceptionOrNull()?.message
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

            // Verify branch head hasn't moved concurrently while blobs were being created
            val concurrentCheckRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (concurrentCheckRes.isSuccess && !concurrentCheckRes.getOrThrow().equals(headCommitSha, ignoreCase = true)) {
                failTransfer(
                    entity,
                    "Remote branch was updated concurrently during upload. Commit aborted to protect repository history."
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
                failTransfer(entity, "Failed to update branch reference: ${updateRefRes.exceptionOrNull()?.message}")
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
        if (activeJobs[transferId]?.isActive == true) return
        pausedTransfers.remove(transferId)

        val job = engineScope.launch {
            val entity = transferRepository.getTransfer(transferId) ?: return@launch
            updateTransferStatus(entity.copy(status = TransferStatus.QUEUED.name))
            if (entity.type == TransferType.UPLOAD.name) {
                executeUploadJob(transferId, entity)
            } else if (entity.type == TransferType.DOWNLOAD.name) {
                executeDownloadJob(transferId, entity)
            }
        }
        activeJobs[transferId] = job
    }

    fun retryTransfer(transferId: String, failedOnly: Boolean = true) {
        if (activeJobs[transferId]?.isActive == true) return
        pausedTransfers.remove(transferId)

        val job = engineScope.launch {
            val entity = transferRepository.getTransfer(transferId) ?: return@launch
            if (failedOnly) {
                transferRepository.resetFailedItems(transferId)
            } else {
                transferRepository.resetAllItems(transferId)
            }
            updateTransferStatus(entity.copy(status = TransferStatus.QUEUED.name, errorMessage = null))
            if (entity.type == TransferType.UPLOAD.name) {
                executeUploadJob(transferId, entity)
            } else if (entity.type == TransferType.DOWNLOAD.name) {
                executeDownloadJob(transferId, entity)
            }
        }
        activeJobs[transferId] = job
    }

    fun pauseTransfer(transferId: String) {
        pausedTransfers.add(transferId)
        activeJobs[transferId]?.cancel()
        engineScope.launch {
            transferRepository.getTransfer(transferId)?.let {
                transferRepository.updateTransfer(it.copy(status = TransferStatus.PAUSED.name))
            }
        }
    }

    fun cancelTransfer(transferId: String) {
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

        val entity = TransferEntity(
            id = transferId,
            type = TransferType.DOWNLOAD.name,
            repoOwner = owner,
            repoName = repo,
            branch = branch,
            sourcePath = if (remotePath.isEmpty()) "Entire Repository ($branch)" else remotePath,
            destPath = config.destinationTreeUri.toString(),
            status = TransferStatus.QUEUED.name,
            totalFiles = 1,
            processedFiles = 0,
            totalBytes = 0L,
            processedBytes = 0L,
            createdAt = System.currentTimeMillis()
        )

        onCreated(transferId)

        val job = engineScope.launch {
            transferRepository.insertTransfer(entity)
            executeDownloadJob(transferId, entity, remotePath, config)
        }
        activeJobs[transferId] = job
        return transferId
    }

    private suspend fun executeDownloadJob(
        transferId: String,
        initialEntity: TransferEntity,
        initialRemotePath: String? = null,
        initialConfig: DownloadConfig? = null
    ) = withContext(Dispatchers.IO) {
        var entity = initialEntity
        try {
            updateTransferStatus(entity.copy(status = TransferStatus.PREPARING.name))

            val destinationUri = Uri.parse(entity.destPath)
            val destDoc = DocumentFile.fromTreeUri(context, destinationUri)
                ?: DocumentFile.fromSingleUri(context, destinationUri)
                ?: run {
                    failTransfer(entity, "Cannot access local destination storage")
                    return@withContext
                }

            val remotePath = initialRemotePath ?: (if (entity.sourcePath.startsWith("Entire Repository")) "" else entity.sourcePath)
            val isZip = initialConfig?.asZip ?: entity.sourcePath.endsWith(".zip")
            val policy = initialConfig?.overwritePolicy ?: OverwritePolicy.OVERWRITE

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
                        failTransfer(entity, "Cannot resolve branch head for download")
                        return@withContext
                    }
                    val treeShaRes = gitHubRepository.getCommitTreeSha(entity.repoOwner, entity.repoName, headShaRes.getOrThrow())
                    val fullTreeRes = if (treeShaRes.isSuccess) {
                        gitHubRepository.getFullTree(entity.repoOwner, entity.repoName, treeShaRes.getOrThrow())
                    } else null

                    val allBlobs = fullTreeRes?.getOrNull()?.filter { it.type == "blob" } ?: emptyList()
                    val cleanRemote = remotePath.trimStart('/').trimEnd('/')

                    val matchedBlobs = if (cleanRemote.isEmpty()) {
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
                            val rel = if (cleanRemote.isEmpty()) blob.path else blob.path.removePrefix("$cleanRemote/").removePrefix("/")
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
                val zipFileName = "${entity.repoName}-${entity.branch}.zip"

                var targetFile = destDoc.findFile(zipFileName)
                if (targetFile != null && policy == OverwritePolicy.SKIP) {
                    // skip
                } else {
                    if (targetFile != null && policy == OverwritePolicy.OVERWRITE) {
                        targetFile.delete()
                    }
                    val finalName = if (targetFile != null && policy == OverwritePolicy.KEEP_BOTH) {
                        resolveUniqueFileName(destDoc, zipFileName)
                    } else zipFileName

                    val created = destDoc.createFile("application/zip", finalName)
                        ?: run {
                            failTransfer(entity, "Cannot create local zip file in destination")
                            return@withContext
                        }

                    context.contentResolver.openOutputStream(created.uri)?.use { out ->
                        body.byteStream().use { input ->
                            streamCopy(input, out) { bytesCopied ->
                                entity = entity.copy(processedBytes = bytesCopied, totalBytes = bytesCopied)
                                transferRepository.updateTransfer(entity)
                            }
                        }
                    }
                }

                existingItems.firstOrNull()?.let {
                    transferRepository.updateItem(it.copy(status = "SUCCESS"))
                }

                updateTransferStatus(
                    entity.copy(
                        status = TransferStatus.COMPLETED.name,
                        completedAt = System.currentTimeMillis(),
                        currentFile = null
                    )
                )
                return@withContext
            }

            // Case B: Individual files or recursive hierarchy download
            updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name))

            val dirCache = mutableMapOf<String, DocumentFile>()
            dirCache[""] = destDoc

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
                val normalizedRel = File(item.relativePath).normalize().path.replace('\\', '/')
                if (item.relativePath.split('/', '\\').any { it == ".." } || normalizedRel.startsWith("../") || normalizedRel == "..") {
                    transferRepository.updateItem(
                        item.copy(status = "FAILED", errorMessage = "Illegal path traversal in filename: ${item.relativePath}")
                    )
                    continue
                }

                entity = entity.copy(currentFile = item.relativePath, processedFiles = processedFiles, processedBytes = processedBytes)
                transferRepository.updateTransfer(entity)
                transferRepository.updateItem(item.copy(status = "IN_PROGRESS"))

                val parentPath = if (normalizedRel.contains('/')) normalizedRel.substringBeforeLast('/') else ""
                var fileName = normalizedRel.substringAfterLast('/')
                val parentDir = getOrCreateSubDir(destDoc, parentPath, dirCache)

                val existing = parentDir.findFile(fileName)
                if (existing != null && policy == OverwritePolicy.SKIP) {
                    transferRepository.updateItem(item.copy(status = "SKIPPED", processedBytes = item.sizeBytes))
                    processedFiles++
                    processedBytes += item.sizeBytes
                    continue
                }

                if (existing != null && policy == OverwritePolicy.OVERWRITE) {
                    existing.delete()
                } else if (existing != null && policy == OverwritePolicy.KEEP_BOTH) {
                    fileName = resolveUniqueFileName(parentDir, fileName)
                }

                val rawUrl = buildRawDownloadUrl(entity.repoOwner, entity.repoName, entity.branch, item.githubPath)
                val rawRes = gitHubRepository.downloadRaw(rawUrl)

                if (rawRes.isSuccess) {
                    val partFileName = "$fileName.part_${UUID.randomUUID().toString().take(6)}"
                    val partFileDoc = parentDir.createFile("application/octet-stream", partFileName)

                    if (partFileDoc == null) {
                        transferRepository.updateItem(
                            item.copy(status = "FAILED", errorMessage = "Failed to create local destination file")
                        )
                        continue
                    }

                    var downloadSuccess = false
                    try {
                        context.contentResolver.openOutputStream(partFileDoc.uri)?.use { out ->
                            rawRes.getOrThrow().byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    if (pausedTransfers.contains(transferId) || !coroutineContext.isActive) {
                                        throw CancellationException("Download paused")
                                    }
                                    out.write(buffer, 0, read)
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
                        // Rename part file to real file
                        partFileDoc.renameTo(fileName)
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
                            item.copy(status = "SUCCESS", processedBytes = item.sizeBytes, errorMessage = null)
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
    ): DocumentFile {
        if (path.isEmpty()) return root
        cache[path]?.let { return it }

        val parts = path.split('/').filter { it.isNotEmpty() }
        var current = root
        var currentPath = ""

        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            current = cache.getOrPut(currentPath) {
                current.findFile(part)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(part)
                    ?: current
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
