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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
                                remotePath = relativePath,
                                changeType = DiffChangeType.EXCLUDED,
                                sizeBytes = 0L,
                                reason = reason
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
                                remotePath = relativePath,
                                changeType = DiffChangeType.EXCLUDED,
                                sizeBytes = size,
                                reason = reason
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
            val name = doc.name ?: "file_${System.currentTimeMillis()}"
            val size = doc.length()

            val (ignored, reason) = ignoreRules.isIgnored(name, false, size)
            if (ignored) {
                excludedItems.add(
                    DiffItem(
                        localPath = name,
                        remotePath = name,
                        changeType = DiffChangeType.EXCLUDED,
                        sizeBytes = size,
                        reason = reason
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

    // 2. Preflight Validator
    suspend fun runPreflight(
        owner: String,
        repo: String,
        branch: String,
        destinationDir: String,
        files: List<FileScanItem>,
        isWipe: Boolean
    ): PreflightReport = withContext(Dispatchers.IO) {
        val checks = mutableListOf<PreflightCheckItem>()
        var errors = 0
        var warnings = 0

        // Check 1: Repo accessibility
        val repoResult = gitHubRepository.getUserRepos()
        if (repoResult.isFailure) {
            checks.add(PreflightCheckItem("Repository Access", CheckLevel.FAIL, "Cannot access GitHub repositories: ${repoResult.exceptionOrNull()?.message}"))
            errors++
        } else {
            checks.add(PreflightCheckItem("Repository Access", CheckLevel.PASS, "Authenticated access confirmed for $owner/$repo"))
        }

        // Check 2: Branch existence
        val branchResult = gitHubRepository.getBranches(owner, repo)
        if (branchResult.isFailure) {
            checks.add(PreflightCheckItem("Branch Verification", CheckLevel.FAIL, "Could not fetch branches for $owner/$repo"))
            errors++
        } else {
            val branchObj = branchResult.getOrNull()?.find { it.name == branch }
            if (branchObj == null) {
                checks.add(PreflightCheckItem("Branch Verification", CheckLevel.FAIL, "Branch '$branch' does not exist on remote repository"))
                errors++
            } else {
                if (branchObj.isProtected) {
                    if (isWipe) {
                        checks.add(PreflightCheckItem("Protected Branch", CheckLevel.FAIL, "Branch '$branch' has branch protection enabled. Wipe cannot proceed on protected branches."))
                        errors++
                    } else {
                        checks.add(PreflightCheckItem("Protected Branch", CheckLevel.WARN, "Branch '$branch' is protected. Commits may require pull requests."))
                        warnings++
                    }
                } else {
                    checks.add(PreflightCheckItem("Branch Verification", CheckLevel.PASS, "Branch '$branch' verified"))
                }
            }
        }

        // Check 3: File count & sizes
        var totalBytes = 0L
        var largeFilesCount = 0
        for (f in files) {
            totalBytes += f.sizeBytes
            if (f.sizeBytes > 100 * 1024 * 1024) { // GitHub 100MB file limit
                largeFilesCount++
            }
        }

        if (files.isEmpty()) {
            checks.add(PreflightCheckItem("Files Selected", CheckLevel.FAIL, "No files selected or all files excluded"))
            errors++
        } else {
            checks.add(PreflightCheckItem("Files Scanned", CheckLevel.PASS, "${files.size} local files ready (${formatBytes(totalBytes)})"))
        }

        if (largeFilesCount > 0) {
            checks.add(PreflightCheckItem("GitHub Size Limit", CheckLevel.FAIL, "$largeFilesCount file(s) exceed GitHub's 100MB regular file size limit"))
            errors++
        }

        // Check 4: Destination path safety
        val normalizedDest = normalizeDestination(destinationDir)
        if (normalizedDest.contains("..")) {
            checks.add(PreflightCheckItem("Destination Safety", CheckLevel.FAIL, "Path traversal ('..') detected in destination path"))
            errors++
        } else {
            val destDisplay = if (normalizedDest.isEmpty()) "Repository Root (/)" else "/$normalizedDest/"
            checks.add(PreflightCheckItem("Destination Path", CheckLevel.PASS, "Target destination: $destDisplay"))
        }

        // Check 5: Wipe warning
        if (isWipe) {
            checks.add(PreflightCheckItem("Wipe Mode", CheckLevel.WARN, "Wipe is enabled. Existing tracked files on branch '$branch' will be replaced."))
            warnings++
        }

        PreflightReport(
            checks = checks,
            totalFiles = files.size,
            totalBytes = totalBytes,
            warningsCount = warnings,
            errorsCount = errors
        )
    }

    // 3. Diff Calculation
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

        // Try to fetch remote tree
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
                // If remote exists and same size, tentatively unchanged or modified
                if (remoteItem.size == local.sizeBytes) {
                    unchanged++
                    diffItems.add(
                        DiffItem(
                            localPath = local.relativePath,
                            remotePath = finalPath,
                            changeType = DiffChangeType.UNCHANGED,
                            sizeBytes = local.sizeBytes
                        )
                    )
                } else {
                    modified++
                    diffItems.add(
                        DiffItem(
                            localPath = local.relativePath,
                            remotePath = finalPath,
                            changeType = DiffChangeType.MODIFIED,
                            sizeBytes = local.sizeBytes
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

        // Add excluded
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

    // 4. Upload Execution
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
            TransferItemEntity(
                transferId = transferId,
                relativePath = f.relativePath,
                githubPath = finalPath,
                sizeBytes = f.sizeBytes,
                status = "PENDING"
            )
        }

        onCreated(transferId)

        val job = engineScope.launch {
            transferRepository.insertTransfer(entity)
            transferRepository.insertItems(itemEntities)
            executeUploadJob(transferId, entity, files, normalizedDest)
        }
        activeJobs[transferId] = job
        return transferId
    }

    private suspend fun executeUploadJob(
        transferId: String,
        initialEntity: TransferEntity,
        files: List<FileScanItem>,
        normalizedDest: String
    ) = withContext(Dispatchers.IO) {
        var entity = initialEntity
        try {
            // STEP 1: PREPARING & GET HEAD
            updateTransferStatus(entity.copy(status = TransferStatus.PREPARING.name))

            val headShaRes = gitHubRepository.getBranchHeadSha(entity.repoOwner, entity.repoName, entity.branch)
            if (headShaRes.isFailure) {
                failTransfer(entity, "Cannot find branch head SHA: ${headShaRes.exceptionOrNull()?.message}")
                return@withContext
            }
            val headCommitSha = headShaRes.getOrThrow()

            val baseTreeSha = if (entity.isWipe) {
                null // Brand new tree without existing files!
            } else {
                val treeShaRes = gitHubRepository.getCommitTreeSha(entity.repoOwner, entity.repoName, headCommitSha)
                if (treeShaRes.isFailure) {
                    failTransfer(entity, "Cannot resolve base tree: ${treeShaRes.exceptionOrNull()?.message}")
                    return@withContext
                }
                treeShaRes.getOrThrow()
            }

            // STEP 2: UPLOADING BLOBS
            updateTransferStatus(entity.copy(status = TransferStatus.UPLOADING.name))
            val treeEntries = mutableListOf<CreateTreeEntryDto>()
            val items = transferRepository.getItemsForTransferSync(transferId).associateBy { it.relativePath }.toMutableMap()

            var processedFiles = 0
            var processedBytes = 0L
            var lastSpeedTimestamp = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            for (file in files) {
                if (pausedTransfers.contains(transferId)) {
                    updateTransferStatus(entity.copy(status = TransferStatus.PAUSED.name))
                    return@withContext
                }
                if (!coroutineContext.isActive) return@withContext

                val itemEntity = items[file.relativePath]
                val finalPath = buildFinalGitHubPath(normalizedDest, file.relativePath)

                // Update current file
                entity = entity.copy(
                    currentFile = file.relativePath,
                    processedFiles = processedFiles,
                    processedBytes = processedBytes
                )
                transferRepository.updateTransfer(entity)

                // Read file bytes via SAF streaming
                val base64Content = readFileAsBase64(file.uri)
                if (base64Content == null) {
                    itemEntity?.let {
                        transferRepository.updateItem(it.copy(status = "FAILED", errorMessage = "Failed to read local file"))
                    }
                    continue
                }

                // Upload blob with retry
                val blobSha = uploadBlobWithRetry(entity.repoOwner, entity.repoName, base64Content)
                if (blobSha != null) {
                    treeEntries.add(
                        CreateTreeEntryDto(
                            path = finalPath,
                            mode = "100644",
                            type = "blob",
                            sha = blobSha
                        )
                    )
                    itemEntity?.let {
                        transferRepository.updateItem(it.copy(status = "SUCCESS", sha = blobSha))
                    }
                } else {
                    itemEntity?.let {
                        transferRepository.updateItem(it.copy(status = "FAILED", errorMessage = "GitHub blob creation failed"))
                    }
                }

                processedFiles++
                processedBytes += file.sizeBytes
                bytesSinceLastSpeed += file.sizeBytes

                val now = System.currentTimeMillis()
                if (now - lastSpeedTimestamp >= 1000) {
                    val durationSec = (now - lastSpeedTimestamp) / 1000.0
                    val speed = (bytesSinceLastSpeed / durationSec).toLong()
                    transferSpeeds[transferId] = speed
                    lastSpeedTimestamp = now
                    bytesSinceLastSpeed = 0L
                }

                entity = entity.copy(
                    processedFiles = processedFiles,
                    processedBytes = processedBytes
                )
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

            // Update branch ref
            val updateRefRes = gitHubRepository.updateBranchRef(
                owner = entity.repoOwner,
                repo = entity.repoName,
                branch = entity.branch,
                commitSha = newCommitSha,
                force = false
            )
            if (updateRefRes.isFailure) {
                failTransfer(entity, "Branch conflict or update rejected: ${updateRefRes.exceptionOrNull()?.message}")
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
            updateTransferStatus(entity.copy(status = TransferStatus.CANCELLED.name))
        } catch (e: Exception) {
            failTransfer(entity, "Unexpected error: ${e.localizedMessage}")
        } finally {
            activeJobs.remove(transferId)
            transferSpeeds.remove(transferId)
        }
    }

    private suspend fun uploadBlobWithRetry(owner: String, repo: String, base64: String): String? {
        var delayMs = 500L
        for (attempt in 1..3) {
            val res = gitHubRepository.createBlob(owner, repo, base64)
            if (res.isSuccess) return res.getOrThrow()
            delay(delayMs)
            delayMs *= 2
        }
        return null
    }

    // 5. Download Execution
    fun startDownload(
        owner: String,
        repo: String,
        branch: String,
        remotePath: String, // file or folder or "" for whole repo
        config: DownloadConfig,
        onCreated: (String) -> Unit
    ): String {
        val transferId = UUID.randomUUID().toString()
        val displayName = if (remotePath.isEmpty()) "$repo ($branch)" else remotePath.substringAfterLast('/')

        val entity = TransferEntity(
            id = transferId,
            type = TransferType.DOWNLOAD.name,
            repoOwner = owner,
            repoName = repo,
            branch = branch,
            sourcePath = if (remotePath.isEmpty()) "Full Repo ($branch)" else remotePath,
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

            // Case A: Download as ZIP (Repository Zipball or folder zip)
            if (config.asZip || remotePath.isEmpty()) {
                updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name, currentFile = "Archive.zip"))
                val zipRes = gitHubRepository.downloadZipball(entity.repoOwner, entity.repoName, entity.branch)
                if (zipRes.isFailure) {
                    failTransfer(entity, "Failed to download zipball: ${zipRes.exceptionOrNull()?.message}")
                    return@withContext
                }

                val body = zipRes.getOrThrow()
                val zipFileName = "${entity.repoName}-${entity.branch}.zip"

                if (config.asZip) {
                    // Save directly as ZIP
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
                } else {
                    // Extract ZIP into destination folder preserving structure
                    extractZipStreamToFolder(body.byteStream(), destDoc, config.overwritePolicy) { copied ->
                        entity = entity.copy(processedBytes = copied)
                        transferRepository.updateTransfer(entity)
                    }
                }

                updateTransferStatus(entity.copy(
                    status = TransferStatus.COMPLETED.name,
                    completedAt = System.currentTimeMillis(),
                    currentFile = null
                ))
                return@withContext
            }

            // Case B: Download single file or specific folder
            updateTransferStatus(entity.copy(status = TransferStatus.DOWNLOADING.name))
            val contentsRes = gitHubRepository.getDirectoryContents(entity.repoOwner, entity.repoName, remotePath, entity.branch)
            if (contentsRes.isFailure) {
                // Try as single file
                val fileRes = gitHubRepository.getFileDetails(entity.repoOwner, entity.repoName, remotePath, entity.branch)
                if (fileRes.isFailure) {
                    failTransfer(entity, "Item not found on GitHub")
                    return@withContext
                }
                val file = fileRes.getOrThrow()
                downloadSingleFile(file, destDoc, config.overwritePolicy)
            } else {
                val list = contentsRes.getOrThrow()
                for (item in list) {
                    if (item.isFile) {
                        downloadSingleFile(item, destDoc, config.overwritePolicy)
                    }
                }
            }

            updateTransferStatus(entity.copy(
                status = TransferStatus.COMPLETED.name,
                completedAt = System.currentTimeMillis(),
                currentFile = null
            ))
        } catch (e: CancellationException) {
            updateTransferStatus(entity.copy(status = TransferStatus.CANCELLED.name))
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
        activeJobs[transferId]?.cancel()
        engineScope.launch {
            transferRepository.getTransfer(transferId)?.let {
                transferRepository.updateTransfer(it.copy(status = TransferStatus.CANCELLED.name))
            }
        }
    }

    // Helper functions
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

    private fun readFileAsBase64(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                val baos = ByteArrayOutputStream()
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    baos.write(buffer, 0, bytesRead)
                }
                android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
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
