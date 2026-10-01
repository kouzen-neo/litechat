package com.localgpt.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.data.ChatConstants

/**
 * Model quick-switcher dropup menu, shared by the chat top bar.
 * Lists installed local models + known remote models, plus pin / compare /
 * browse shortcuts. Previously lived inside the composer; moved to the top bar
 * so the composer stays slim.
 */
@Composable
internal fun ModelQuickSwitchMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    onNavigateToModels: () -> Unit,
    onCompareClick: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val installedModels by viewModel.installedModels
    val remoteModels by viewModel.remoteModels
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val activeBackend by viewModel.activeBackendState.collectAsState()
    val modelPin by viewModel.modelPinState

DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismiss,
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier.widthIn(min = 240.dp, max = 300.dp),
) {
    // Header
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "Model",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Text(
                text = if (settings.modelSource == ChatConstants.SOURCE_REMOTE) "Remote" else (activeBackend ?: ChatConstants.BACKEND_GPU),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

    if (installedModels.isEmpty() && remoteModels.isEmpty()) {
        DropdownMenuItem(
            text = { Text("No installed models found", style = MaterialTheme.typography.bodySmall) },
            onClick = {
                onDismiss()
                onNavigateToModels()
            },
        )
    } else {
        // Installed Models
        installedModels.forEach { model ->
            val isActive = if (settings.customModelPath.isNotBlank()) {
                settings.customModelPath == model.absolutePath
            } else {
                settings.activeModelId == model.id
            }
            val isCurrentlyLoaded = isModelLoaded && isActive

            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${model.sizeDisplay} · ${if (isCurrentlyLoaded) "In RAM" else "Ready"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrentlyLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                        )
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    onDismiss()
                    viewModel.setModelSource(ChatConstants.SOURCE_LOCAL)
                    if (model.isPreset) {
                        viewModel.setActivePreset(model.id)
                    } else {
                        viewModel.setActiveCustomPath(model.absolutePath)
                    }
                    viewModel.loadActiveModel()
                },
            )
        }

        // Remote Models if any
        if (remoteModels.isNotEmpty()) {
            remoteModels.take(4).forEach { rModel ->
                val isRemoteActive = settings.modelSource == ChatConstants.SOURCE_REMOTE && settings.remoteModelId == rModel.id
                DropdownMenuItem(
                    text = {
                        Text(
                            text = rModel.id,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isRemoteActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isRemoteActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = if (isRemoteActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        onDismiss()
                        viewModel.setModelSource(ChatConstants.SOURCE_REMOTE)
                        viewModel.setRemoteModelId(rModel.id)
                    },
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

    if (modelPin == null) {
        DropdownMenuItem(
            text = { Text("Pin this model to this chat", style = MaterialTheme.typography.labelSmall) },
            leadingIcon = {
                Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            },
            onClick = {
                onDismiss()
                viewModel.pinCurrentModel()
            },
        )
    } else {
        DropdownMenuItem(
            text = { Text("Unpin model (follow global)", style = MaterialTheme.typography.labelSmall) },
            leadingIcon = {
                Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            },
            onClick = {
                onDismiss()
                viewModel.clearModelPin()
            },
        )
    }

    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compare mode (2 models)", style = MaterialTheme.typography.labelSmall)
                if (settings.compareMode) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            "ON",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        },
        leadingIcon = {
            Icon(
                Icons.Default.CompareArrows,
                contentDescription = null,
                tint = if (settings.compareMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        },
        onClick = {
            onDismiss()
            onCompareClick()
        },
    )

    DropdownMenuItem(
        text = { Text("Browse Models Hub…", style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        },
        onClick = {
            onDismiss()
            onNavigateToModels()
        },
    )
}
}
