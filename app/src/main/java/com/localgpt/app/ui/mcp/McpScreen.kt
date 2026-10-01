package com.localgpt.app.ui.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.mcp.McpServerConfig
import com.localgpt.app.mcp.McpTool
import com.localgpt.app.ui.chat.ChatViewModel

/**
 * Manages MCP (Model Context Protocol) servers: add/edit/remove, discover
 * tools, and manually test-call a tool. Autonomous tool use during chat is
 * gated behind [ChatViewModel.setMcpEnabled] plus a per-call approval dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val servers by viewModel.mcpServers.collectAsState()
    val toolsByServer by viewModel.mcpToolsByServer.collectAsState()
    val errors by viewModel.mcpToolErrors.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var expandedServer by remember { mutableStateOf<String?>(null) }
    var testTool by remember { mutableStateOf<Pair<McpServerConfig, McpTool>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP Servers") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshMcpTools() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Discover tools")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add server")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "MCP tools in chat",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "When on, the assistant may propose tool calls — each one needs your explicit approval.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.mcpEnabled,
                            onCheckedChange = { viewModel.setMcpEnabled(it) },
                        )
                    }
                }
            }

            if (servers.isEmpty()) {
                item {
                    Text(
                        "No MCP servers yet. Tap + to add one — e.g. a local MCP gateway at http://192.168.1.10:8000/mcp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            items(servers, key = { it.id }) { server ->
                val tools = toolsByServer[server.id].orEmpty()
                val error = errors[server.id]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    server.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    server.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (server.hasToken) {
                                    Text(
                                        "Token saved (encrypted)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Switch(
                                checked = server.enabled,
                                onCheckedChange = { viewModel.setMcpServerEnabled(server, it) },
                            )
                        }
                        if (error != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { viewModel.refreshMcpServerTools(server) }) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (tools.isEmpty()) "Discover tools" else "${tools.size} tools")
                            }
                            TextButton(onClick = {
                                expandedServer = if (expandedServer == server.id) null else server.id
                            }) {
                                Text(if (expandedServer == server.id) "Hide" else "Show")
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { editingServer = server }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { viewModel.removeMcpServer(server.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (expandedServer == server.id && tools.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            tools.forEach { tool ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { testTool = server to tool }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            tool.signature,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                        )
                                        if (tool.description.isNotBlank()) {
                                            Text(
                                                tool.description.take(140),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Test call",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Tool calls proposed by the assistant always ask for approval first — nothing runs silently.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    if (showAddDialog || editingServer != null) {
        McpServerDialog(
            initial = editingServer,
            onDismiss = {
                showAddDialog = false
                editingServer = null
            },
            onSave = { server, authToken ->
                viewModel.upsertMcpServer(server, authToken)
                showAddDialog = false
                editingServer = null
            },
        )
    }

    testTool?.let { (server, tool) ->
        McpTestCallDialog(
            server = server,
            tool = tool,
            onDismiss = { testTool = null },
            onRun = { argsJson, onResult ->
                viewModel.testMcpCall(server.id, tool.name, argsJson, onResult)
            },
        )
    }
}

@Composable
private fun McpServerDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    /**
     * [authToken]: null = leave stored token untouched (edit mode, field left
     * blank); "" = clear the stored token; otherwise store the new token.
     */
    onSave: (McpServerConfig, String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var authToken by remember { mutableStateOf("") }
    var clearToken by remember { mutableStateOf(false) }
    val canSave = name.isNotBlank() && url.isNotBlank()
    val hadToken = initial?.hasToken == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add MCP Server" else "Edit MCP Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("My tools") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Endpoint URL") },
                    placeholder = { Text("http://192.168.1.10:8000/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = authToken,
                    onValueChange = {
                        authToken = it
                        if (it.isNotBlank()) clearToken = false
                    },
                    label = { Text("Bearer token (optional)") },
                    placeholder = { Text(if (hadToken) "•••••••• (saved)" else "Leave empty for none") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hadToken && !clearToken && authToken.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "A token is saved — leave empty to keep it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { clearToken = true }) { Text("Clear") }
                    }
                }
                if (clearToken) {
                    Text(
                        "The saved token will be removed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Streamable HTTP transport (JSON-RPC over POST). Tokens are stored encrypted on-device, never in plain settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val base = initial ?: McpServerConfig(name = "", url = "")
                    val tokenParam: String? =
                        when {
                            clearToken -> ""
                            authToken.isNotBlank() -> authToken.trim()
                            initial == null -> ""
                            else -> null // keep existing
                        }
                    onSave(base.copy(name = name.trim(), url = url.trim()), tokenParam)
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun McpTestCallDialog(
    server: McpServerConfig,
    tool: McpTool,
    onDismiss: () -> Unit,
    onRun: (String, (String) -> Unit) -> Unit,
) {
    var argsJson by remember { mutableStateOf("{}") }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test: ${tool.name}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    tool.signature,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    "Server: ${server.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = argsJson,
                    onValueChange = { argsJson = it },
                    label = { Text("Arguments (JSON)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp),
                )
                result?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            it.take(3000),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(8.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = {
                    running = true
                    result = "Running…"
                    onRun(argsJson) {
                        result = it
                        running = false
                    }
                },
            ) { Text("Run") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
