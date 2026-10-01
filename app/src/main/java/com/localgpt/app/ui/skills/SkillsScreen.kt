package com.localgpt.app.ui.skills

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.skills.Skill
import com.localgpt.app.skills.SkillsManager
import com.localgpt.app.ui.chat.ChatViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Dedicated Skills Hub Screen cleanly styled with Material 3 uniform cards,
 * matching SamplerScreen and ModelsScreen aesthetics.
 */
@Composable
fun SkillsScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    val skills by viewModel.skills.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<Skill?>(null) }
    var deleteCandidate by remember { mutableStateOf<Skill?>(null) }
    var pendingImportReview by remember { mutableStateOf<Skill?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val imported = SkillsManager.getInstance(context).importFromUri(uri)
                if (imported != null) {
                    // Imported skills stay disabled until the user reviews the
                    // instructions and explicitly enables them (B9).
                    pendingImportReview = imported
                } else {
                    Toast.makeText(context, "Failed to import skill file", Toast.LENGTH_SHORT).show()
                }
            }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Skills Hub",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "AI Capabilities, Tools & Specialized Instructions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Create Skill",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 1. Secondary Tabs Row ──
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("System Tools (7)", fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Custom Skills (${skills.size})", fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
            }

            // ── 2. Content Views ──
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {

            if (selectedTab == 0) {
                // ── 2. System Capabilities Section ──
                item(key = "sys_info") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "ON-DEVICE SYSTEM CAPABILITIES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Core execution tools built into LiteChat. Toggled features are automatically active during prompt processing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ── Group 1: Knowledge & Retrieval ──
                item(key = "group_knowledge") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "KNOWLEDGE & RETRIEVAL",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(14.dp))

                            SkillItemRow(
                                title = "Live Web Search (Internet)",
                                description = "Real-time on-device search (DuckDuckGo, Wikipedia, URLs) with factual citations. Privacy: fetching full page content sends the URL through the r.jina.ai proxy.",
                                icon = Icons.Default.Search,
                                isEnabled = settings.enableWebSearch,
                                onToggle = { viewModel.setEnableWebSearch(it) },
                            )

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                modifier = Modifier.padding(vertical = 12.dp),
                            )

                            SkillItemRow(
                                title = "Document Knowledge (RAG)",
                                description = "On-device BM25 document indexing and semantic excerpt retrieval ([Doc1], [Doc2]).",
                                icon = Icons.AutoMirrored.Filled.MenuBook,
                                isEnabled = settings.ragEnabled,
                                onToggle = { viewModel.setRagEnabled(it) },
                            )

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                modifier = Modifier.padding(vertical = 12.dp),
                            )

                            SkillItemRow(
                                title = "Chat Memory (Long-term Recall)",
                                description = "Archives evicted conversation turns into persistent searchable memory ([Mem1]).",
                                icon = Icons.Default.Psychology,
                                isEnabled = settings.useChatMemory,
                                onToggle = { viewModel.setUseChatMemory(it) },
                            )
                        }
                    }
                }

                // ── Group 2: Execution & Context ──
                item(key = "group_exec") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "CODE & CONTEXT MANAGEMENT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(14.dp))

                            SkillItemRow(
                                title = "Code Artifacts & HTML Preview",
                                description = "Extracts multi-file projects, derives smart file names, and provides Live Web Previews.",
                                icon = Icons.Default.Code,
                                isEnabled = settings.captureArtifacts,
                                onToggle = { viewModel.setCaptureArtifacts(it) },
                            )

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                modifier = Modifier.padding(vertical = 12.dp),
                            )

                            SkillItemRow(
                                title = "Context Auto-Compression",
                                description = "Summarizes earlier turns before the token limit is reached, preventing generation cutoff.",
                                icon = Icons.Default.Compress,
                                isEnabled = settings.autoCompress,
                                onToggle = { viewModel.setAutoCompress(it) },
                            )
                        }
                    }
                }

                // ── Group 3: Reasoning & Multimodal ──
                item(key = "group_reasoning") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "REASONING & MULTIMODAL",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(14.dp))

                            SkillItemRow(
                                title = "Deep Thinking & Reasoning",
                                description = "Enables structured <think>...</think> reasoning process with collapsible UI cards.",
                                icon = Icons.Default.Lightbulb,
                                isEnabled = settings.enableThinking,
                                onToggle = { viewModel.setEnableThinking(it) },
                            )

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                modifier = Modifier.padding(vertical = 12.dp),
                            )

                            SkillItemRow(
                                title = "Multimodal Vision Input",
                                description = "Enables camera and photo attachments for models with SigLIP vision encoders (Gemma 4 E2B/E4B).",
                                icon = Icons.Default.AddPhotoAlternate,
                                isEnabled = settings.enableVision,
                                onToggle = { viewModel.setEnableVision(it) },
                            )
                        }
                    }
                }
            } else {
                // ── 3. Custom Skills Section ──
                item(key = "custom_actions") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "CUSTOM & IMPORTED SKILLS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Modular prompt directives injected automatically before inferencing. Add specialized knowledge domains anytime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { showCreateDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Create Skill")
                                }

                                OutlinedButton(
                                    onClick = { filePicker.launch("*/*") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Import File")
                                }
                            }
                        }
                    }
                }

                // Preset Prompt Skills
                val presets = skills.filter { it.isBuiltIn }
                if (presets.isNotEmpty()) {
                    item(key = "header_presets") {
                        Text(
                            "BUILT-IN PRESET SKILLS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    items(presets, key = { it.id }) { skill ->
                        PromptSkillCard(
                            skill = skill,
                            onToggle = { isEnabled ->
                                scope.launch { SkillsManager.getInstance(context).toggleSkill(skill.id, isEnabled) }
                            },
                            onEdit = { editingSkill = skill },
                            onDelete = { deleteCandidate = skill },
                            onExport = {
                                scope.launch {
                                    val exported = SkillsManager.getInstance(context).exportSkill(skill)
                                    if (exported != null) {
                                        Toast.makeText(context, "Exported to Downloads/LiteChat/Skills/${exported.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Failed to export skill", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                }

                // User Created Skills
                val customOnly = skills.filterNot { it.isBuiltIn }
                if (customOnly.isNotEmpty()) {
                    item(key = "header_custom") {
                        Text(
                            "USER CREATED & IMPORTED SKILLS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    items(customOnly, key = { it.id }) { skill ->
                        PromptSkillCard(
                            skill = skill,
                            onToggle = { isEnabled ->
                                scope.launch { SkillsManager.getInstance(context).toggleSkill(skill.id, isEnabled) }
                            },
                            onEdit = { editingSkill = skill },
                            onDelete = { deleteCandidate = skill },
                            onExport = {
                                scope.launch {
                                    val exported = SkillsManager.getInstance(context).exportSkill(skill)
                                    if (exported != null) {
                                        Toast.makeText(context, "Exported to Downloads/LiteChat/Skills/${exported.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Failed to export skill", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

    // ── Create / Edit Skill Dialog ──
    if (showCreateDialog || editingSkill != null) {
        val target = editingSkill
        SkillFormDialog(
            initialSkill = target,
            onDismiss = {
                showCreateDialog = false
                editingSkill = null
            },
            onSave = { name, desc, category, icon, instructions ->
                scope.launch {
                    val newSkill = Skill(
                        id = target?.id ?: UUID.randomUUID().toString(),
                        name = name,
                        description = desc,
                        category = category,
                        iconCategory = icon,
                        instructions = instructions,
                        isEnabled = target?.isEnabled ?: true,
                        isBuiltIn = target?.isBuiltIn ?: false,
                    )
                    SkillsManager.getInstance(context).saveSkill(newSkill)
                    Toast.makeText(context, "Skill saved successfully", Toast.LENGTH_SHORT).show()
                }
                showCreateDialog = false
                editingSkill = null
            },
        )
    }

    // ── Delete Confirmation Dialog ──
    deleteCandidate?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete Skill") },
            text = { Text("Are you sure you want to delete '${skill.name}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { SkillsManager.getInstance(context).deleteSkill(skill.id) }
                        deleteCandidate = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Import Review Dialog (B9): preview instructions before enabling ──
    pendingImportReview?.let { skill ->
        SkillImportReviewDialog(
            skill = skill,
            onEnable = {
                scope.launch { SkillsManager.getInstance(context).toggleSkill(skill.id, true) }
                pendingImportReview = null
            },
            onDismiss = { pendingImportReview = null },
        )
    }
}

@Composable
private fun SkillItemRow(
    title: String,
    description: String,
    icon: ImageVector,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.width(10.dp))

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun PromptSkillCard(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    val iconVector = when (skill.iconCategory.lowercase()) {
        "code" -> Icons.Default.Code
        "terminal" -> Icons.Default.Terminal
        "palette" -> Icons.Default.Palette
        "math" -> Icons.Default.Functions
        "write" -> Icons.Default.Edit
        "search" -> Icons.Default.Search
        else -> Icons.Default.AutoAwesome
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (skill.isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = if (skill.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (skill.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 3.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = skill.category,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (skill.isBuiltIn) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            ) {
                                Text(
                                    text = "Built-in Preset",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Switch(
                    checked = skill.isEnabled,
                    onCheckedChange = onToggle,
                )

                if (!skill.isBuiltIn) {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export to Markdown") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit Skill") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                } else {
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Export",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun SkillFormDialog(
    initialSkill: Skill?,
    onDismiss: () -> Unit,
    onSave: (name: String, desc: String, category: String, icon: String, instructions: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialSkill?.name.orEmpty()) }
    var desc by remember { mutableStateOf(initialSkill?.description.orEmpty()) }
    var category by remember { mutableStateOf(initialSkill?.category ?: "Coding") }
    var iconCategory by remember { mutableStateOf(initialSkill?.iconCategory ?: "code") }
    var instructions by remember { mutableStateOf(initialSkill?.instructions.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialSkill != null) "Edit Skill" else "Create Custom Skill") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill Name") },
                    placeholder = { Text("e.g. Kotlin Jetpack Architect") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Short Description") },
                    placeholder = { Text("What this skill specializes in...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category") },
                        placeholder = { Text("Coding / Writing") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )

                    OutlinedTextField(
                        value = iconCategory,
                        onValueChange = { iconCategory = it },
                        label = { Text("Icon Type") },
                        placeholder = { Text("code/math/write/search") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("System Instructions & Directives") },
                    placeholder = { Text("Write detailed instructions on how the AI should format answers, guidelines, and expertise...") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), desc.trim(), category.trim(), iconCategory.trim(), instructions.trim()) },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
            ) {
                Text("Save Skill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
