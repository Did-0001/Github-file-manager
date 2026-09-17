package com.example.ui.screens.download

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import com.example.domain.model.DownloadConfig
import com.example.domain.model.OverwritePolicy
import com.example.ui.theme.GhDarkAccentBlue
import com.example.ui.theme.GhDarkAccentGreen
import com.example.ui.theme.GhDarkAccentPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    selectedRepo: SelectedRepoInfo?,
    initialPath: String? = null,
    initialIsFile: Boolean = false,
    onOpenRepoSelector: () -> Unit,
    onStartDownload: (remotePath: String, config: DownloadConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var downloadScopeIndex by remember(initialPath, initialIsFile) {
        mutableStateOf(
            if (initialPath.isNullOrEmpty()) 0
            else if (initialIsFile) 2
            else 1
        )
    }
    var remotePathInput by remember(initialPath) { mutableStateOf(initialPath ?: "") }
    var destinationTreeUri by remember { mutableStateOf<Uri?>(null) }
    var destinationDisplayName by remember { mutableStateOf<String?>(null) }
    var asZip by remember { mutableStateOf(false) }
    var preserveStructure by remember { mutableStateOf(true) }
    var selectedPolicy by remember { mutableStateOf(OverwritePolicy.OVERWRITE) }

    val destPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            destinationTreeUri = it
            destinationDisplayName = it.lastPathSegment?.substringAfterLast(':') ?: "Selected Folder"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
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
                modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Download Scope",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    TabRow(
                        selectedTabIndex = downloadScopeIndex,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Tab(
                            selected = downloadScopeIndex == 0,
                            onClick = { downloadScopeIndex = 0 },
                            text = { Text("Entire Repo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = downloadScopeIndex == 1,
                            onClick = { downloadScopeIndex = 1 },
                            text = { Text("Folder", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = downloadScopeIndex == 2,
                            onClick = { downloadScopeIndex = 2 },
                            text = { Text("Single File", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }

                    if (downloadScopeIndex != 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = remotePathInput,
                            onValueChange = { remotePathInput = it },
                            label = { Text(if (downloadScopeIndex == 1) "Remote Folder Path (e.g. app/src)" else "Remote File Path (e.g. README.md)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("download_remote_path_input")
                        )
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
                modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
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
                                onClick = { selectedPolicy = OverwritePolicy.OVERWRITE }
                            )
                            Text("Overwrite", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(16.dp))
                            RadioButton(
                                selected = selectedPolicy == OverwritePolicy.SKIP,
                                onClick = { selectedPolicy = OverwritePolicy.SKIP }
                            )
                            Text("Skip existing", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Start Download Action
                    Button(
                        onClick = {
                            if (destinationTreeUri != null) {
                                val targetPath = if (downloadScopeIndex == 0) "" else remotePathInput.trim()
                                val config = DownloadConfig(
                                    destinationTreeUri = destinationTreeUri!!,
                                    asZip = asZip,
                                    preserveStructure = preserveStructure,
                                    overwritePolicy = selectedPolicy
                                )
                                onStartDownload(targetPath, config)
                            }
                        },
                        enabled = destinationTreeUri != null && selectedRepo != null && (downloadScopeIndex == 0 || remotePathInput.isNotBlank()),
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
}
