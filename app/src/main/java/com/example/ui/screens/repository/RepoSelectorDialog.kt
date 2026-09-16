package com.example.ui.screens.repository

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.remote.dto.GitHubBranchDto
import com.example.data.remote.dto.GitHubRepoDto
import com.example.ui.theme.GhDarkAccentBlue
import com.example.ui.theme.GhDarkAccentGreen
import com.example.ui.theme.GhDarkAccentOrange
import com.example.ui.theme.GhDarkAccentPurple
import com.example.ui.theme.GhDarkAccentRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoSelectorDialog(
    repos: List<GitHubRepoDto>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRefreshRepos: () -> Unit,
    onSelectRepo: (GitHubRepoDto, String) -> Unit, // repo and branch
    onFetchBranches: suspend (String, String) -> List<GitHubBranchDto>,
    onCreateRepo: (String, String?, Boolean, (Boolean, String?) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedRepoForBranch by remember { mutableStateOf<GitHubRepoDto?>(null) }
    var branches by remember { mutableStateOf<List<GitHubBranchDto>>(emptyList()) }
    var isLoadingBranches by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filteredRepos = remember(repos, searchQuery) {
        if (searchQuery.isBlank()) repos
        else repos.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.fullName.contains(searchQuery, ignoreCase = true) ||
                    (it.description?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .padding(8.dp)
                .testTag("repo_selector_dialog"),
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
                    Column {
                        Text(
                            text = if (selectedRepoForBranch == null) "Select Repository" else "Select Branch",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (selectedRepoForBranch == null) "${repos.size} repositories available" else selectedRepoForBranch!!.fullName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row {
                        if (selectedRepoForBranch != null) {
                            IconButton(
                                onClick = { selectedRepoForBranch = null },
                                modifier = Modifier.testTag("back_to_repos_button")
                            ) {
                                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        } else {
                            IconButton(
                                onClick = onRefreshRepos,
                                modifier = Modifier.testTag("refresh_repos_button")
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.testTag("open_create_repo_button")
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "New Repo")
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(if (selectedRepoForBranch == null) "Search repositories..." else "Search branches...") },
                    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("repo_search_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading || isLoadingBranches) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (selectedRepoForBranch == null) {
                    // Repository list
                    if (filteredRepos.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (repos.isEmpty()) "No repositories found.\nEnsure your token has 'repo' permissions." else "No matches found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredRepos) { repo ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedRepoForBranch = repo
                                            isLoadingBranches = true
                                            scope.launch {
                                                branches = onFetchBranches(repo.owner.login, repo.name)
                                                isLoadingBranches = false
                                            }
                                        }
                                        .testTag("repo_item_${repo.name}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                                                    contentDescription = null,
                                                    tint = if (repo.isPrivate) GhDarkAccentOrange else GhDarkAccentGreen,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = repo.fullName,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            if (!repo.description.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = repo.description,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.ForkRight,
                                                    contentDescription = null,
                                                    tint = GhDarkAccentPurple,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "default: ${repo.defaultBranch}",
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Branch list for selectedRepoForBranch
                    val repo = selectedRepoForBranch!!
                    val filteredBranches = remember(branches, searchQuery) {
                        if (searchQuery.isBlank()) branches
                        else branches.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Quick option: Use default branch
                        item {
                            Surface(
                                color = GhDarkAccentBlue.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GhDarkAccentBlue.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectRepo(repo, repo.defaultBranch)
                                        onDismiss()
                                    }
                                    .testTag("branch_default_${repo.defaultBranch}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = GhDarkAccentBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "${repo.defaultBranch} (Default Branch)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = GhDarkAccentBlue
                                            )
                                        }
                                    }
                                    Text("Select", color = GhDarkAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        items(filteredBranches.filter { it.name != repo.defaultBranch }) { branch ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectRepo(repo, branch.name)
                                        onDismiss()
                                    }
                                    .testTag("branch_item_${branch.name}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.ForkRight,
                                            contentDescription = null,
                                            tint = GhDarkAccentPurple,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = branch.name,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "SHA: ${branch.commit.sha.take(7)}",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (branch.isProtected) {
                                        Surface(
                                            color = GhDarkAccentOrange.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "Protected",
                                                color = GhDarkAccentOrange,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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

    // Coroutine loader for branches
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(selectedRepoForBranch) {
        if (selectedRepoForBranch != null) {
            isLoadingBranches = true
            branches = onFetchBranches(selectedRepoForBranch!!.owner.login, selectedRepoForBranch!!.name)
            isLoadingBranches = false
        }
    }

    // Create Repo Sub-dialog
    if (showCreateDialog) {
        CreateRepoDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = onCreateRepo
        )
    }
}

@Composable
fun CreateRepoDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String?, Boolean, (Boolean, String?) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create GitHub Repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Repository Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_repo_name_input")
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Private Repository")
                    Switch(
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it },
                        modifier = Modifier.testTag("new_repo_private_switch")
                    )
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = error!!, color = GhDarkAccentRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isCreating = true
                    error = null
                    onCreate(name, description.takeIf { it.isNotBlank() }, isPrivate) { success, err ->
                        isCreating = false
                        if (success) {
                            onDismiss()
                        } else {
                            error = err ?: "Creation failed"
                        }
                    }
                },
                enabled = name.isNotBlank() && !isCreating,
                modifier = Modifier.testTag("confirm_create_repo_button")
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Create Repository")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
