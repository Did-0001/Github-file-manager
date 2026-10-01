package com.example.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import com.example.data.cache.CacheExpirationPolicy
import com.example.data.cache.CacheStats
import com.example.data.local.SelectedRepoInfo
import com.example.data.remote.RateLimitTracker
import com.example.ui.theme.*

@Composable
fun SettingsScreen(
    isAuthenticated: Boolean,
    authUser: Pair<String, String?>?,
    selectedRepo: SelectedRepoInfo?,
    onOpenAuth: () -> Unit,
    onSignOut: () -> Unit,
    onOpenRepoSelector: () -> Unit,
    cacheStats: CacheStats = CacheStats(),
    onSetCacheExpirationPolicy: (CacheExpirationPolicy) -> Unit = {},
    onClearCache: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = showSignOutConfirm || showClearCacheConfirm) {
        showSignOutConfirm = false
        showClearCacheConfirm = false
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("settings_lazy_column"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings & Security",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Manage GitHub credentials, repository associations, and application security.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 1. Account & Authentication Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("settings_account_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GitHub Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (isAuthenticated && authUser != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(GhDarkAccentBlue.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = GhDarkAccentBlue,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "@${authUser.first}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (authUser.second != null) {
                                        Text(
                                            text = authUser.second!!,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = { showSignOutConfirm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = GhDarkAccentRed),
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Text("Sign Out")
                            }
                        }
                    } else {
                        Text(
                            text = "You are not currently signed in with a GitHub account.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onOpenAuth,
                            modifier = Modifier.fillMaxWidth().testTag("settings_sign_in_btn")
                        ) {
                            Text("Sign In with GitHub")
                        }
                    }
                }
            }
        }

        // 2. Active Repository
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("settings_repo_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Default Repository",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = onOpenRepoSelector,
                            modifier = Modifier.testTag("settings_switch_repo_btn")
                        ) {
                            Text("Change")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (selectedRepo != null) {
                        Text(
                            text = selectedRepo.fullName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Branch: ${selectedRepo.branch} • ${if (selectedRepo.isPrivate) "Private" else "Public"}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No repository chosen yet.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 3. Security & Keystore
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("settings_security_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = GhDarkAccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Hardware-Backed Security",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Authentication tokens are encrypted using AES-256-GCM with keys stored in the hardware-backed Android KeyStore.\n• Tokens are never logged or stored in plaintext.\n• Transfers are conducted exclusively over TLS 1.3 to official api.github.com endpoints.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // 4. Cache Architecture & Local Storage
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().testTag("settings_cache_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = GhDarkAccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cache Architecture & Local Storage",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Cache Expiration Policy",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cache_policy_selector"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CacheExpirationPolicy.entries.forEach { policy ->
                            val isSelected = cacheStats.policy == policy
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetCacheExpirationPolicy(policy) },
                                label = {
                                    Text(
                                        text = when (policy) {
                                            CacheExpirationPolicy.MINUTES_5 -> "5 Min"
                                            CacheExpirationPolicy.MINUTES_15 -> "15 Min"
                                            CacheExpirationPolicy.HOURS_1 -> "1 Hour"
                                            CacheExpirationPolicy.MANUAL -> "Manual"
                                        },
                                        fontSize = 11.sp
                                    )
                                },
                                modifier = Modifier.testTag("cache_policy_${policy.name.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Cache Stats Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Repositories",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${cacheStats.repoCount} cached",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("cache_repos_count")
                            )
                        }
                        Column {
                            Text(
                                text = "Branches",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${cacheStats.branchCount} cached",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("cache_branches_count")
                            )
                        }
                        Column {
                            Text(
                                text = "Directories",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${cacheStats.directoryCount} cached",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("cache_dirs_count")
                            )
                        }
                        Column {
                            Text(
                                text = "Files",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${cacheStats.fileContentCount} cached",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("cache_files_count")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Prominent Warning Box (Stale Data & Private Storage)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("cache_warning_card")
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = GhDarkAccentBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Stale Data & Local Storage Notice",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• Metadata caching reduces API calls and avoids GitHub rate limits.\n• Stale Data: Cached directory trees and branches may be briefly out of date with changes made on github.com. Use refresh to bypass cache.\n• Private Local Storage: Cached metadata and file contents are stored in private local app storage and are automatically cleared on sign-out.\n• Preflight & Transfers: Destructive preflight, diffs, commits, and branch-integrity transfers strictly bypass cache to ensure data integrity.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showClearCacheConfirm = true },
                        modifier = Modifier.fillMaxWidth().testTag("settings_clear_cache_btn"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GhDarkAccentRed)
                    ) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Local Cache Now")
                    }
                }
            }
        }

        // 5. Rate Limit Info
        item {
            RateLimitTracker.remaining?.let { remaining ->
                val limit = RateLimitTracker.limit ?: 5000
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().testTag("settings_rate_limit_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Speed, contentDescription = null, tint = GhDarkAccentBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GitHub API Rate Limits",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$remaining of $limit requests remaining in this cycle.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GhDarkAccentGreen
                        )
                    }
                }
            }
        }

        // 5. App Info
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GitHub File Manager v1.0.0",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Engineered with Jetpack Compose, Room & GitHub REST API",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            modifier = Modifier.testTag("sign_out_confirm_dialog"),
            title = { Text("Sign Out of GitHub?") },
            text = {
                Text("This will purge the encrypted token from Android KeyStore and clear local cached repository metadata.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutConfirm = false
                        onSignOut()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GhDarkAccentRed),
                    modifier = Modifier.testTag("confirm_sign_out_button")
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSignOutConfirm = false },
                    modifier = Modifier.testTag("cancel_sign_out_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            modifier = Modifier.testTag("clear_cache_confirm_dialog"),
            title = { Text("Clear Local Cache?") },
            text = {
                Text("This will purge all cached repository lists, branch data, directory listings, and local file previews from private storage. Subsequent views will fetch fresh data from GitHub REST API.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheConfirm = false
                        onClearCache()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GhDarkAccentRed),
                    modifier = Modifier.testTag("confirm_clear_cache_button")
                ) {
                    Text("Clear Cache")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearCacheConfirm = false },
                    modifier = Modifier.testTag("cancel_clear_cache_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
