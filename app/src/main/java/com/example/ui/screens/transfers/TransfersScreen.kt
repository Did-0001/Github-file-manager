package com.example.ui.screens.transfers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.TransferEntity
import com.example.ui.components.EmptyStateView
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TransfersScreen(
    transfers: List<TransferEntity>,
    onPauseTransfer: (String) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onRetryTransfer: (String) -> Unit,
    onDeleteTransfer: (String) -> Unit,
    onClearAll: () -> Unit,
    getSpeedForTransfer: (String) -> Long,
    modifier: Modifier = Modifier
) {
    val activeTransfers = remember(transfers) {
        transfers.filter {
            it.status in listOf("QUEUED", "PREPARING", "UPLOADING", "DOWNLOADING", "COMMITTING", "VERIFYING", "PAUSED")
        }
    }
    val pastTransfers = remember(transfers) {
        transfers.filter {
            it.status in listOf("COMPLETED", "FAILED", "CANCELLED")
        }
    }

    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Transfer Center",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${activeTransfers.size} active • ${pastTransfers.size} finished",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (transfers.isNotEmpty()) {
                        IconButton(
                            onClick = onClearAll,
                            modifier = Modifier.testTag("clear_all_transfers_btn")
                        ) {
                            Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear History")
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { padding ->
        if (transfers.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.SwapVert,
                title = "No Transfer History",
                subtitle = "Active and completed uploads and downloads will appear here with live speed, progress, and commit tracking.",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ACTIVE TRANSFERS SECTION
                if (activeTransfers.isNotEmpty()) {
                    item {
                        Text(
                            text = "Active Transfers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    items(activeTransfers, key = { it.id }) { transfer ->
                        val speedBytes = getSpeedForTransfer(transfer.id)
                        val progressFraction = if (transfer.totalFiles > 0) {
                            (transfer.processedFiles.toFloat() / transfer.totalFiles.toFloat()).coerceIn(0f, 1f)
                        } else if (transfer.totalBytes > 0) {
                            (transfer.processedBytes.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GhDarkAccentBlue.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().testTag("active_transfer_${transfer.id}")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(
                                                    if (transfer.type == "UPLOAD") GhDarkAccentGreen.copy(alpha = 0.15f) else GhDarkAccentBlue.copy(alpha = 0.15f),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (transfer.type == "UPLOAD") Icons.Default.CloudUpload else Icons.Default.CloudDownload,
                                                contentDescription = null,
                                                tint = if (transfer.type == "UPLOAD") GhDarkAccentGreen else GhDarkAccentBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "${transfer.type} • ${transfer.repoOwner}/${transfer.repoName}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Branch: ${transfer.branch} → ${transfer.destPath}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    StatusBadge(status = transfer.status)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Progress Bar
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    color = if (transfer.type == "UPLOAD") GhDarkAccentGreen else GhDarkAccentBlue,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${transfer.processedFiles} of ${transfer.totalFiles} files (${formatBytes(transfer.processedBytes)})",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (speedBytes > 0) {
                                        Text(
                                            text = "${formatBytes(speedBytes)}/s",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = GhDarkAccentBlue
                                        )
                                    }
                                }

                                if (transfer.currentFile != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Current: ${transfer.currentFile}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (transfer.status == "PAUSED") {
                                        OutlinedButton(
                                            onClick = { onRetryTransfer(transfer.id) },
                                            modifier = Modifier.testTag("resume_transfer_btn")
                                        ) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Resume")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { onPauseTransfer(transfer.id) },
                                            modifier = Modifier.testTag("pause_transfer_btn")
                                        ) {
                                            Icon(imageVector = Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Pause")
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(
                                        onClick = { onCancelTransfer(transfer.id) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = GhDarkAccentRed),
                                        modifier = Modifier.testTag("cancel_transfer_btn")
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        }
                    }
                }

                // PAST TRANSFERS SECTION
                if (pastTransfers.isNotEmpty()) {
                    item {
                        Text(
                            text = "Transfer History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(pastTransfers, key = { it.id }) { transfer ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (transfer.type == "UPLOAD") Icons.Default.CloudUpload else Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            tint = if (transfer.status == "COMPLETED") GhDarkAccentGreen else if (transfer.status == "FAILED") GhDarkAccentRed else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${transfer.type} • ${transfer.repoOwner}/${transfer.repoName}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    StatusBadge(status = transfer.status)
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "${transfer.totalFiles} files (${formatBytes(transfer.totalBytes)}) • Destination: ${transfer.destPath}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (transfer.commitSha != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Commit: ${transfer.commitSha.take(8)}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = GhDarkAccentPurple
                                        )
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(transfer.commitSha))
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy SHA", modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }

                                if (transfer.errorMessage != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Error: ${transfer.errorMessage}",
                                        fontSize = 11.sp,
                                        color = GhDarkAccentRed,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(transfer.createdAt))
                                    Text(text = dateStr, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                    Row {
                                        if (transfer.status == "FAILED") {
                                            TextButton(
                                                onClick = { onRetryTransfer(transfer.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) {
                                                Text("Retry", fontSize = 11.sp)
                                            }
                                        }
                                        IconButton(
                                            onClick = { onDeleteTransfer(transfer.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(16.dp))
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
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
