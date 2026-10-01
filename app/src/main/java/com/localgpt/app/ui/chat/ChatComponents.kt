package com.localgpt.app.ui.chat

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.data.ChatConstants
import com.localgpt.app.data.ChatMessageEntry
import com.localgpt.app.ui.component.MarkdownText
import com.localgpt.app.ui.component.SearchSourcesCard
import com.localgpt.app.web.WebSourceCitation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Branch navigation pill shown under bubbles that have sibling branches
 * (from editing an older message or regenerating). Matches the variant
 * swipe pagination styling.
 */
@Composable
internal fun BranchNavRow(
    position: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        IconButton(
            onClick = onPrev,
            enabled = position > 1,
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateBefore,
                contentDescription = "Previous branch",
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = "$position/$total",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        IconButton(
            onClick = onNext,
            enabled = position < total,
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "Next branch",
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Empty Chat Welcome Hero matching ChatterUI aesthetic.
 */
@Composable
internal fun EmptyChatHero(
    persona: PersonaPreset,
    modelName: String,
    onPromptSelected: (String) -> Unit,
    onOpenCharacters: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.size(72.dp).clickable { onOpenCharacters() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = getIconVector(persona.iconType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = persona.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = persona.systemPrompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(24.dp))

        // Quick Starter Prompt Chips
        val suggestions = remember(persona.name) {
            when {
                persona.name.contains("Coder", ignoreCase = true) -> listOf(
                    "Write a Kotlin coroutine flow example",
                    "Explain Jetpack Compose state hoisting",
                    "Debug Android memory leak",
                )
                persona.name.contains("Writer", ignoreCase = true) -> listOf(
                    "Write a short futuristic sci-fi story",
                    "Help me polish this professional email",
                    "Create an outline for a technical article",
                )
                else -> listOf(
                    "Explain quantum computing in simple terms",
                    "Give me 5 productivity tips for developers",
                    "How does on-device LLM GPU acceleration work?",
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            suggestions.forEach { prompt ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPromptSelected(prompt) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Message list with ChatterUI-style bubble and frame layouts.
 * Uses reverseLayout = true so incoming streaming tokens (and long thinking blocks)
 * naturally anchor to the bottom without jumping or locking user scroll position.
 */
@Composable
internal fun MessageList(
    viewModel: ChatViewModel,
    activePersona: PersonaPreset,
    isGenerating: Boolean,
    enableThinking: Boolean,
    ttsHelper: TtsHelper,
    onPreviewHtml: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val messageCount = viewModel.messages.size
    val settings by viewModel.settings.collectAsState()

    // Scroll to bottom (index 0 in reverseLayout) when a new message is sent or started.
    // Respects the "Auto-scroll" setting — when off, the list stays where the user left it.
    LaunchedEffect(messageCount, settings.autoScroll) {
        if (messageCount > 0 && settings.autoScroll) {
            listState.animateScrollToItem(0)
        }
    }

    val isScrolledUp by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 250
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(
                count = messageCount,
                key = { i ->
                    val reversedIndex = messageCount - 1 - i
                    val msg = viewModel.messages.getOrNull(reversedIndex)
                    msg?.id ?: "msg_${reversedIndex}_$i"
                },
            ) { i ->
                val reversedIndex = messageCount - 1 - i
                val msg = viewModel.messages.getOrNull(reversedIndex) ?: return@items
                val isLast = (reversedIndex == messageCount - 1)
                MessageBubble(
                    messageIndex = reversedIndex,
                    message = msg,
                    activePersona = activePersona,
                    isLastMessage = isLast,
                    isGenerating = isGenerating,
                    enableThinking = enableThinking,
                    ttsHelper = ttsHelper,
                    branchInfo = viewModel.branchInfoFor(reversedIndex),
                    onEditUserMessage = { newText -> viewModel.editUserMessageAndRegenerate(reversedIndex, newText) },
                    onSelectVariant = { varIndex -> viewModel.selectMessageVariant(reversedIndex, varIndex) },
                    onSwitchBranch = { delta -> viewModel.switchBranch(reversedIndex, delta) },
                    onRegenerate = { viewModel.regenerateLastResponse() },
                    onPreviewHtml = onPreviewHtml,
                )
            }
        }

        // Floating Jump to Bottom action button when user scrolls up
        AnimatedVisibility(
            visible = isScrolledUp,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(0)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
                tonalElevation = 4.dp,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Individual ChatterUI Chat Bubble with Speaker Header, Stats, Variants, and Action Buttons.
 */
@Composable
internal fun MessageBubble(
    messageIndex: Int,
    message: ChatMessageEntry,
    activePersona: PersonaPreset,
    isLastMessage: Boolean,
    isGenerating: Boolean,
    enableThinking: Boolean,
    ttsHelper: TtsHelper,
    branchInfo: Pair<Int, Int>?,
    onEditUserMessage: (String) -> Unit,
    onSelectVariant: (Int) -> Unit,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: () -> Unit,
    onPreviewHtml: (String) -> Unit = {},
) {
    val isUser = message.role == ChatConstants.ROLE_USER
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
        Surface(
            shape =
                RoundedCornerShape(
                    topStart = if (isUser) 18.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 18.dp,
                ),
            color =
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            border =
                if (!isUser) {
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    )
                } else {
                    null
                },
            tonalElevation = if (isUser) 1.dp else 2.dp,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.95f),
        ) {
            Column {
                // Speaker Header Row
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(20.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isUser) Icons.Default.Person else getIconVector(activePersona.iconType),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isUser) "You" else activePersona.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color =
                                if (isUser) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                    }

                    if (isUser && !isGenerating) {
                        IconButton(
                            onClick = { showEditDialog = true },
                            modifier = Modifier.size(22.dp),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit message",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }

                // Attached Image if present
                if (!message.imagePath.isNullOrBlank()) {
                    val imagePath = message.imagePath
                    // B38: decode off the main thread, downsampled to bubble
                    // width (was: main-thread full-size decode in remember).
                    val bitmap by produceState<Bitmap?>(initialValue = null, imagePath) {
                        value =
                            withContext(Dispatchers.IO) {
                                imagePath?.let { path ->
                                    decodeSampledBitmapFromFile(File(path), reqSizePx = 1024)
                                }
                            }
                    }
                    // Recycle bitmap when imagePath changes or composable leaves composition
                    DisposableEffect(bitmap) {
                        onDispose { bitmap?.recycle() }
                    }
                    // Copy to a local val first: smart cast doesn't work on
                    // delegated properties.
                    val currentBitmap = bitmap
                    if (currentBitmap != null) {
                        Image(
                            bitmap = currentBitmap.asImageBitmap(),
                            contentDescription = "Attached Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }

                // Message Text Content
                if (isUser) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                } else {
                    MarkdownText(
                        markdown = message.content.ifBlank { "…" },
                        enableThinking = enableThinking,
                        onPreviewHtml = onPreviewHtml,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )

                    // Web Search Clickable & Collapsible Sources Card
                    val webCitations = remember(message.sources) {
                        message.sources.orEmpty().mapNotNull { raw ->
                            WebSourceCitation.parse(raw)
                        }
                    }
                    if (webCitations.isNotEmpty()) {
                        SearchSourcesCard(
                            sources = webCitations,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        )
                    }

                    // Local Document RAG citation chips (if non-web)
                    val docSources = remember(message.sources, webCitations) {
                        if (webCitations.isNotEmpty()) {
                            message.sources.orEmpty().filter { raw ->
                                WebSourceCitation.parse(raw) == null
                            }
                        } else {
                            message.sources.orEmpty()
                        }
                    }
                    if (docSources.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        ) {
                            docSources.take(3).forEach { label ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(11.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Footer Row: Telemetry Stats + Swipes Pagination + Quick Actions
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!message.stats.isNullOrBlank()) {
                                Text(
                                    text = message.stats,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (message.variants.size > 1) {
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 4.dp),
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (message.selectedVariant > 0) {
                                                onSelectVariant(message.selectedVariant - 1)
                                            }
                                        },
                                        enabled = message.selectedVariant > 0,
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.NavigateBefore,
                                            contentDescription = "Previous variant",
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    Text(
                                        text = "${message.selectedVariant + 1}/${message.variants.size}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                    )
                                    IconButton(
                                        onClick = {
                                            if (message.selectedVariant < message.variants.lastIndex) {
                                                onSelectVariant(message.selectedVariant + 1)
                                            }
                                        },
                                        enabled = message.selectedVariant < message.variants.lastIndex,
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.NavigateNext,
                                            contentDescription = "Next variant",
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Copy Action
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(message.content))
                                    copied = true
                                    scope.launch {
                                        delay(2000)
                                        copied = false
                                    }
                                },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy message",
                                    modifier = Modifier.size(13.dp),
                                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Read Aloud / TTS Action
                            val isSpeaking = (ttsHelper.currentlySpeakingId.value == (message.id ?: "msg_$messageIndex"))
                            IconButton(
                                onClick = {
                                    val msgId = message.id ?: "msg_$messageIndex"
                                    if (isSpeaking) {
                                        ttsHelper.stop()
                                    } else {
                                        ttsHelper.speak(msgId, message.content)
                                    }
                                },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (isSpeaking) "Stop reading" else "Read aloud",
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Share Action
                            IconButton(
                                onClick = {
                                    val sendIntent =
                                        Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, message.content)
                                            type = "text/plain"
                                        }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Message"))
                                },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share message",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Regenerate Action (only on last assistant message when not generating)
                            if (isLastMessage && !isGenerating) {
                                IconButton(
                                    onClick = onRegenerate,
                                    modifier = Modifier.size(26.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Regenerate Response",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        if (branchInfo != null) {
            BranchNavRow(
                position = branchInfo.first,
                total = branchInfo.second,
                onPrev = { onSwitchBranch(-1) },
                onNext = { onSwitchBranch(1) },
            )
        }
    }

    if (showEditDialog) {
        var editInput by remember { mutableStateOf(message.content) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            icon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editInput,
                    onValueChange = { editInput = it },
                    label = { Text("Prompt") },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editInput.isNotBlank()) {
                            onEditUserMessage(editInput)
                            showEditDialog = false
                        }
                    },
                    enabled = editInput.isNotBlank(),
                ) {
                    Text("Save & Regenerate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Maps persona icon type string to Material icon vector.
 */
internal fun getIconVector(iconType: String): ImageVector =
    when (iconType) {
        "assistant" -> Icons.Default.Person
        "coder" -> Icons.Default.Code
        "writer" -> Icons.Default.Description
        "translator" -> Icons.Default.Translate
        "reasoning" -> Icons.Default.Psychology
        "speed" -> Icons.Default.Speed
        "shell" -> Icons.Default.Terminal
        else -> Icons.Default.SmartToy
    }

/**
 * Formats file size in human-readable format.
 */
internal fun formatFileSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}
