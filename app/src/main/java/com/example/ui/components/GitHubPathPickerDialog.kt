package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.dto.GitHubContentDto
import com.example.ui.theme.GhDarkAccentBlue
import com.example.ui.theme.GhDarkAccentGreen
import com.example.ui.theme.GhDarkAccentOrange
import kotlinx.coroutines.launch

enum class GitHubPickerMode {
    DIRECTORIES_ONLY, // Upload destination & Download folder scope (Root and folders selectable)
    FILES_ONLY,        // Download single file scope (Files selectable)
    SELECTED_ITEM      // Download selected-item flow (Any file or folder selectable)
}

@Composable
fun GitHubPathPickerDialog(
    selectedRepo: SelectedRepoInfo?,
    initialPath: String = "",
    pickerMode: GitHubPickerMode,
    onFetchDirectory: suspend (owner: String, repo: String, path: String, branch: String) -> Result<List<GitHubContentDto>>,
    onDismiss: () -> Unit,
    onPathConfirmed: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        GitHubPathPickerContent(
            selectedRepo = selectedRepo,
            initialPath = initialPath,
            pickerMode = pickerMode,
            onFetchDirectory = onFetchDirectory,
            onDismiss = onDismiss,
            onPathConfirmed = onPathConfirmed,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
        )
    }
}

@Composable
fun GitHubPathPickerContent(
    selectedRepo: SelectedRepoInfo?,
    initialPath: String = "",
    pickerMode: GitHubPickerMode,
    onFetchDirectory: suspend (owner: String, repo: String, path: String, branch: String) -> Result<List<GitHubContentDto>>,
    onDismiss: () -> Unit,
    onPathConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val coroutineScope = rememberCoroutineScope()

    // Navigation and selection state
    var currentPath by remember {
        mutableStateOf(initialPath.trim().trimStart('/').trimEnd('/'))
    }
    var selectedPath by remember {
        mutableStateOf(
            if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY) {
                initialPath.trim().trimStart('/').trimEnd('/')
            } else {
                initialPath.trim().trimStart('/')
            }
        )
    }

    var contents by remember { mutableStateOf<List<GitHubContentDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath, selectedRepo) {
        if (selectedRepo == null) {
            errorMessage = "No repository selected."
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        val res = onFetchDirectory(
            selectedRepo.owner,
            selectedRepo.name,
            currentPath,
            selectedRepo.branch
        )
        if (res.isSuccess) {
            contents = res.getOrThrow()
        } else {
            errorMessage = res.exceptionOrNull()?.message ?: "Failed to load directory contents"
        }
        isLoading = false
    }

    fun reload() {
        if (selectedRepo == null) {
            errorMessage = "No repository selected."
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val res = onFetchDirectory(
                selectedRepo.owner,
                selectedRepo.name,
                currentPath,
                selectedRepo.branch
            )
            if (res.isSuccess) {
                contents = res.getOrThrow()
            } else {
                errorMessage = res.exceptionOrNull()?.message ?: "Failed to load directory contents"
            }
            isLoading = false
        }
    }

    val canConfirm = remember(selectedPath, pickerMode) {
        when (pickerMode) {
            GitHubPickerMode.DIRECTORIES_ONLY -> true // Can select root ("") or any valid folder
            GitHubPickerMode.FILES_ONLY -> selectedPath.isNotBlank()
            GitHubPickerMode.SELECTED_ITEM -> selectedPath.isNotBlank()
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = modifier
            .testTag("github_path_picker_dialog")
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header: Title & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (pickerMode) {
                                GitHubPickerMode.DIRECTORIES_ONLY -> "Browse GitHub Destination"
                                GitHubPickerMode.FILES_ONLY -> "Browse Remote GitHub File"
                                GitHubPickerMode.SELECTED_ITEM -> "Select GitHub Item"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (selectedRepo != null) {
                            Text(
                                text = "${selectedRepo.fullName} (${selectedRepo.branch})",
                                fontSize = 12.sp,
                                color = GhDarkAccentBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("path_picker_close_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Navigation Bar: Up Button, Refresh & Breadcrumbs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back/Up navigation button
                    val canGoUp = currentPath.isNotEmpty()
                    IconButton(
                        onClick = {
                            if (canGoUp) {
                                val parent = currentPath.substringBeforeLast('/', "")
                                currentPath = parent
                                if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY) {
                                    selectedPath = parent
                                }
                            }
                        },
                        enabled = canGoUp,
                        modifier = Modifier.size(32.dp).testTag("path_picker_up_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Navigate Up",
                            tint = if (canGoUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Root / Breadcrumbs path display
                    Box(modifier = Modifier.weight(1f)) {
                        BreadcrumbsRow(
                            path = currentPath,
                            onSegmentClick = { targetPath ->
                                currentPath = targetPath
                                if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY) {
                                    selectedPath = targetPath
                                }
                            },
                            onCopyPath = {}
                        )
                    }

                    // Refresh Button
                    IconButton(
                        onClick = { reload() },
                        modifier = Modifier.size(32.dp).testTag("path_picker_refresh_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Directory",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Current Selected Path Bar
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("path_picker_current_selection_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected: ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val displaySelected = if (selectedPath.isBlank()) {
                            if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY) "/ (Root)" else "None"
                        } else {
                            if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY) "/$selectedPath" else selectedPath
                        }
                        Text(
                            text = displaySelected,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).testTag("path_picker_selected_path_text")
                        )

                        if ((pickerMode == GitHubPickerMode.DIRECTORIES_ONLY || pickerMode == GitHubPickerMode.SELECTED_ITEM) && currentPath != selectedPath) {
                            TextButton(
                                onClick = { selectedPath = currentPath },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp).testTag("path_picker_select_current_folder_btn")
                            ) {
                                Text("Select Current Folder", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Directory Contents Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                ) {
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize().testTag("path_picker_loading"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Fetching repository contents...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        errorMessage != null -> {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(16.dp).testTag("path_picker_error"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = errorMessage ?: "Unknown error",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { reload() },
                                        modifier = Modifier.testTag("path_picker_retry_btn")
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }

                        contents.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize().testTag("path_picker_empty"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("This folder is empty.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("path_picker_items_list"),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(contents, key = { it.path }) { item ->
                                    val isDirectory = item.isDirectory
                                    val isItemCurrentSelection = selectedPath == item.path

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isDirectory) {
                                                    // Navigate into directory
                                                    currentPath = item.path
                                                    if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY || pickerMode == GitHubPickerMode.SELECTED_ITEM) {
                                                        selectedPath = item.path
                                                    }
                                                } else if (pickerMode == GitHubPickerMode.FILES_ONLY || pickerMode == GitHubPickerMode.SELECTED_ITEM) {
                                                    // Select file
                                                    selectedPath = item.path
                                                }
                                            }
                                            .background(
                                                if (isItemCurrentSelection) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                else androidx.compose.ui.graphics.Color.Transparent
                                            )
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                            .testTag("path_picker_item_${item.path}"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                            contentDescription = if (isDirectory) "Directory" else "File",
                                            tint = if (isDirectory) GhDarkAccentBlue else GhDarkAccentOrange,
                                            modifier = Modifier.size(20.dp)
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                fontSize = 13.sp,
                                                fontWeight = if (isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!isDirectory && item.size > 0) {
                                                Text(
                                                    text = "${item.size} bytes",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (isDirectory) {
                                            if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY || pickerMode == GitHubPickerMode.SELECTED_ITEM) {
                                                // Quick select button without navigating into it
                                                IconButton(
                                                    onClick = { selectedPath = item.path },
                                                    modifier = Modifier.size(28.dp).testTag("select_folder_${item.path}")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isItemCurrentSelection) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                        contentDescription = "Select this folder",
                                                        tint = if (isItemCurrentSelection) GhDarkAccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Navigate into folder",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else if (pickerMode == GitHubPickerMode.FILES_ONLY || pickerMode == GitHubPickerMode.SELECTED_ITEM) {
                                            Icon(
                                                imageVector = if (isItemCurrentSelection) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = "Select this file",
                                                tint = if (isItemCurrentSelection) GhDarkAccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons: Cancel and Tick/Confirm
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("path_picker_cancel_btn")
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            val finalPath = if (pickerMode == GitHubPickerMode.DIRECTORIES_ONLY) {
                                val cleaned = selectedPath.trim().trimStart('/').trimEnd('/')
                                if (cleaned.isEmpty()) "/" else cleaned
                            } else {
                                selectedPath.trim().trimStart('/')
                            }
                            onPathConfirmed(finalPath)
                        },
                        enabled = canConfirm,
                        modifier = Modifier.testTag("path_picker_confirm_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select")
                    }
                }
            }
        }
}
