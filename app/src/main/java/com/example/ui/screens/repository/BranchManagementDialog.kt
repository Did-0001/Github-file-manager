package com.example.ui.screens.repository

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import com.example.data.remote.dto.GitHubBranchDto
import com.example.data.repository.GitHubRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchManagementDialog(
    selectedRepo: SelectedRepoInfo?,
    branches: List<GitHubBranchDto>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRefreshBranches: () -> Unit,
    onSelectBranch: (String) -> Unit,
    onCreateBranch: (name: String, sourceBranch: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onRenameBranch: (oldName: String, newName: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onDeleteBranch: (name: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onSetDefaultBranch: (name: String, onComplete: (Boolean, String?) -> Unit) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }

    // Dialog states
    var showCreateDialog by remember { mutableStateOf(false) }
    var branchToRename by remember { mutableStateOf<GitHubBranchDto?>(null) }
    var branchToDelete by remember { mutableStateOf<GitHubBranchDto?>(null) }
    var branchToSetDefault by remember { mutableStateOf<GitHubBranchDto?>(null) }
    var branchForDetails by remember { mutableStateOf<GitHubBranchDto?>(null) }

    var actionError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showCreateDialog || branchToRename != null || branchToDelete != null || branchToSetDefault != null || branchForDetails != null) {
        showCreateDialog = false
        branchToRename = null
        branchToDelete = null
        branchToSetDefault = null
        branchForDetails = null
        actionError = null
    }

    val currentBranchName = selectedRepo?.branch ?: "main"
    val defaultBranchName = selectedRepo?.defaultBranch ?: "main"

    val filteredBranches = remember(branches, searchQuery) {
        if (searchQuery.isBlank()) branches
        else branches.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .padding(8.dp)
                .testTag("branch_management_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Branch Management",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (selectedRepo != null) {
                            Text(
                                text = selectedRepo.fullName,
                                style = MaterialTheme.typography.bodySmall,
                                color = GhDarkAccentBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onRefreshBranches,
                            modifier = Modifier.testTag("branch_mgmt_refresh_button")
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Branches")
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("branch_mgmt_close_button")
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Create branch action button and search bar row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search branches...") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("branch_search_input")
                    )

                    Button(
                        onClick = {
                            actionError = null
                            showCreateDialog = true
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("branch_mgmt_create_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ Create", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (actionError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = actionError ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { actionError = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("branch_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Section: Current active branch
                        val activeBranchDto = branches.find { it.name == currentBranchName }
                        if (activeBranchDto != null && (searchQuery.isBlank() || activeBranchDto.name.contains(searchQuery, ignoreCase = true))) {
                            item {
                                Surface(
                                    color = GhDarkAccentPurple.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.5.dp, GhDarkAccentPurple.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("current_branch_card")
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Active Branch",
                                                    tint = GhDarkAccentPurple,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = activeBranchDto.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            Surface(
                                                color = GhDarkAccentPurple.copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "CURRENT",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = GhDarkAccentPurple,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "HEAD: ${activeBranchDto.commit.sha.take(7)}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (activeBranchDto.name == defaultBranchName) {
                                                Surface(
                                                    color = GhDarkAccentBlue.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "DEFAULT",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = GhDarkAccentBlue,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            if (activeBranchDto.isProtected) {
                                                Surface(
                                                    color = GhDarkAccentOrange.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "PROTECTED",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = GhDarkAccentOrange,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Branch actions row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = { branchForDetails = activeBranchDto },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(32.dp).testTag("branch_mgmt_details_${activeBranchDto.name}")
                                            ) {
                                                Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Details", fontSize = 11.sp)
                                            }

                                            Spacer(modifier = Modifier.width(4.dp))

                                            TextButton(
                                                onClick = {
                                                    actionError = null
                                                    branchToRename = activeBranchDto
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(32.dp).testTag("branch_mgmt_rename_${activeBranchDto.name}")
                                            ) {
                                                Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Rename", fontSize = 11.sp)
                                            }

                                            if (activeBranchDto.name != defaultBranchName) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                TextButton(
                                                    onClick = {
                                                        actionError = null
                                                        branchToSetDefault = activeBranchDto
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(32.dp).testTag("branch_mgmt_set_default_${activeBranchDto.name}")
                                                ) {
                                                    Icon(imageVector = Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Set Default", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section: Other branches
                        val otherBranches = filteredBranches.filter { it.name != currentBranchName }
                        items(otherBranches, key = { it.name }) { branch ->
                            val isDefault = branch.name == defaultBranchName

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("branch_mgmt_item_${branch.name}")
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ForkRight,
                                                contentDescription = null,
                                                tint = GhDarkAccentPurple,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = branch.name,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isDefault) {
                                                Surface(
                                                    color = GhDarkAccentBlue.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "DEFAULT",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = GhDarkAccentBlue,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            if (branch.isProtected) {
                                                Surface(
                                                    color = GhDarkAccentOrange.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "PROTECTED",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = GhDarkAccentOrange,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "HEAD: ${branch.commit.sha.take(7)}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Action bar for branch item
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Switch / Select
                                        Button(
                                            onClick = {
                                                onSelectBranch(branch.name)
                                                onDismiss()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(30.dp).testTag("branch_mgmt_switch_${branch.name}")
                                        ) {
                                            Text("Switch", fontSize = 11.sp)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        // Details
                                        IconButton(
                                            onClick = { branchForDetails = branch },
                                            modifier = Modifier.size(30.dp).testTag("branch_mgmt_details_${branch.name}")
                                        ) {
                                            Icon(imageVector = Icons.Default.Info, contentDescription = "Branch Details", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                        }

                                        // Rename
                                        IconButton(
                                            onClick = {
                                                actionError = null
                                                branchToRename = branch
                                            },
                                            modifier = Modifier.size(30.dp).testTag("branch_mgmt_rename_${branch.name}")
                                        ) {
                                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Rename Branch", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                        }

                                        // Set as Default (if not already default)
                                        if (!isDefault) {
                                            IconButton(
                                                onClick = {
                                                    actionError = null
                                                    branchToSetDefault = branch
                                                },
                                                modifier = Modifier.size(30.dp).testTag("branch_mgmt_set_default_${branch.name}")
                                            ) {
                                                Icon(imageVector = Icons.Default.Star, contentDescription = "Set as Default Branch", tint = GhDarkAccentBlue, modifier = Modifier.size(16.dp))
                                            }
                                        }

                                        // Delete (disabled if default)
                                        IconButton(
                                            onClick = {
                                                if (!isDefault) {
                                                    actionError = null
                                                    branchToDelete = branch
                                                }
                                            },
                                            enabled = !isDefault,
                                            modifier = Modifier.size(30.dp).testTag("branch_mgmt_delete_${branch.name}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = if (isDefault) "Default branch cannot be deleted" else "Delete Branch",
                                                tint = if (isDefault) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else GhDarkAccentRed,
                                                modifier = Modifier.size(16.dp)
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
    }

    // CREATE BRANCH SUB-DIALOG
    if (showCreateDialog) {
        CreateBranchDialog(
            currentBranch = currentBranchName,
            existingBranches = branches.map { it.name },
            onDismiss = { showCreateDialog = false },
            onConfirm = { newName, sourceBranch ->
                onCreateBranch(newName, sourceBranch) { success, error ->
                    if (success) {
                        showCreateDialog = false
                        onRefreshBranches()
                    } else {
                        actionError = error ?: "Failed to create branch."
                    }
                }
            }
        )
    }

    // RENAME BRANCH SUB-DIALOG
    if (branchToRename != null) {
        val target = branchToRename!!
        RenameBranchDialog(
            targetBranch = target.name,
            existingBranches = branches.map { it.name },
            onDismiss = { branchToRename = null },
            onConfirm = { newName ->
                onRenameBranch(target.name, newName) { success, error ->
                    if (success) {
                        branchToRename = null
                        onRefreshBranches()
                    } else {
                        actionError = error ?: "Failed to rename branch '${target.name}'."
                    }
                }
            }
        )
    }

    // DELETE BRANCH CONFIRMATION DIALOG
    if (branchToDelete != null) {
        val target = branchToDelete!!
        AlertDialog(
            onDismissRequest = { branchToDelete = null },
            title = { Text("Delete Branch") },
            text = {
                Column {
                    Text("Are you sure you want to delete branch '${target.name}'?")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "HEAD SHA: ${target.commit.sha.take(7)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (target.isProtected) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Warning: This branch is protected. Deletion may be rejected by GitHub permissions.",
                            fontSize = 12.sp,
                            color = GhDarkAccentOrange
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = target.name
                        onDeleteBranch(name) { success, error ->
                            if (success) {
                                branchToDelete = null
                                onRefreshBranches()
                            } else {
                                actionError = error ?: "Failed to delete branch '$name'."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete_branch_confirm_btn")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { branchToDelete = null }) {
                    Text("Cancel")
                }
            },
            modifier = Modifier.testTag("delete_branch_dialog")
        )
    }

    // SET DEFAULT BRANCH CONFIRMATION DIALOG
    if (branchToSetDefault != null) {
        val target = branchToSetDefault!!
        AlertDialog(
            onDismissRequest = { branchToSetDefault = null },
            title = { Text("Set as Default Branch") },
            text = {
                Text("Change the default repository branch to '${target.name}'? This affects the default base for new branches, pulls, and repository cloning.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = target.name
                        onSetDefaultBranch(name) { success, error ->
                            if (success) {
                                branchToSetDefault = null
                                onRefreshBranches()
                            } else {
                                actionError = error ?: "Failed to set default branch to '$name'."
                            }
                        }
                    },
                    modifier = Modifier.testTag("set_default_branch_confirm_btn")
                ) {
                    Text("Set as Default")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { branchToSetDefault = null }) {
                    Text("Cancel")
                }
            },
            modifier = Modifier.testTag("set_default_branch_dialog")
        )
    }

    // BRANCH DETAILS MODAL
    if (branchForDetails != null) {
        val target = branchForDetails!!
        AlertDialog(
            onDismissRequest = { branchForDetails = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.ForkRight, contentDescription = null, tint = GhDarkAccentPurple)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(target.name, fontFamily = FontFamily.Monospace)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Full HEAD Commit SHA:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(target.commit.sha, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Default Branch:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (target.name == defaultBranchName) "Yes" else "No", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Branch Protection:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (target.isProtected) "Protected" else "Unprotected", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (target.isProtected) GhDarkAccentOrange else GhDarkAccentGreen)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { branchForDetails = null }) {
                    Text("Close")
                }
            },
            modifier = Modifier.testTag("branch_details_dialog")
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateBranchDialog(
    currentBranch: String,
    existingBranches: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (newName: String, sourceBranch: String) -> Unit
) {
    var newBranchName by remember { mutableStateOf("") }
    var selectedSourceBranch by remember { mutableStateOf(currentBranch) }
    var sourceDropdownExpanded by remember { mutableStateOf(false) }

    val validationError = remember(newBranchName, existingBranches) {
        if (newBranchName.isEmpty()) null
        else GitHubRepository.validateBranchName(newBranchName, existingBranches)
    }

    val canCreate = newBranchName.isNotBlank() && validationError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Branch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // New branch name input
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("New Branch Name") },
                    placeholder = { Text("e.g. feature/my-feature") },
                    isError = validationError != null,
                    supportingText = {
                        if (validationError != null) {
                            Text(validationError, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Branch will be created from source branch's HEAD commit.")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_branch_name_input")
                )

                // Source branch dropdown
                ExposedDropdownMenuBox(
                    expanded = sourceDropdownExpanded,
                    onExpandedChange = { sourceDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedSourceBranch,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Source Branch") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .testTag("source_branch_selector")
                    )

                    ExposedDropdownMenu(
                        expanded = sourceDropdownExpanded,
                        onDismissRequest = { sourceDropdownExpanded = false }
                    ) {
                        existingBranches.forEach { branchName ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(branchName, fontFamily = FontFamily.Monospace)
                                        if (branchName == currentBranch) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("(current)", fontSize = 11.sp, color = GhDarkAccentPurple)
                                        }
                                    }
                                },
                                onClick = {
                                    selectedSourceBranch = branchName
                                    sourceDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("source_branch_item_$branchName")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canCreate) {
                        onConfirm(newBranchName.trim(), selectedSourceBranch)
                    }
                },
                enabled = canCreate,
                modifier = Modifier.testTag("create_branch_confirm_btn")
            ) {
                Text("Create Branch")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        modifier = Modifier.testTag("create_branch_dialog")
    )
}

@Composable
fun RenameBranchDialog(
    targetBranch: String,
    existingBranches: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var newBranchName by remember { mutableStateOf("") }

    val validationError = remember(newBranchName, existingBranches) {
        if (newBranchName.isEmpty()) null
        else GitHubRepository.validateBranchName(newBranchName, existingBranches)
    }

    val canRename = newBranchName.isNotBlank() && validationError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Branch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Current name: $targetBranch", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("New Branch Name") },
                    placeholder = { Text("Enter new name") },
                    isError = validationError != null,
                    supportingText = {
                        if (validationError != null) {
                            Text(validationError, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_branch_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canRename) {
                        onConfirm(newBranchName.trim())
                    }
                },
                enabled = canRename,
                modifier = Modifier.testTag("rename_branch_confirm_btn")
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        modifier = Modifier.testTag("rename_branch_dialog")
    )
}
