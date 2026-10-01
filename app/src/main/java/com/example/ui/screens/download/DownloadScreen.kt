package com.example.ui.screens.download

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.dto.GitHubContentDto
import com.example.domain.model.DownloadConfig
import com.example.domain.model.OverwritePolicy
import com.example.ui.components.GitHubPathPickerDialog
import com.example.ui.components.GitHubPickerMode
import com.example.ui.theme.GhDarkAccentBlue
import com.example.ui.theme.GhDarkAccentGreen
import com.example.ui.theme.GhDarkAccentPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    selectedRepo: SelectedRepoInfo?,
    initialPath: String? = null,
    initialIsFile: Boolean = false,
    initialSelectedPaths: List<String> = emptyList(),
    onOpenRepoSelector: () -> Unit,
    onStartDownload: (remotePath: String, config: DownloadConfig) -> Unit,
    onFetchDirectory: (suspend (owner: String, repo: String, path: String, branch: String) -> Result<List<GitHubContentDto>>)? = null,
    modifier: Modifier = Modifier
) {
    var downloadScopeIndex by remember(initialPath, initialIsFile, initialSelectedPaths) {
        mutableStateOf(
            if (initialSelectedPaths.isNotEmpty()) 3
            else if (initialPath.isNullOrEmpty()) 0
            else if (initialIsFile) 2
            else 1
        )
    }
    var remotePathInput by remember(initialPath) { mutableStateOf(initialPath ?: "") }
    var selectedPathsList by remember(initialSelectedPaths) { mutableStateOf(initialSelectedPaths) }
    var destinationTreeUri by remember { mutableStateOf<Uri?>(null) }
    var destinationDisplayName by remember { mutableStateOf<String?>(null) }
    var asZip by remember { mutableStateOf(false) }
    var createRepoFolder by remember { mutableStateOf(false) }
    var preserveStructure by remember { mutableStateOf(true) }
    var selectedPolicy by remember { mutableStateOf(OverwritePolicy.OVERWRITE) }
    var showPathPickerDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = showPathPickerDialog) {
        showPathPickerDialog = false
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val destPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            destinationTreeUri = it
            destinationDisplayName = it.lastPathSegment?.substringAfterLast(':') ?: "Selected Folder"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("download_lazy_column"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Download from GitHub",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Export repository files directly to your device storage as a ZIP archive or extracted folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. Target Repository & Branch
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_download_repo")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Repository Source",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = onOpenRepoSelector,
                            modifier = Modifier.testTag("download_switch_repo_btn")
                        ) {
                            Text("Switch Repo")
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = selectedRepo?.fullName ?: "No repository selected",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
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
                            text = "Branch: ${selectedRepo?.branch ?: "main"}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = GhDarkAccentPurple
                        )
                    }
                }
            }
        }

        // 2. Download Scope
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_download_scope")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Download Scope",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    TabRow(
                        selectedTabIndex = downloadScopeIndex.coerceAtMost(if (selectedPathsList.isNotEmpty()) 3 else 2),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Tab(
                            selected = downloadScopeIndex == 0,
                            onClick = { downloadScopeIndex = 0 },
                            text = { Text("Entire Repo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            modifier = Modifier.testTag("scope_tab_entire_repo")
                        )
                        Tab(
                            selected = downloadScopeIndex == 1,
                            onClick = { downloadScopeIndex = 1 },
                            text = { Text("Folder", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            modifier = Modifier.testTag("scope_tab_folder")
                        )
                        Tab(
                            selected = downloadScopeIndex == 2,
                            onClick = { downloadScopeIndex = 2 },
                            text = { Text("Single File", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            modifier = Modifier.testTag("scope_tab_single_file")
                        )
                        if (selectedPathsList.isNotEmpty()) {
                            Tab(
                                selected = downloadScopeIndex == 3,
                                onClick = { downloadScopeIndex = 3 },
                                text = { Text("Selected (${selectedPathsList.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.testTag("scope_tab_selected")
                            )
                        }
                    }

                    if (downloadScopeIndex == 3 && selectedPathsList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedPathsList.size} item(s) selected for download:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (onFetchDirectory != null) {
                                TextButton(
                                    onClick = { showPathPickerDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.testTag("download_add_item_btn")
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add Item", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            selectedPathsList.take(5).forEach { path ->
                                Text(
                                    text = "• $path",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selectedPathsList.size > 5) {
                                Text(
                                    text = "... and ${selectedPathsList.size - 5} more",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else if (downloadScopeIndex != 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = remotePathInput,
                                onValueChange = { remotePathInput = it },
                                label = { Text(if (downloadScopeIndex == 1) "Remote Folder Path (e.g. app/src)" else "Remote File Path (e.g. README.md)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("download_remote_path_input")
                            )

                            if (onFetchDirectory != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledTonalButton(
                                    onClick = { showPathPickerDialog = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .height(56.dp)
                                        .testTag("download_browse_path_button")
                                ) {
                                    Icon(
                                        imageVector = if (downloadScopeIndex == 1) Icons.Default.FolderOpen else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Browse", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Local Destination Picker
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_download_destination")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Storage Destination",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Select the folder on your device where downloaded files or archives will be saved.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { destPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth().testTag("pick_dest_folder_button")
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(destinationDisplayName ?: "Choose Destination Folder")
                    }

                    if (destinationTreeUri != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Destination: $destinationDisplayName",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GhDarkAccentGreen
                        )
                    }
                }
            }
        }

        // 4. Download Options
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_download_options")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Packaging & Overwrite Rules",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Download as ZIP Archive", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Saves a single compressed .zip file directly into the destination folder", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = asZip,
                            onCheckedChange = { asZip = it },
                            modifier = Modifier.testTag("as_zip_switch")
                        )
                    }

                    if (!asZip) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Create Repository Folder", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Places downloaded content inside a '${selectedRepo?.name ?: "repo"}' folder", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = createRepoFolder,
                                onCheckedChange = { createRepoFolder = it },
                                modifier = Modifier.testTag("create_repo_folder_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Preserve Directory Hierarchy", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Recreates subfolders locally matching the GitHub tree", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = preserveStructure,
                                onCheckedChange = { preserveStructure = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Existing File Collision Policy", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedPolicy == OverwritePolicy.OVERWRITE,
                                onClick = { selectedPolicy = OverwritePolicy.OVERWRITE },
                                modifier = Modifier.testTag("overwrite_policy_overwrite")
                            )
                            Text("Overwrite", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioButton(
                                selected = selectedPolicy == OverwritePolicy.SKIP,
                                onClick = { selectedPolicy = OverwritePolicy.SKIP },
                                modifier = Modifier.testTag("overwrite_policy_skip")
                            )
                            Text("Skip existing", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioButton(
                                selected = selectedPolicy == OverwritePolicy.KEEP_BOTH,
                                onClick = { selectedPolicy = OverwritePolicy.KEEP_BOTH },
                                modifier = Modifier.testTag("overwrite_policy_keep_both")
                            )
                            Text("Keep both", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Start Download Action
                    val isStartEnabled = destinationTreeUri != null && selectedRepo != null && (
                        downloadScopeIndex == 0 ||
                        (downloadScopeIndex == 3 && selectedPathsList.isNotEmpty()) ||
                        remotePathInput.isNotBlank()
                    )

                    Button(
                        onClick = {
                            if (destinationTreeUri != null) {
                                val (targetPath, scope, paths) = when (downloadScopeIndex) {
                                    0 -> Triple("", com.example.domain.model.DownloadScope.REPOSITORY, emptyList<String>())
                                    1 -> Triple(remotePathInput.trim(), com.example.domain.model.DownloadScope.DIRECTORY, emptyList<String>())
                                    2 -> Triple(remotePathInput.trim(), com.example.domain.model.DownloadScope.SINGLE_FILE, emptyList<String>())
                                    3 -> Triple(if (selectedPathsList.size == 1) selectedPathsList.first() else "", com.example.domain.model.DownloadScope.SELECTED_ITEMS, selectedPathsList)
                                    else -> Triple(remotePathInput.trim(), com.example.domain.model.DownloadScope.DIRECTORY, emptyList<String>())
                                }

                                val config = DownloadConfig(
                                    destinationTreeUri = destinationTreeUri!!,
                                    asZip = asZip,
                                    preserveStructure = preserveStructure,
                                    createRepoFolder = createRepoFolder,
                                    overwritePolicy = selectedPolicy,
                                    downloadScope = scope,
                                    selectedPaths = paths
                                )
                                onStartDownload(targetPath, config)
                            }
                        },
                        enabled = isStartEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("execute_download_button")
                    ) {
                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (asZip) "Download as ZIP" else "Download & Extract Files",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // GitHub Path Picker Dialog
    if (showPathPickerDialog && onFetchDirectory != null) {
        val pickerMode = when (downloadScopeIndex) {
            1 -> GitHubPickerMode.DIRECTORIES_ONLY
            2 -> GitHubPickerMode.FILES_ONLY
            3 -> GitHubPickerMode.SELECTED_ITEM
            else -> GitHubPickerMode.DIRECTORIES_ONLY
        }

        GitHubPathPickerDialog(
            selectedRepo = selectedRepo,
            initialPath = if (downloadScopeIndex == 3) "" else remotePathInput,
            pickerMode = pickerMode,
            onFetchDirectory = onFetchDirectory,
            onDismiss = { showPathPickerDialog = false },
            onPathConfirmed = { chosenPath ->
                if (downloadScopeIndex == 3) {
                    val clean = chosenPath.trim().trimStart('/').trimEnd('/')
                    if (clean.isNotEmpty() && !selectedPathsList.contains(clean)) {
                        selectedPathsList = selectedPathsList + clean
                    }
                } else {
                    remotePathInput = chosenPath
                }
                showPathPickerDialog = false
            }
        )
    }
}
