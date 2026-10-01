package com.localgpt.app.ui.models

import android.net.Uri
import android.widget.Toast
import com.localgpt.app.data.ChatConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import java.util.Locale
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.localai.InstalledModel
import com.localgpt.app.localai.LocalAiCatalog
import com.localgpt.app.localai.LocalAiModel
import com.localgpt.app.localai.LocalModelDownloader.DownloadState
import com.localgpt.app.ui.chat.ChatViewModel
import com.localgpt.app.ui.component.SettingsTextField

/**
 * Dedicated Models Management Screen cleanly styled with Material 3 uniform cards,
 * matching SamplerScreen aesthetic.
 */
@Composable
fun ModelsScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val installedModels by viewModel.installedModels
    val downloadStates by viewModel.downloadStates.collectAsState()
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val isLoadingModel by viewModel.isLoadingModel.collectAsState()
    val loadedModelPath by viewModel.loadedModelPath.collectAsState()
    val activeBackend by viewModel.activeBackendState.collectAsState()

    val remoteModels by viewModel.remoteModels
    val isFetchingRemoteModels by viewModel.isFetchingRemoteModels

    val freeDiskBytes by viewModel.freeDiskSpaceBytes
    val freeDiskGb = freeDiskBytes / (1024.0 * 1024.0 * 1024.0)

    var customUrlInput by remember { mutableStateOf("") }
    // Mirrors of the remote text fields below, used by the "Fetch Remote Models" button.
    // The fields themselves use SettingsTextField (focus-guarded one-way sync) so typing
    // is never wiped by unrelated settings emissions; onValueChange keeps these in sync.
    var remoteUrlInput by remember { mutableStateOf(settings.remoteBaseUrl) }
    var remoteKeyInput by remember { mutableStateOf(settings.remoteApiKey) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let { viewModel.importCustomModel(it) }
        }

    val isLocalMode = settings.modelSource == ChatConstants.SOURCE_LOCAL

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 10.dp),
        ) {
            // ── 0. Top Header ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Open Menu",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Models Hub",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "On-Device LiteRT & Remote Endpoints",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 1. Secondary Tabs Row ──
            SecondaryTabRow(
                selectedTabIndex = if (isLocalMode) 0 else 1,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    selected = isLocalMode,
                    onClick = { viewModel.setModelSource(ChatConstants.SOURCE_LOCAL) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("On-Device (LiteRT)", fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
                Tab(
                    selected = !isLocalMode,
                    onClick = { viewModel.setModelSource(ChatConstants.SOURCE_REMOTE) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Remote API", fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
            }

            // ── 2. Content View ──
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {

            if (isLocalMode) {
                // ── 2. Storage & Hardware Target Card ──
                item(key = "storage_hardware_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("Device Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Free storage space for local models", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = String.format(Locale.ROOT, "%.1f GB Free", freeDiskGb),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                            Text("Hardware Acceleration Target", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(ChatConstants.BACKEND_GPU to "GPU (OpenCL Snapdragon)", ChatConstants.BACKEND_CPU to "CPU (Arm NEON)").forEach { (be, label) ->
                                    val isSelected = settings.backend.equals(be, ignoreCase = true)
                                    Surface(
                                        onClick = { viewModel.setBackend(be) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 3. Active Downloads (if any) ──
                val activeDownloads = downloadStates.filter {
                    it.value is DownloadState.Downloading || it.value is DownloadState.Error
                }
                if (activeDownloads.isNotEmpty()) {
                    item(key = "active_downloads_card") {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("Active Downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                activeDownloads.forEach { (modelId, state) ->
                                    when (state) {
                                        is DownloadState.Downloading -> {
                                            val percent = (state.progress * 100).toInt()
                                            val speedMb = state.speedBps.toFloat() / (1024 * 1024)
                                            val downloadedMb = state.downloadedBytes.toFloat() / (1024 * 1024)
                                            val totalMb = state.totalBytes.toFloat() / (1024 * 1024)
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(state.displayName.ifBlank { modelId }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                                    Text("$percent%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { state.progress },
                                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        String.format(Locale.ROOT, "%.1f / %.1f MB (%.1f MB/s)", downloadedMb, totalMb, speedMb),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    TextButton(onClick = { viewModel.cancelDownload(modelId) }) {
                                                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                        is DownloadState.Error -> {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Text(
                                                        state.fileName.ifBlank { modelId },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                    Text(
                                                        state.message,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.End,
                                                    ) {
                                                        TextButton(onClick = { viewModel.cancelDownload(modelId) }) {
                                                            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 4. Installed Models Card ──
                item(key = "installed_models_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Installed Models (${installedModels.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Offline models downloaded and stored in app sandbox.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (installedModels.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "No models installed yet. Download a preset below or import a .litertlm file.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    installedModels.forEach { item ->
                                        val isCurrent = (settings.activeModelId.isNotBlank() && item.id == settings.activeModelId) ||
                                            (settings.customModelPath.isNotBlank() && item.absolutePath == settings.customModelPath)
                                        val isLoaded = isCurrent && isModelLoaded

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            border = if (isCurrent) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = item.displayName,
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        if (isCurrent) {
                                                            Spacer(Modifier.width(6.dp))
                                                            Icon(
                                                                Icons.Default.Check,
                                                                contentDescription = "Active",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(16.dp),
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = "${item.sizeDisplay} · ${item.fileName}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontSize = 11.sp,
                                                    )
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    IconButton(
                                                        onClick = { viewModel.deleteModel(item.fileName) },
                                                        modifier = Modifier.size(36.dp),
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                                    }

                                                    if (isCurrent) {
                                                        if (isLoaded) {
                                                            Button(
                                                                onClick = { viewModel.unloadActiveModel() },
                                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                                                shape = RoundedCornerShape(10.dp),
                                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                            ) {
                                                                Text("Unload", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                            }
                                                        } else {
                                                            Button(
                                                                onClick = { viewModel.loadActiveModel() },
                                                                enabled = !isLoadingModel,
                                                                shape = RoundedCornerShape(10.dp),
                                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                            ) {
                                                                if (isLoadingModel) {
                                                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                                                } else {
                                                                    Text("Load into Memory", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        Button(
                                                            onClick = {
                                                                if (item.isPreset) {
                                                                    viewModel.setActivePreset(item.id)
                                                                } else {
                                                                    viewModel.setActiveCustomPath(item.absolutePath)
                                                                }
                                                            },
                                                            shape = RoundedCornerShape(10.dp),
                                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                        ) {
                                                            Text("Use Model", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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

                // ── 5. Verified LiteRT Catalog Card ──
                item(key = "catalog_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Verified LiteRT Catalog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Official mobile-quantized models tested for high token generation speeds.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                LocalAiCatalog.PRESET_MODELS.forEach { model ->
                                    val isInstalled = installedModels.any { it.fileName == model.fileName } || viewModel.isModelDownloaded(model)
                                    val isActive = settings.activeModelId == model.id && settings.customModelPath.isBlank()
                                    val downloadState = downloadStates[model.id]

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                    Text("${model.sizeDisplay} · Format: .litertlm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                                                }
                                                if (isActive) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                    ) {
                                                        Text(
                                                            "ACTIVE",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.height(10.dp))

                                            if (isInstalled) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                                            Spacer(Modifier.width(4.dp))
                                                            Text("Installed", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                        }
                                                    }
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        IconButton(onClick = { viewModel.deleteModel(model) }) {
                                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                                                        }
                                                        if (!isActive) {
                                                            Button(
                                                                onClick = { viewModel.setActivePreset(model.id) },
                                                                shape = RoundedCornerShape(8.dp),
                                                            ) {
                                                                Text("Use Model", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                }
                                            } else if (downloadState is DownloadState.Downloading) {
                                                val percent = (downloadState.progress * 100).toInt()
                                                Column {
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("Downloading...", style = MaterialTheme.typography.labelSmall)
                                                        Text("$percent%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                    }
                                                    Spacer(Modifier.height(4.dp))
                                                    LinearProgressIndicator(
                                                        progress = { downloadState.progress },
                                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                    )
                                                }
                                            } else {
                                                Button(
                                                    onClick = { viewModel.startDownload(model) },
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Download (${model.sizeDisplay})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 6. Custom Model Import & HuggingFace Card ──
                item(key = "import_hf_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CustomUrlDownloadCard(
                            urlInput = customUrlInput,
                            onUrlInputChange = { customUrlInput = it },
                            hfToken = settings.huggingFaceToken,
                            onStartDownload = { url, fileName, sizeBytes, sha256 ->
                                viewModel.downloadCustomUrl(url, fileName, sizeBytes, sha256.ifBlank { null })
                                Toast.makeText(context, "Download started: $fileName", Toast.LENGTH_SHORT).show()
                            },
                        )

                        LocalSafImportCard(
                            onImportModel = { uri -> viewModel.importCustomModel(uri) },
                        )
                    }
                }
            } else {
                // ── Remote Provider Setup Card ──
                item(key = "remote_provider_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Remote Provider Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Connect to an Ollama instance on your Wi-Fi LAN or any OpenAI-compatible API.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            SettingsTextField(
                                initialValue = settings.remoteBaseUrl,
                                onSave = { viewModel.setRemoteBaseUrl(it) },
                                onValueChange = { remoteUrlInput = it },
                                label = { Text("API Base URL") },
                                placeholder = { Text("http://192.168.1.100:11434") },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            SettingsTextField(
                                initialValue = settings.remoteApiKey,
                                onSave = { viewModel.setRemoteApiKey(it) },
                                onValueChange = { remoteKeyInput = it },
                                label = { Text("API Key (Optional for Ollama)") },
                                placeholder = { Text("sk-...") },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // S3: warn when the base URL is plain HTTP — the API key and
                            // prompts are sent unencrypted. Blocking is not an
                            // option: LAN Ollama instances typically serve HTTP.
                            val urlForWarning = remoteUrlInput.ifBlank { settings.remoteBaseUrl }
                            if (urlForWarning.trimStart().startsWith("http://", ignoreCase = true)) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Plain HTTP: your API key and prompts are sent unencrypted. " +
                                            "Use https:// (e.g. via a reverse proxy) if you don't trust this network.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }

                            Button(
                                onClick = { viewModel.fetchRemoteModels(remoteUrlInput, remoteKeyInput) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Fetch Remote Models")
                            }

                            if (remoteModels.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                Text("Available Remote Models (${remoteModels.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    remoteModels.forEach { item ->
                                        val isSelected = settings.remoteModelId == item.id
                                        Surface(
                                            onClick = { viewModel.setRemoteModelId(item.id) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.name.ifBlank { item.id },
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                    )
                                                    Text(
                                                        text = item.id,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontSize = 10.sp,
                                                    )
                                                }
                                                if (isSelected) {
                                                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                                                        Text(
                                                            "Active",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
            }
        }
    }
}
}
