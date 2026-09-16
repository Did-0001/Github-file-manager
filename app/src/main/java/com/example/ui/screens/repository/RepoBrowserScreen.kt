package com.example.ui.screens.repository

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.dto.GitHubContentDto
import com.example.ui.components.BreadcrumbsRow
import com.example.ui.components.EmptyStateView
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoBrowserScreen(
    selectedRepo: SelectedRepoInfo?,
    currentPath: String,
    contents: List<GitHubContentDto>,
    isLoading: Boolean,
    errorMessage: String?,
    onNavigatePath: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String, String, String, (Boolean, String?) -> Unit) -> Unit,
    onCreateDirectory: (String, (Boolean, String?) -> Unit) -> Unit,
    onDeleteFile: (GitHubContentDto, String, (Boolean, String?) -> Unit) -> Unit,
    onDownloadFile: (GitHubContentDto) -> Unit,
    onDownloadCurrentFolder: () -> Unit,
    onOpenRepoSelector: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFileForDetail by remember { mutableStateOf<GitHubContentDto?>(null) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateDirDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val filteredContents = remember(contents, searchQuery) {
        if (searchQuery.isBlank()) contents
        else contents.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            Column {
                // Header with repo name and switcher
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedRepo?.fullName ?: "No repository",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ForkRight,
                                    contentDescription = null,
                                    tint = GhDarkAccentPurple,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = selectedRepo?.branch ?: "main",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier.testTag("browser_refresh_button")
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(
                                onClick = { showCreateFileDialog = true },
                                modifier = Modifier.testTag("browser_create_file_button")
                            ) {
                                Icon(imageVector = Icons.Default.NoteAdd, contentDescription = "New File")
                            }
                            IconButton(
                                onClick = { showCreateDirDialog = true },
                                modifier = Modifier.testTag("browser_create_dir_button")
                            ) {
                                Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                            }
                        }
                    }
                }

                // Breadcrumb navigation
                BreadcrumbsRow(
                    path = currentPath,
                    onSegmentClick = onNavigatePath,
                    onCopyPath = {
                        clipboardManager.setText(AnnotatedString(currentPath.ifEmpty { "/" }))
                    }
                )

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter items in folder...") },
                    leadingIcon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("browser_filter_input")
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onDownloadCurrentFolder,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("browser_download_folder_fab")
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download Folder")
                }
            }
        },
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectedRepo == null) {
                EmptyStateView(
                    icon = Icons.Default.Source,
                    title = "No Repository Selected",
                    subtitle = "Select a GitHub repository to browse files and explore directories.",
                    actionText = "Choose Repository",
                    onAction = onOpenRepoSelector
                )
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (errorMessage != null) {
                EmptyStateView(
                    icon = Icons.Default.ErrorOutline,
                    title = "Error Loading Contents",
                    subtitle = errorMessage,
                    actionText = "Retry",
                    onAction = onRefresh
                )
            } else if (filteredContents.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Default.FolderOpen,
                    title = if (searchQuery.isEmpty()) "Directory is Empty" else "No matching files",
                    subtitle = if (searchQuery.isEmpty()) "This folder contains no files on branch '${selectedRepo.branch}'." else "Try adjusting your filter search."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Parent directory item (..) if not at root
                    if (currentPath.isNotEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val parent = if (currentPath.contains('/')) currentPath.substringBeforeLast('/') else ""
                                        onNavigatePath(parent)
                                    }
                                    .testTag("browser_parent_dir")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Parent Directory",
                                        tint = GhDarkAccentBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "..",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "(Parent directory)",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Directory & file items
                    items(filteredContents) { item ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item.isDirectory) {
                                        onNavigatePath(item.path)
                                    } else {
                                        selectedFileForDetail = item
                                    }
                                }
                                .testTag("browser_item_${item.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (item.isDirectory) Icons.Default.Folder else getFileIcon(item.name),
                                        contentDescription = if (item.isDirectory) "Directory" else "File",
                                        tint = if (item.isDirectory) GhDarkAccentBlue else getFileIconColor(item.name),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = item.name,
                                            fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.isFile) {
                                            Text(
                                                text = "${formatBytes(item.size)} • SHA: ${item.sha.take(7)}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                if (item.isDirectory) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    IconButton(
                                        onClick = { selectedFileForDetail = item },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // File Detail Modal Sheet / Dialog
    if (selectedFileForDetail != null) {
        val file = selectedFileForDetail!!
        AlertDialog(
            onDismissRequest = { selectedFileForDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = getFileIcon(file.name), contentDescription = null, tint = getFileIconColor(file.name))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Column {
                    DetailRow("Path", file.path)
                    DetailRow("Size", formatBytes(file.size))
                    DetailRow("SHA-1", file.sha)
                    DetailRow("Type", file.type)

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                file.htmlUrl?.let { url ->
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("GitHub")
                        }

                        Button(
                            onClick = {
                                selectedFileForDetail = null
                                onDownloadFile(file)
                            },
                            modifier = Modifier.weight(1f).testTag("dialog_download_file_button")
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            showDeleteConfirmDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GhDarkAccentRed),
                        modifier = Modifier.fillMaxWidth().testTag("dialog_delete_file_button")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete from GitHub")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedFileForDetail = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog && selectedFileForDetail != null) {
        val file = selectedFileForDetail!!
        var commitMsg by remember { mutableStateOf("Delete ${file.name}") }
        var isDeleting by remember { mutableStateOf(false) }
        var deleteError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete ${file.name}?") },
            text = {
                Column {
                    Text(
                        text = "This will commit the deletion directly to branch '${selectedRepo?.branch}'. This action cannot be undone on the remote without a revert commit.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = commitMsg,
                        onValueChange = { commitMsg = it },
                        label = { Text("Commit Message") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("delete_commit_message_input")
                    )
                    if (deleteError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = deleteError!!, color = GhDarkAccentRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        deleteError = null
                        onDeleteFile(file, commitMsg) { success, err ->
                            isDeleting = false
                            if (success) {
                                showDeleteConfirmDialog = false
                                selectedFileForDetail = null
                                onRefresh()
                            } else {
                                deleteError = err ?: "Deletion failed"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GhDarkAccentRed),
                    enabled = commitMsg.isNotBlank() && !isDeleting,
                    modifier = Modifier.testTag("confirm_delete_file_button")
                ) {
                    if (isDeleting) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    Text("Delete File")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Create File Dialog
    if (showCreateFileDialog) {
        CreateFileDialog(
            currentPath = currentPath,
            onDismiss = { showCreateFileDialog = false },
            onCreate = { fileName, content, commitMsg, cb ->
                onCreateFile(fileName, content, commitMsg) { success, err ->
                    if (success) onRefresh()
                    cb(success, err)
                }
            }
        )
    }

    // Create Directory Dialog
    if (showCreateDirDialog) {
        CreateDirectoryDialog(
            currentPath = currentPath,
            onDismiss = { showCreateDirDialog = false },
            onCreate = { dirName, cb ->
                onCreateDirectory(dirName) { success, err ->
                    if (success) onRefresh()
                    cb(success, err)
                }
            }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CreateFileDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, (Boolean, String?) -> Unit) -> Unit
) {
    var fileName by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create File in /${currentPath.trim('/')}") },
        text = {
            Column {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        if (commitMessage.isBlank()) commitMessage = "Create $it"
                    },
                    label = { Text("File Name (e.g. README.md)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_file_name_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("File Content") },
                    maxLines = 6,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("new_file_content_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit Message") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_file_commit_msg_input")
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = error!!, color = GhDarkAccentRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isCreating = true
                    error = null
                    onCreate(fileName, content, commitMessage) { success, err ->
                        isCreating = false
                        if (success) {
                            onDismiss()
                        } else {
                            error = err ?: "Failed to create file"
                        }
                    }
                },
                enabled = fileName.isNotBlank() && commitMessage.isNotBlank() && !isCreating,
                modifier = Modifier.testTag("confirm_create_file_button")
            ) {
                if (isCreating) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                Text("Commit New File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CreateDirectoryDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onCreate: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var dirName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Folder") },
        text = {
            Column {
                Text(
                    text = "Git does not track empty directories. Creating a folder will initialize it with a .gitkeep placeholder file.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = dirName,
                    onValueChange = { dirName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_dir_name_input")
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = error!!, color = GhDarkAccentRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isCreating = true
                    error = null
                    onCreate(dirName) { success, err ->
                        isCreating = false
                        if (success) {
                            onDismiss()
                        } else {
                            error = err ?: "Failed to create folder"
                        }
                    }
                },
                enabled = dirName.isNotBlank() && !isCreating,
                modifier = Modifier.testTag("confirm_create_dir_button")
            ) {
                if (isCreating) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun getFileIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "java", "py", "js", "ts", "html", "css", "cpp", "c", "go", "rs", "rb", "php", "swift" -> Icons.Default.Code
        "md", "txt", "doc", "pdf" -> Icons.Default.Description
        "json", "xml", "yaml", "yml", "toml", "gradle", "properties" -> Icons.Default.Settings
        "png", "jpg", "jpeg", "gif", "svg", "webp", "ico" -> Icons.Default.Image
        "zip", "tar", "gz", "7z", "jar", "aar" -> Icons.Default.Archive
        else -> Icons.Default.InsertDriveFile
    }
}

fun getFileIconColor(name: String): Color {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "java" -> GhDarkAccentPurple
        "py", "js", "ts", "go", "rs" -> GhDarkAccentBlue
        "md", "txt" -> GhDarkAccentGreen
        "json", "xml", "yaml", "yml", "toml", "gradle" -> GhDarkAccentOrange
        "png", "jpg", "jpeg", "gif", "svg", "webp" -> Color(0xFFE36209)
        "zip", "tar", "gz", "7z" -> Color(0xFFD29922)
        else -> Color(0xFF8B949E)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
