package com.example.ui.screens.upload

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SelectedRepoInfo
import com.example.domain.model.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    selectedRepo: SelectedRepoInfo?,
    onScanFolder: (Uri) -> Unit,
    onScanFiles: (List<Uri>) -> Unit,
    scannedFiles: List<FileScanItem>,
    excludedItems: List<DiffItem>,
    isScanning: Boolean,
    ignoreRules: IgnoreRules,
    onUpdateIgnoreRules: (IgnoreRules) -> Unit,
    onStartUpload: (destinationDir: String, commitMessage: String, isWipe: Boolean) -> Unit,
    onOpenRepoSelector: () -> Unit,
    diffReport: DiffReport?,
    isCalculatingDiff: Boolean,
    preflightReport: PreflightReport?,
    onRunPreflight: (destinationDir: String, isWipe: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var destinationPath by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("Upload files via GitHub File Manager") }
    var isWipeEnabled by remember { mutableStateOf(false) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var showIgnoreRulesDialog by remember { mutableStateOf(false) }
    var showDiffDetailsSheet by remember { mutableStateOf(false) }
    var showPreflightDetailsSheet by remember { mutableStateOf(false) }
    var showLocalFilesList by remember { mutableStateOf(false) }

    // SAF Launchers
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { onScanFolder(it) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onScanFiles(uris)
    }

    val totalBytes = scannedFiles.sumOf { it.sizeBytes }

    // Auto-update commit message when files change
    LaunchedEffect(scannedFiles.size) {
        if (scannedFiles.isNotEmpty()) {
            commitMessage = "Upload ${scannedFiles.size} files via GitHub File Manager"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header
        item {
            Column {
                Text(
                    text = "Upload to GitHub",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select local folders or files, review destination and changes, and commit safely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. SOURCE FACT
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_upload_source")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(GhDarkAccentBlue.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("1", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GhDarkAccentBlue)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Source Files",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = { showIgnoreRulesDialog = true },
                            modifier = Modifier.size(32.dp).testTag("open_ignore_rules_button")
                        ) {
                            Icon(imageVector = Icons.Default.Tune, contentDescription = "Ignore Rules", tint = GhDarkAccentBlue)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f).testTag("pick_folder_button")
                        ) {
                            Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Folder")
                        }

                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f).testTag("pick_files_button")
                        ) {
                            Icon(imageVector = Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Files")
                        }
                    }

                    if (isScanning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning local files & applying ignore filters...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (scannedFiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${scannedFiles.size} files selected (${formatBytes(totalBytes)})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TextButton(
                                        onClick = { showLocalFilesList = !showLocalFilesList },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(if (showLocalFilesList) "Hide List" else "View All")
                                    }
                                }

                                if (excludedItems.isNotEmpty()) {
                                    Text(
                                        text = "${excludedItems.size} items excluded by .gitignore rules",
                                        fontSize = 11.sp,
                                        color = GhDarkAccentOrange
                                    )
                                }

                                AnimatedVisibility(visible = showLocalFilesList) {
                                    Column(modifier = Modifier.padding(top = 8.dp)) {
                                        scannedFiles.take(10).forEach { item ->
                                            Text(
                                                text = "${item.relativePath} (${formatBytes(item.sizeBytes)})",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (scannedFiles.size > 10) {
                                            Text(
                                                text = "... and ${scannedFiles.size - 10} more files",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.primary
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

        // 2. DESTINATION FACT
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_upload_destination")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(GhDarkAccentPurple.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("2", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GhDarkAccentPurple)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GitHub Destination",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        TextButton(
                            onClick = onOpenRepoSelector,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.testTag("upload_change_repo_btn")
                        ) {
                            Text("Switch Repo")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Target: ${selectedRepo?.fullName ?: "None selected"} (${selectedRepo?.branch ?: "main"})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = destinationPath,
                        onValueChange = { destinationPath = it },
                        label = { Text("Destination Directory in Repository") },
                        placeholder = { Text("e.g. / or src/main or docs") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Folder, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("destination_path_input")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Path Preview
                    val cleanDest = destinationPath.trim().trimStart('/').trimEnd('/')
                    val previewDest = if (cleanDest.isEmpty()) "/" else "/$cleanDest/"
                    Text(
                        text = "Files will land at: $previewDest",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = GhDarkAccentBlue
                    )
                }
            }
        }

        // 3. CHANGES / DIFF FACT
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_upload_diff")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(GhDarkAccentGreen.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("3", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GhDarkAccentGreen)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Changes Preview",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (scannedFiles.isNotEmpty()) {
                            TextButton(
                                onClick = { onRunPreflight(destinationPath, isWipeEnabled) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.testTag("calculate_diff_btn")
                            ) {
                                Text("Calculate Diff")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isCalculatingDiff) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Comparing local files against branch tree...", fontSize = 12.sp)
                        }
                    } else if (diffReport != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DiffPill("+ ${diffReport.added}", GhDarkAccentGreen)
                            DiffPill("~ ${diffReport.modified}", GhDarkAccentBlue)
                            if (diffReport.deleted > 0) {
                                DiffPill("- ${diffReport.deleted}", GhDarkAccentRed)
                            }
                            DiffPill("= ${diffReport.unchanged}", Color.Gray)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { showDiffDetailsSheet = true },
                            modifier = Modifier.fillMaxWidth().testTag("view_diff_details_button")
                        ) {
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Inspect Itemized Diff List")
                        }
                    } else {
                        Text(
                            text = if (scannedFiles.isEmpty()) "Select files to preview changes." else "Click 'Calculate Diff' to compare local files against remote branch.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4. OPTIONS & COMMIT FACT
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("card_upload_commit")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(GhDarkAccentOrange.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("4", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GhDarkAccentOrange)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Commit & Upload",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Wipe Checkbox & Warning
                    Surface(
                        color = if (isWipeEnabled) GhDarkAccentRed.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isWipeEnabled) GhDarkAccentRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Wipe repository branch before upload",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isWipeEnabled) GhDarkAccentRed else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Replaces remote branch contents completely with this upload. Non-matching files are removed.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Checkbox(
                                checked = isWipeEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showWipeConfirmDialog = true
                                    } else {
                                        isWipeEnabled = false
                                    }
                                },
                                modifier = Modifier.testTag("wipe_mode_checkbox")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit Message *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("commit_message_input")
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Main Upload Button
                    Button(
                        onClick = {
                            onStartUpload(destinationPath, commitMessage, isWipeEnabled)
                        },
                        enabled = scannedFiles.isNotEmpty() && selectedRepo != null && commitMessage.isNotBlank() && (preflightReport?.errorsCount ?: 0) == 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("execute_upload_button")
                    ) {
                        Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isWipeEnabled) "Wipe & Upload ${scannedFiles.size} Files" else "Commit & Upload ${scannedFiles.size} Files",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (preflightReport != null && preflightReport.errorsCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${preflightReport.errorsCount} blocking preflight error(s) must be resolved before uploading.",
                            color = GhDarkAccentRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // Wipe Confirmation Dialog
    if (showWipeConfirmDialog) {
        WipeConfirmationDialog(
            repo = selectedRepo?.fullName ?: "",
            branch = selectedRepo?.branch ?: "",
            onConfirm = {
                isWipeEnabled = true
                showWipeConfirmDialog = false
            },
            onDismiss = {
                isWipeEnabled = false
                showWipeConfirmDialog = false
            }
        )
    }

    // Ignore Rules Dialog
    if (showIgnoreRulesDialog) {
        IgnoreRulesDialog(
            rules = ignoreRules,
            onSave = { updated ->
                onUpdateIgnoreRules(updated)
                showIgnoreRulesDialog = false
            },
            onDismiss = { showIgnoreRulesDialog = false }
        )
    }

    // Itemized Diff Dialog / Sheet
    if (showDiffDetailsSheet && diffReport != null) {
        DiffDetailsDialog(
            diffReport = diffReport,
            onDismiss = { showDiffDetailsSheet = false }
        )
    }
}

@Composable
fun DiffPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun WipeConfirmationDialog(
    repo: String,
    branch: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmedCheckbox by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = GhDarkAccentRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Confirm Wipe-Before-Upload")
            }
        },
        text = {
            Column {
                Text(
                    text = "You have enabled Wipe mode for $repo on branch '$branch'.",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This operation will create an orphaned Git tree root containing ONLY the files in your current upload. Any existing files on this branch that are not in this upload will be wiped from this commit.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = confirmedCheckbox,
                        onCheckedChange = { confirmedCheckbox = it },
                        modifier = Modifier.testTag("wipe_confirm_ack_checkbox")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "I understand that existing repository files will be removed from this branch.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GhDarkAccentRed
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmedCheckbox,
                colors = ButtonDefaults.buttonColors(containerColor = GhDarkAccentRed),
                modifier = Modifier.testTag("confirm_wipe_action_btn")
            ) {
                Text("Enable Wipe Mode")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun IgnoreRulesDialog(
    rules: IgnoreRules,
    onSave: (IgnoreRules) -> Unit,
    onDismiss: () -> Unit
) {
    var ignoreGit by remember { mutableStateOf(rules.excludeGit) }
    var ignoreBuild by remember { mutableStateOf(rules.excludeBuildAndGradle) }
    var ignoreNodeModules by remember { mutableStateOf(rules.excludeNodeModules) }
    var ignoreHidden by remember { mutableStateOf(rules.excludeHiddenFiles) }
    var ignoreIde by remember { mutableStateOf(rules.excludeIdeFiles) }
    var customPatternsText by remember { mutableStateOf(rules.customPatterns.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ignore Rules (.gitignore)") },
        text = {
            Column {
                IgnoreToggleRow("Ignore .git folder", ignoreGit) { ignoreGit = it }
                IgnoreToggleRow("Ignore build / .gradle / APKs", ignoreBuild) { ignoreBuild = it }
                IgnoreToggleRow("Ignore node_modules folder", ignoreNodeModules) { ignoreNodeModules = it }
                IgnoreToggleRow("Ignore .idea / .vscode / IDE files", ignoreIde) { ignoreIde = it }
                IgnoreToggleRow("Ignore hidden files (.DS_Store, etc.)", ignoreHidden) { ignoreHidden = it }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Custom Patterns (one per line):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = customPatternsText,
                    onValueChange = { customPatternsText = it },
                    placeholder = { Text("*.log\n*.tmp\nsecrets/") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("custom_ignore_patterns_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val patterns = customPatternsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(
                        rules.copy(
                            excludeGit = ignoreGit,
                            excludeBuildAndGradle = ignoreBuild,
                            excludeNodeModules = ignoreNodeModules,
                            excludeHiddenFiles = ignoreHidden,
                            excludeIdeFiles = ignoreIde,
                            customPatterns = patterns
                        )
                    )
                },
                modifier = Modifier.testTag("save_ignore_rules_button")
            ) {
                Text("Save Rules")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun IgnoreToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun DiffDetailsDialog(
    diffReport: DiffReport,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Itemized Changes (${diffReport.items.size} files)") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(diffReport.items) { item ->
                    val (badgeColor, badgeText) = when (item.changeType) {
                        DiffChangeType.ADDED -> Pair(GhDarkAccentGreen, "ADDED")
                        DiffChangeType.MODIFIED -> Pair(GhDarkAccentBlue, "MODIFIED")
                        DiffChangeType.DELETED -> Pair(GhDarkAccentRed, "DELETED")
                        DiffChangeType.UNCHANGED -> Pair(Color.Gray, "UNCHANGED")
                        DiffChangeType.EXCLUDED -> Pair(GhDarkAccentOrange, "EXCLUDED")
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.remotePath,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.reason != null) {
                                    Text(
                                        text = item.reason,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
