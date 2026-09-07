package com.localgpt.app.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.core.engine.LiteRtEngineManager
import com.localgpt.app.core.server.OpenAiServer
import com.localgpt.app.data.Conversation
import com.localgpt.app.localai.LocalAiCatalog
import com.localgpt.app.localai.LocalModelDownloader.DownloadState
import com.localgpt.app.ui.component.AccentColorRow
import com.localgpt.app.ui.component.ChipsRow
import com.localgpt.app.ui.component.Material3SettingsGroup
import com.localgpt.app.ui.component.Material3SettingsItem
import com.localgpt.app.ui.component.SettingsIcon
import com.localgpt.app.ui.component.createM3Item
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import com.localgpt.app.ui.models.ActiveDownloadItemCard
import com.localgpt.app.ui.models.CustomUrlDownloadCard
import com.localgpt.app.ui.models.InstalledModelCard
import com.localgpt.app.ui.models.LocalAiParametersTab
import com.localgpt.app.ui.models.LocalSafImportCard
import com.localgpt.app.ui.models.PresetModelCard
import com.localgpt.app.ui.models.StorageSpaceCard
import com.localgpt.app.util.NetworkUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet: Model Manager (Downloads, Catalog, Custom Import, SAF) + Parameter Tuning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var customUrlInput by remember { mutableStateOf("") }
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val specs = remember { LiteRtEngineManager.getSystemSpecs(context) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            // Header: Title & Close Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Offline Models & Tuning",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Navigation Tabs (0 = Models & Downloads, 1 = Parameters & Backend)
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Models Catalog")
                        }
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Parameters")
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                val installed by viewModel.installedModels
                val downloads by viewModel.downloadStates.collectAsState()
                val isLoaded by viewModel.isModelLoaded.collectAsState()
                val isLoading by viewModel.isLoadingModel.collectAsState()
                val activeBackend by viewModel.activeBackendState.collectAsState()

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Storage summary card
                    item {
                        StorageSpaceCard(
                            freeDiskSpaceBytes = viewModel.freeDiskSpaceBytes.value,
                        )
                    }

                    // Low RAM Safety warning banner if device free memory is low
                    if (specs.availRamGb < 1.8f) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = String.format(Locale.US, "Low Available RAM (%.1f GB). SmolLM 135M / 360M is recommended. 2B+ models may cause memory pressure.", specs.availRamGb),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    // Active downloads list
                    val activeList = downloads.entries.mapNotNull { entry ->
                        val state = entry.value
                        if (state is DownloadState.Downloading || state is DownloadState.Error) entry.key to state else null
                    }
                    if (activeList.isNotEmpty()) {
                        item {
                            Text(
                                "Active Downloads",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(activeList, key = { it.first }) { (id, state) ->
                            ActiveDownloadItemCard(
                                downloadId = id,
                                downloadState = state,
                                onCancel = { viewModel.cancelDownload(id) },
                            )
                        }
                    }

                    // Installed models on device
                    if (installed.isNotEmpty()) {
                        item {
                            Text(
                                "Installed Models (${installed.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(installed, key = { it.absolutePath }) { model ->
                            val isActive =
                                if (settings.customModelPath.isNotBlank()) {
                                    settings.customModelPath == model.absolutePath
                                } else {
                                    settings.activeModelId == model.id
                                }
                            InstalledModelCard(
                                installedModel = model,
                                isActive = isActive,
                                isLoaded = isLoaded && isActive,
                                isLoading = isLoading && isActive,
                                activeBackend = activeBackend,
                                onSetActive = {
                                    if (model.isPreset) {
                                        viewModel.setActivePreset(model.id)
                                    } else {
                                        viewModel.setActiveCustomPath(model.absolutePath)
                                    }
                                },
                                onLoad = { viewModel.loadActiveModel() },
                                onUnload = { viewModel.unloadActiveModel() },
                                onDelete = { viewModel.deleteModel(model.fileName) },
                            )
                        }
                    }

                    // Preset Catalog
                    item {
                        Text(
                            "Verified LiteRT Models",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    items(LocalAiCatalog.PRESET_MODELS, key = { it.id }) { preset ->
                        val isDownloaded = viewModel.isModelDownloaded(preset) || installed.any { it.fileName == preset.fileName }
                        val isActive = settings.activeModelId == preset.id && settings.customModelPath.isBlank()
                        val dlState = downloads[preset.id] ?: DownloadState.Idle

                        PresetModelCard(
                            model = preset,
                            isDownloaded = isDownloaded,
                            isActive = isActive,
                            downloadState = dlState,
                            onDownload = { viewModel.startDownload(preset) },
                            onCancel = { viewModel.cancelDownload(preset.id) },
                            onDelete = { viewModel.deleteModel(preset) },
                            onSetActive = { viewModel.setActivePreset(preset.id) },
                        )
                    }

                    // Hugging Face custom URL resolver card
                    item {
                        CustomUrlDownloadCard(
                            urlInput = customUrlInput,
                            onUrlInputChange = { customUrlInput = it },
                            hfToken = settings.huggingFaceToken,
                            onStartDownload = { url, fileName, sizeBytes ->
                                viewModel.downloadCustomUrl(url, fileName, sizeBytes)
                            },
                        )
                    }

                    // Local SAF Import card
                    item {
                        LocalSafImportCard(
                            onImportModel = { uri -> viewModel.importCustomModel(uri) },
                        )
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
                LocalAiParametersTab(
                    settings = settings,
                    onUpdateHuggingFaceToken = { viewModel.setHuggingFaceToken(it) },
                    onUpdateTemperature = { viewModel.setTemperature(it) },
                    onUpdateTopK = { viewModel.setTopK(it) },
                    onUpdateTopP = { viewModel.setTopP(it) },
                    onUpdateMaxTokens = { viewModel.setMaxTokens(it) },
                    onUpdateContextWindow = { viewModel.setContextWindowTokens(it) },
                    onUpdatePromptTemplate = { viewModel.setPromptTemplateFormat(it) },
                    onApplySamplerPreset = { viewModel.applySamplerPreset(it) },
                    onUpdateBackend = { viewModel.setBackend(it) },
                    onResetToRecommended = { viewModel.resetParametersToRecommended() },
                )
            }
        }
    }
}

/**
 * Server control sheet: status, real LAN IP detection, port/bind/auth config, live request history + API Playground.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val isRunning = serverStatus is OpenAiServer.Status.Running
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lanIp = remember { NetworkUtils.getLocalIpAddress(context) ?: "127.0.0.1" }
    val baseUrl = "http://$lanIp:${settings.serverPort}/v1"
    val recentLogs by OpenAiServer.recentLogs.collectAsState()
    val requestCount by OpenAiServer.requestCount.collectAsState()
    var showApiPlayground by remember { mutableStateOf(false) }
    var playgroundInput by remember { mutableStateOf("") }
    var playgroundResponse by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Server Control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status Card
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Surface(shape = CircleShape, color = if (isRunning) Color(0xFF43A047) else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(10.dp)) {}
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRunning) "Server Running" else "Server Stopped", fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Endpoint: $baseUrl", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Requests: $requestCount", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.toggleServer(context) }, modifier = Modifier.weight(1f)) {
                            Text(if (isRunning) "Stop Server" else "Start Server")
                        }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(baseUrl)) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy URL")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Recent Request Logs
            if (recentLogs.isNotEmpty()) {
                Text("Recent Requests", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recentLogs.takeLast(10).reversed()) { log ->
                        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${log.method} ${log.path}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("${log.status} · ${log.durationMs}ms", fontSize = 10.sp, color = if (log.status == 200) Color(0xFF43A047) else Color(0xFFE53935))
                        }
                    }
                }
            }
        }
    }
}

/** List of saved conversations: Search, Edit/Rename, Delete, and Clear All. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val rawConversations by viewModel.conversations
    val currentId = viewModel.currentConversationId
    var searchQuery by remember { mutableStateOf("") }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editTitle by remember { mutableStateOf("") }
    val filtered = remember(rawConversations, searchQuery) {
        if (searchQuery.isBlank()) rawConversations
        else rawConversations.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Chat History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search conversations...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Clear All Button
            if (rawConversations.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showClearAllDialog = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear All")
                    }
                }
            }

            // Conversation List
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.id }) { conv ->
                    val isActive = conv.id == currentId
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conv.title.ifBlank { "New Chat" }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                Text("${conv.messages.size} messages", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editingId = conv.id; editTitle = conv.title }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(14.dp))
                            }
                            IconButton(onClick = { viewModel.deleteConversation(conv.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    // Clear All Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All History") },
            text = { Text("This will permanently delete all conversations. This action cannot be undone.") },
            confirmButton = { Button(onClick = { viewModel.clearAllConversations(); showClearAllDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete All") } },
            dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") } },
        )
    }

    // Edit Dialog
    if (editingId != null) {
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("Rename Conversation") },
            text = { OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Title") }, singleLine = true, shape = RoundedCornerShape(10.dp)) },
            confirmButton = { Button(onClick = { if (editTitle.isNotBlank()) { viewModel.renameConversation(editingId!!, editTitle) }; editingId = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Settings & Appearance Sheet: Dynamic Theme seed color, Pure Black OLED, and general preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Appearance Group
            Material3SettingsGroup(title = "Appearance", items = listOf(
                createM3Item(title = "Theme Color", trailingContent = { AccentColorRow(selectedColor = Color(settings.themeColor), onSelectColor = { viewModel.setThemeColor(it.toArgb().toLong()) }) }),
                createM3Item(title = "Pure Black OLED", trailingContent = { Switch(checked = settings.pureBlack, onCheckedChange = { viewModel.setPureBlack(it) }) }),
                createM3Item(title = "Auto-scroll", trailingContent = { Switch(checked = settings.autoScroll, onCheckedChange = { viewModel.setAutoScroll(it) }) }),
                createM3Item(title = "Show Tokens/sec", trailingContent = { Switch(checked = settings.showTokensPerSec, onCheckedChange = { viewModel.setShowTokensPerSec(it) }) }),
            ))

            Spacer(Modifier.height(8.dp))

            // Advanced Group
            Material3SettingsGroup(title = "Advanced", items = listOf(
                createM3Item(title = "Enable Thinking", trailingContent = { Switch(checked = settings.enableThinking, onCheckedChange = { viewModel.setEnableThinking(it) }) }),
                createM3Item(title = "Enable Vision", trailingContent = { Switch(checked = settings.enableVision, onCheckedChange = { viewModel.setEnableVision(it) }) }),
                createM3Item(title = "Web Search", trailingContent = { Switch(checked = settings.enableWebSearch, onCheckedChange = { viewModel.setEnableWebSearch(it) }) }),
                createM3Item(title = "Capture Artifacts", trailingContent = { Switch(checked = settings.captureArtifacts, onCheckedChange = { viewModel.setCaptureArtifacts(it) }) }),
            ))

            Spacer(Modifier.height(8.dp))

            // Chat Group
            Material3SettingsGroup(title = "Chat", items = listOf(
                createM3Item(title = "RAG Documents", trailingContent = { Switch(checked = settings.ragEnabled, onCheckedChange = { viewModel.setRagEnabled(it) }) }),
                createM3Item(title = "Use Chat Memory", trailingContent = { Switch(checked = settings.useChatMemory, onCheckedChange = { viewModel.setUseChatMemory(it) }) }),
                createM3Item(title = "Auto-compress", trailingContent = { Switch(checked = settings.autoCompress, onCheckedChange = { viewModel.setAutoCompress(it) }) }),
            ))
        }
    }
}

/** Monospace engine telemetry log viewer with Device Specs card and clear/share actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsSheet(
    logs: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val specs = remember { LiteRtEngineManager.getSystemSpecs(context) }

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Telemetry Logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Device Specs Card
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Device Specs", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Device: ${specs.deviceModel}", fontSize = 11.sp)
                    Text("Chipset: ${specs.chipset} (${specs.cores} cores, ${specs.abi})", fontSize = 11.sp)
                    Text("RAM: ${String.format(Locale.US, "%.1f", specs.totalRamGb)} GB total, ${String.format(Locale.US, "%.1f", specs.availRamGb)} GB free", fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onClear() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy All")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Log Viewer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                Text(
                    text = logs.joinToString("\n").ifBlank { "No logs yet." },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/**
 * LM Studio-style On-Device LLM Benchmark Tool Sheet.
 * Measures Time to First Token (TTFT), Decode Speed (tok/s), and overall execution speed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val isBenchmarking by viewModel.isBenchmarking
    val benchmarkResult by viewModel.latestBenchmark

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Benchmark", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Run Button
            Button(
                onClick = { viewModel.runBenchmark() },
                enabled = !isBenchmarking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isBenchmarking) {
                    Text("Running Benchmark...")
                } else {
                    Text("Run Benchmark")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Results
            benchmarkResult?.let { result ->
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Results", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Model: ${result.modelName}", fontSize = 12.sp)
                        Text("Backend: ${result.backend}", fontSize = 12.sp)
                        Text("TTFT: ${result.ttftMs}ms", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Speed: ${String.format(Locale.US, "%.1f", result.decodeSpeedTokPerSec)} tok/s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Tokens: ${result.tokensGenerated}", fontSize = 12.sp)
                        Text("Duration: ${String.format(Locale.US, "%.2f", result.totalDurationSec)}s", fontSize = 12.sp)
                        Text("Rating: ${result.rating}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * SnippetCard for code snippets with copy/share.
 */
@Composable
fun SnippetCard(title: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(12.dp))
                    }
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, code)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(intent, "Share"))
                    }, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(12.dp))
                    }
                }
            }
            Text(code, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** NumberSetting helper for integer input fields. */
@Composable
private fun NumberSetting(label: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t.filter { it.isDigit() }.take(5)
                text.toIntOrNull()?.let(onCommit)
            },
            modifier = Modifier.width(140.dp),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )
    }
}
