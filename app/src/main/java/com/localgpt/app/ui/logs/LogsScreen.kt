package com.localgpt.app.ui.logs

import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.localgpt.app.ui.theme.success
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import com.localgpt.app.App
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.ui.chat.ChatViewModel
import com.localgpt.app.ui.component.EmptyState
import java.util.Locale

/**
 * Dedicated Real-Time Logs Screen mirroring ChatterUI.
 * Displays LiteRT JNI bridge telemetry, compilation events, and embedded server logs.
 */
@Composable
fun LogsScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val engineLogs by viewModel.engineLogs.collectAsState()
    val listState = rememberLazyListState()

    var searchQuery by remember { mutableStateOf("") }

    val filteredLogs = remember(searchQuery, engineLogs) {
        if (searchQuery.isBlank()) {
            engineLogs
        } else {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            engineLogs.filter { it.lowercase(Locale.ROOT).contains(q) }
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 10.dp, start = 16.dp, end = 16.dp),
        ) {
            // ── 0. Top Header ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            text = "Telemetry Logs",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${filteredLogs.size} Telemetry Records",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            val allText = engineLogs.joinToString("\n")
                            clipboard.setText(AnnotatedString(allText))
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy all logs",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.clearLogs()
                            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear logs",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── 1. Search Bar ──
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter logs…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )

            // ── 1b. Last crash report (written by App's uncaught-exception
            // handler; shown here so crashes are diagnosable without adb) ──
            var crashDismissed by remember { mutableStateOf(false) }
            val crashReport = remember { App.crashReportFile(context)?.readText().orEmpty().takeIf { it.isNotBlank() } }
            if (!crashDismissed && crashReport != null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "Last Crash Report",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            crashReport.take(4000),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(crashReport))
                                Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                            }) {
                                Text("Copy")
                            }
                            TextButton(onClick = {
                                App.clearCrashReport(context)
                                crashDismissed = true
                            }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

            // ── 2. Terminal Log Window ──
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
            ) {
                if (filteredLogs.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Terminal,
                        title = "No log records found",
                        message = "Engine telemetry will appear here once the model runs.",
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredLogs) { line ->
                            val color =
                                when {
                                    line.contains("ERROR", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> MaterialTheme.colorScheme.error
                                    line.contains("WARN", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
                                    line.contains("SUCCESS", ignoreCase = true) || line.contains("Ready", ignoreCase = true) -> MaterialTheme.colorScheme.success
                                    else -> MaterialTheme.colorScheme.onSurface
                                }

                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = color,
                            )
                        }
                    }
                }
            }
        }
    }
}
