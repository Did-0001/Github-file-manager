package com.example.domain.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        isWipe: Boolean
    ): DiffReport = withContext(Dispatchers.IO) {
        val normalizedDest = normalizeDestination(destinationDir)

        // Fetch remote tree
        val headShaRes = gitHubRepository.getBranchHeadSha(owner, repo, branch)
        val remoteMap = mutableMapOf<String, GitTreeItemDto>()

        if (headShaRes.isSuccess) {
            val treeShaRes = gitHubRepository.getCommitTreeSha(owner, repo, headShaRes.getOrThrow())
            if (treeShaRes.isSuccess) {
                val treeRes = gitHubRepository.getTree(owner, repo, treeShaRes.getOrThrow(), recursive = true)
                if (treeRes.isSuccess) {
                    for (item in treeRes.getOrThrow().tree) {
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
        if (isWipe) {
            for ((remPath, remItem) in remoteMap) {
                if (!handledRemotePaths.contains(remPath)) {
                    deleted++
                    diffItems.add(
                        DiffItem(
                            localPath = "",
                            remotePath = remPath,
                            changeType = DiffChangeType.DELETED,
                            sizeBytes = remItem.size,
                            reason = "Removed by wipe-before-upload"
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
            items = diffItems
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

        val itemEntities = files.map { f ->
            val finalPath = buildFinalGitHubPath(normalizedDest, f.relativePath)
            val localSha = try {
                GitBlobHasher.calculateSha(context, f.uri, f.sizeBytes)
            } catch (e: Exception) {
                null
            }
            TransferItemEntity(
                transferId = transferId,
                relativePath = f.relativePath,
                githubPath = finalPath,
                sizeBytes = f.sizeBytes,
                status = "PENDING",
                sha = localSha,
                blobSha = null,
                localUri = f.uri.toString(),
                processedBytes = 0L
            )
        }

        onCreated(transferId)

        val job = engineScope.launch {
            transferRepository.insertTransfer(entity)
            transferRepository.insertItems(itemEntities)
            executeUploadJob(transferId, entity)
        }
        activeJobs[transferId] = job
        return transferId
    }

    private suspend fun executeUploadJob(
        transferId: String,
        initialEntity: TransferEntity
    ) = withContext(Dispatchers.IO) {
        var entity = initialEntity
        try {
            // STEP 1: PREPARING & GET HEAD
            updateTransferStatus(entity.copy(status = TransferStatus.PREPARING.name))

            val headShaRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (headShaRes.isFailure) {
                failTransfer(entity, "Cannot resolve branch head: ${headShaRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val headCommitSha = headShaRes.getOrThrow()

            // Fetch remote tree map for unchanged blob reuse
            val remoteMap = mutableMapOf<String, GitTreeItemDto>()
            val treeShaRes = gitHubRepository.getCommitTreeSha(entity.repoOwner, entity.repoName, headCommitSha)
            if (treeShaRes.isSuccess) {
                val treeRes = gitHubRepository.getTree(entity.repoOwner, entity.repoName, treeShaRes.getOrThrow(), recursive = true)
                if (treeRes.isSuccess) {
                    for (item in treeRes.getOrThrow().tree) {
                        if (item.type == "blob") {
                            remoteMap[item.path] = item
                        }
                    }
                }
            }

            val baseTreeSha = if (entity.isWipe) {
                null // Clean slate: all non-uploaded files in tree removed!
            } else {
                treeShaRes.getOrNull()
            }

            // STEP 2: UPLOADING BLOBS (Memory-Safe Streaming)
            updateTransferStatus(entity.copy(status = TransferStatus.UPLOADING.name))
            val treeEntries = mutableListOf<CreateTreeEntryDto>()
            val items = transferRepository.getItemsForTransferSync(transferId).toMutableList()

            var processedFiles = 0
            var processedBytes = 0L
            var lastSpeedTimestamp = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            for (item in items) {
                if (pausedTransfers.contains(transferId)) {
                    updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
                    return@withContext
                }
                if (!coroutineContext.isActive) {
                    if (pausedTransfers.contains(transferId)) {
                        updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
                    }
                    return@withContext
                }

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
                        item.copy(status = "SUCCESS", blobSha = remoteExisting.sha, processedBytes = item.sizeBytes)
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

                while (attempt < 3 && uploadedBlobSha == null) {
                    attempt++
                    try {
                        val streamingBody = StreamingBlobRequestBody(
                            context = context,
                            uri = fileUri,
                            size = item.sizeBytes
                        ) { bytesSent ->
                            val currentProcessed = processedBytes + bytesSent
                            val now = System.currentTimeMillis()
                            bytesSinceLastSpeed += 12288
                            if (now - lastSpeedTimestamp >= 1000) {
                                val dur = (now - lastSpeedTimestamp) / 1000.0
                                val speed = (bytesSinceLastSpeed / dur).toLong()
                                transferSpeeds[transferId] = speed
                                lastSpeedTimestamp = now
                                bytesSinceLastSpeed = 0L
                            }
                        }

                        val blobRes = gitHubRepository.createBlobStream(entity.repoOwner, entity.repoName, streamingBody)
                        if (blobRes.isSuccess) {
                            uploadedBlobSha = blobRes.getOrThrow()
                        } else {
                            lastErr = blobRes.exceptionOrNull()?.message
                            delay(500L * attempt)
                        }
                    } catch (e: Exception) {
                        lastErr = e.localizedMessage
                        delay(500L * attempt)
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
                } else {
                    transferRepository.updateItem(
                        item.copy(status = "FAILED", errorMessage = lastErr ?: "Failed to upload blob to GitHub", retryCount = item.retryCount + 1)
                    )
                }

                processedFiles++
                processedBytes += item.sizeBytes
                entity = entity.copy(processedFiles = processedFiles, processedBytes = processedBytes)
                transferRepository.updateTransfer(entity)
            }

            if (treeEntries.isEmpty()) {
                failTransfer(entity, "No files could be uploaded successfully.")
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
                failTransfer(entity, "Failed to create Git tree: ${newTreeRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val newTreeSha = newTreeRes.getOrThrow()

            // Create Git commit
            val commitMsg = entity.commitMessage ?: "Upload files via GitHub File Manager"
            val newCommitRes = gitHubRepository.createCommit(
                owner = entity.repoOwner,
                repo = entity.repoName,
                message = commitMsg,
                treeSha = newTreeSha,
                parentCommitSha = headCommitSha
            )
            if (newCommitRes.isFailure) {
                failTransfer(entity, "Failed to create Git commit: ${newCommitRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val newCommitSha = newCommitRes.getOrThrow()

            // Conditional branch update (conflict check)
            val currentHead = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch).getOrNull()
            if (currentHead != null && currentHead != headCommitSha) {
                failTransfer(entity, "Remote branch moved concurrently (conflict detected). Commit $newCommitSha created but branch ref not updated.")
                return@withContext
            }

            // Update branch ref
            val updateRefRes = gitHubRepository.updateBranchRef(
                owner = entity.repoOwner,
                repo = entity.repoName,
                branch = entity.branch,
                commitSha = newCommitSha,
                force = false
            )
            if (updateRefRes.isFailure) {
                failTransfer(entity, "Branch update rejected: ${updateRefRes.exceptionOrNull()?.message}")
                return@withContext
            }

            // STEP 4: VERIFYING & COMPLETE
            updateTransferStatus(entity.copy(status = TransferStatus.VERIFYING.name))
            val verifyHead = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (verifyHead.getOrNull() == newCommitSha) {
                val completed = entity.copy(
                    status = TransferStatus.COMPLETED.name,
                    commitSha = newCommitSha,
                    currentFile = null,
                    completedAt = System.currentTimeMillis()
                )
                updateTransferStatus(completed)
            } else {
                failTransfer(entity, "Verification mismatch: branch head did not match new commit SHA.")
            }
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
            if (entity.type == TransferType.UPLOAD.name) {
                updateTransferStatus(entity.copy(status = TransferStatus.QUEUED.name))
                executeUploadJob(transferId, entity)
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
        remotePath: String,
        config: DownloadConfig
    ) = withContext(Dispatchers.IO) {
        var entity = initialEntity
        try {
            updateTransferStatus(entity.copy(status = TransferStatus.PREPARING.name))

            val destDoc = DocumentFile.fromTreeUri(context, config.destinationTreeUri)
                ?: DocumentFile.fromSingleUri(context, config.destinationTreeUri)
                ?: run {
                    failTransfer(entity, "Cannot access local destination storage")
                    return@withContext
                }

            // Case A: Whole repository ZIP download
            if (remotePath.isEmpty() && config.asZip) {
                updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name, currentFile = "Archive.zip"))
                val zipRes = gitHubRepository.downloadZipball(entity.repoOwner, entity.repoName, entity.branch)
                if (zipRes.isFailure) {
                    failTransfer(entity, "Failed to download zipball: ${zipRes.exceptionOrNull()?.message}")
                    return@withContext
                }

                val body = zipRes.getOrThrow()
                val zipFileName = "${entity.repoName}-${entity.branch}.zip"
                val targetFile = destDoc.createFile("application/zip", zipFileName)
                    ?: run {
                        failTransfer(entity, "Cannot create local zip file in destination")
                        return@withContext
                    }

                context.contentResolver.openOutputStream(targetFile.uri)?.use { out ->
                    body.byteStream().use { input ->
                        streamCopy(input, out) { bytesCopied ->
                            entity = entity.copy(processedBytes = bytesCopied, totalBytes = bytesCopied)
                            transferRepository.updateTransfer(entity)
                        }
                    }
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

            // Case B: Whole repository extracted into folder
            if (remotePath.isEmpty() && !config.asZip) {
                updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name, currentFile = "Extracting Repository..."))
                val zipRes = gitHubRepository.downloadZipball(entity.repoOwner, entity.repoName, entity.branch)
                if (zipRes.isFailure) {
                    failTransfer(entity, "Failed to download zipball: ${zipRes.exceptionOrNull()?.message}")
                    return@withContext
                }

                val body = zipRes.getOrThrow()
                val targetRoot = if (config.createRepoFolder) {
                    destDoc.findFile(entity.repoName)?.takeIf { it.isDirectory }
                        ?: destDoc.createDirectory(entity.repoName)
                        ?: destDoc
                } else destDoc

                extractZipStreamToFolder(body.byteStream(), targetRoot, config.overwritePolicy) { copied ->
                    entity = entity.copy(processedBytes = copied)
                    transferRepository.updateTransfer(entity)
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

            // Case C: Specific path (Single file OR recursive folder)
            updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name))

            val cleanRemote = remotePath.trimStart('/').trimEnd('/')
            val headShaRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (headShaRes.isFailure) {
                failTransfer(entity, "Cannot resolve branch head for download")
                return@withContext
            }

            val treeShaRes = gitHubRepository.getCommitTreeSha(entity.repoOwner, entity.repoName, headShaRes.getOrThrow())
            val treeRes = if (treeShaRes.isSuccess) {
                gitHubRepository.getTree(entity.repoOwner, entity.repoName, treeShaRes.getOrThrow(), recursive = true)
            } else null

            val allTreeItems = treeRes?.getOrNull()?.tree ?: emptyList()
            val targetBlobs = allTreeItems.filter {
                it.type == "blob" && (it.path == cleanRemote || it.path.startsWith("$cleanRemote/"))
            }

            if (targetBlobs.isEmpty()) {
                // Try fallback to getDirectoryContents or getFileDetails
                val contentsRes = gitHubRepository.getDirectoryContents(entity.repoOwner, entity.repoName, cleanRemote, entity.branch)
                if (contentsRes.isSuccess) {
                    val list = contentsRes.getOrThrow()
                    entity = entity.copy(totalFiles = list.size)
                    transferRepository.updateTransfer(entity)

                    for ((idx, item) in list.withIndex()) {
                        if (item.isFile) {
                            downloadSingleFile(item, destDoc, config.overwritePolicy)
                            entity = entity.copy(processedFiles = idx + 1, currentFile = item.name)
                            transferRepository.updateTransfer(entity)
                        }
                    }
                } else {
                    failTransfer(entity, "Specified path not found on GitHub")
                    return@withContext
                }
            } else {
                // Target blobs found! Full recursive hierarchy download
                val totalBytes = targetBlobs.sumOf { it.size }
                entity = entity.copy(totalFiles = targetBlobs.size, totalBytes = totalBytes)
                transferRepository.updateTransfer(entity)

                val dirCache = mutableMapOf<String, DocumentFile>()
                dirCache[""] = destDoc

                var processedFiles = 0
                var processedBytes = 0L

                for (blob in targetBlobs) {
                    if (pausedTransfers.contains(transferId) || !coroutineContext.isActive) {
                        updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
                        return@withContext
                    }

                    val relPathUnderTarget = if (blob.path == cleanRemote) {
                        blob.path.substringAfterLast('/')
                    } else {
                        blob.path.removePrefix("$cleanRemote/").removePrefix("/")
                    }

                    val parentDir = if (relPathUnderTarget.contains('/')) {
                        val subPath = relPathUnderTarget.substringBeforeLast('/')
                        getOrCreateSubDir(destDoc, subPath, dirCache)
                    } else destDoc

                    val fileName = relPathUnderTarget.substringAfterLast('/')
                    entity = entity.copy(currentFile = fileName)
                    transferRepository.updateTransfer(entity)

                    // Download raw content
                    val rawUrl = "https://raw.githubusercontent.com/${entity.repoOwner}/${entity.repoName}/${entity.branch}/${blob.path}"
                    val rawRes = gitHubRepository.downloadRaw(rawUrl)

                    if (rawRes.isSuccess) {
                        val existing = parentDir.findFile(fileName)
                        if (existing != null && config.overwritePolicy == OverwritePolicy.SKIP) {
                            processedFiles++
                            processedBytes += blob.size
                            continue
                        }
                        if (existing != null && config.overwritePolicy == OverwritePolicy.OVERWRITE) {
                            existing.delete()
                        }

                        val targetFileDoc = parentDir.createFile("application/octet-stream", fileName)
                        if (targetFileDoc != null) {
                            context.contentResolver.openOutputStream(targetFileDoc.uri)?.use { out ->
                                rawRes.getOrThrow().byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        out.write(buffer, 0, read)
                                        processedBytes += read
                                    }
                                }
                            }
                        }
                    }

                    processedFiles++
                    entity = entity.copy(processedFiles = processedFiles, processedBytes = processedBytes)
                    transferRepository.updateTransfer(entity)
                }
            }

            updateTransferStatus(
                entity.copy(
                    status = TransferStatus.COMPLETED.name,
                    completedAt = System.currentTimeMillis(),
                    currentFile = null
                )
            )
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

    private suspend fun downloadSingleFile(
        item: GitHubContentDto,
        targetDir: DocumentFile,
        policy: OverwritePolicy
    ) {
        val existing = targetDir.findFile(item.name)
        if (existing != null && policy == OverwritePolicy.SKIP) return
        if (existing != null && policy == OverwritePolicy.OVERWRITE) {
            existing.delete()
        }

        val created = targetDir.createFile("application/octet-stream", item.name) ?: return
        val url = item.downloadUrl ?: return
        val res = gitHubRepository.downloadRaw(url)
        if (res.isSuccess) {
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                res.getOrThrow().byteStream().use { input ->
                    input.copyTo(out)
                }
            }
        }
    }

    private suspend fun extractZipStreamToFolder(
        zipInput: InputStream,
        targetDir: DocumentFile,
        policy: OverwritePolicy,
        onBytesCopied: suspend (Long) -> Unit
    ) {
        val zis = ZipInputStream(zipInput)
        var totalBytes = 0L
        var entry: ZipEntry? = zis.nextEntry

        val dirCache = mutableMapOf<String, DocumentFile>()
        dirCache[""] = targetDir

        while (entry != null) {
            val name = entry.name
            // Skip root repo folder inside GitHub zipball (e.g. repo-name-sha/)
            val cleanName = if (name.contains('/')) name.substringAfter('/') else name
            if (cleanName.isNotEmpty()) {
                if (entry.isDirectory) {
                    getOrCreateSubDir(targetDir, cleanName, dirCache)
                } else {
                    val parentPath = if (cleanName.contains('/')) cleanName.substringBeforeLast('/') else ""
                    val fileName = cleanName.substringAfterLast('/')
                    val parentDir = getOrCreateSubDir(targetDir, parentPath, dirCache)

                    val existing = parentDir.findFile(fileName)
                    if (existing != null && policy == OverwritePolicy.OVERWRITE) {
                        existing.delete()
                    }
                    if (existing == null || policy == OverwritePolicy.OVERWRITE) {
                        val fileDoc = parentDir.createFile("application/octet-stream", fileName)
                        if (fileDoc != null) {
                            context.contentResolver.openOutputStream(fileDoc.uri)?.use { out ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    out.write(buffer, 0, read)
                                    totalBytes += read
                                    onBytesCopied(totalBytes)
                                }
                            }
                        }
                    }
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
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
