package com.localgpt.app.ui.settings
import com.localgpt.app.BuildConfig
import com.localgpt.app.data.ChatConstants

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localgpt.app.ui.chat.ChatViewModel
import com.localgpt.app.ui.component.AccentColorRow
import com.localgpt.app.ui.component.ChipsRow
import com.localgpt.app.ui.component.Material3SettingsGroup
import com.localgpt.app.ui.component.Material3SettingsItem
import com.localgpt.app.ui.component.SettingsIcon
import com.localgpt.app.ui.component.createM3Item

/**
 * Dedicated App Settings Screen adhering to kzkt Material 3 Expressive design.
 */
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()

    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 10.dp, start = 16.dp, end = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Top Header ──
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
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
                            text = "Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Theme, Preferences & Storage",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // ── 1. Appearance & Theme Group ──
            item(key = "appearance_group") {
                Material3SettingsGroup(
                    title = "Appearance",
                    items =
                        listOf(
                            Material3SettingsItem(
                                leadingContent = { SettingsIcon(Icons.Outlined.Brightness4) },
                                title = { Text("Theme Mode") },
                                description = {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 2.dp),
                                    ) {
                                        Text(
                                            when (settings.themeMode) {
                                                "dark" -> "Dark theme enabled"
                                                "light" -> "Light theme enabled"
                                                else -> "Follow system default"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(
                                                ChatConstants.THEME_SYSTEM to "System",
                                                ChatConstants.THEME_LIGHT to "Light",
                                                ChatConstants.THEME_DARK to "Dark",
                                            ).forEach { (mode, label) ->
                                                FilterChip(
                                                    selected = settings.themeMode == mode,
                                                    onClick = { viewModel.setThemeMode(mode) },
                                                    label = { Text(label) },
                                                    shape = RoundedCornerShape(50),
                                                )
                                            }
                                        }
                                    }
                                },
                            ),
                            Material3SettingsItem(
                                leadingContent = { SettingsIcon(Icons.Outlined.DarkMode) },
                                title = { Text("Pure Black OLED Mode") },
                                description = { Text("Uses pitch-black background #000000 on dark theme") },
                                trailingContent = {
                                    Switch(
                                        checked = settings.pureBlack,
                                        onCheckedChange = { viewModel.setPureBlack(it) },
                                    )
                                },
                            ),
                            Material3SettingsItem(
                                leadingContent = { SettingsIcon(Icons.Outlined.Palette) },
                                title = { Text("Accent Color Palette") },
                                description = {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 2.dp),
                                    ) {
                                        Text(
                                            "Generates dynamic Material You tonal color scheme",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        AccentColorRow(
                                            selectedColor = Color(settings.themeColor.toInt()),
                                            onSelectColor = { color -> viewModel.setThemeColor(color.toArgb().toLong() and 0xFFFFFFFFL) },
                                        )
                                    }
                                },
                            ),
                        ),
                )
            }

            // ── 2. Chat UI & Interaction Preferences ──
            item(key = "chat_preferences_group") {
                Material3SettingsGroup(
                    title = "Chat Behavior",
                    items =
                        listOf(
                            createM3Item(
                                icon = Icons.Outlined.Speed,
                                title = "Show Tokens Per Second",
                                description = "Displays real-time generation speed below AI messages",
                                trailingContent = {
                                    Switch(
                                        checked = settings.showTokensPerSec,
                                        onCheckedChange = { viewModel.setShowTokensPerSec(it) },
                                    )
                                },
                            ),
                            createM3Item(
                                icon = Icons.Outlined.SwapVert,
                                title = "Auto-Scroll During Generation",
                                description = "Automatically scrolls message feed as tokens arrive",
                                trailingContent = {
                                    Switch(
                                        checked = settings.autoScroll,
                                        onCheckedChange = { viewModel.setAutoScroll(it) },
                                    )
                                },
                            ),
                            createM3Item(
                                icon = Icons.Outlined.Keyboard,
                                title = "Send on Enter Key",
                                description = "Submits composer prompt immediately when pressing Enter",
                                trailingContent = {
                                    Switch(
                                        checked = settings.sendOnEnter,
                                        onCheckedChange = { viewModel.setSendOnEnter(it) },
                                    )
                                },
                            ),
                            createM3Item(
                                icon = Icons.Outlined.StayCurrentPortrait,
                                title = "Keep Screen Awake",
                                description = "Prevents device display from sleeping during generation",
                                trailingContent = {
                                    Switch(
                                        checked = settings.keepAwake,
                                        onCheckedChange = { viewModel.setKeepAwake(it) },
                                    )
                                },
                            ),
                        ),
                )
            }

            // ── 3. Data & Storage Management ──
            item(key = "data_management_group") {
                Material3SettingsGroup(
                    title = "Data & Storage",
                    items =
                        listOf(
                            createM3Item(
                                icon = Icons.Outlined.Share,
                                title = "Export All Chats (JSON)",
                                description = "Share and backup all conversations as JSON file",
                                trailingContent = {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    val json = viewModel.exportAllConversationsJson()
                                    val sendIntent =
                                        Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, json)
                                            type = "application/json"
                                        }
                                    context.startActivity(Intent.createChooser(sendIntent, "Export All Chats"))
                                },
                            ),
                            createM3Item(
                                icon = Icons.Outlined.DeleteOutline,
                                title = "Clear All Chat History",
                                description = "Permanently remove all chat sessions and saved messages",
                                trailingContent = {
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = { showClearHistoryDialog = true },
                            ),
                        ),
                )
            }

            // ── 4. About LiteChat Card ──
            item(key = "about_card") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "About LiteChat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    "v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }

                        Text(
                            "LiteChat is a 100% on-device AI inference engine built with Google AI Edge LiteRT-LM, OpenCL GPU acceleration, and an embedded OpenAI-compatible local API server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Runtime Engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("LiteRT-LM (OpenCL / NEON)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Target Architecture", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("ARM64-v8a", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear All History?") },
            text = { Text("This will permanently delete all saved conversations on your device. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearHistoryDialog = false
                        Toast.makeText(context, "All chat history cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
