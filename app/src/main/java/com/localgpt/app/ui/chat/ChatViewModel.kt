package com.localgpt.app.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.localgpt.app.data.ChatConstants
import java.io.File
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.localgpt.app.core.engine.LiteRtEngineManager
import com.localgpt.app.core.remote.RemoteAiClient
import com.localgpt.app.core.remote.RemoteModelItem
import com.localgpt.app.core.server.ChatServerService
import com.localgpt.app.core.server.OpenAiServer
import com.localgpt.app.core.server.PromptBuilder
import com.localgpt.app.data.ChatMessageEntry
import com.localgpt.app.data.ChatRepository
import com.localgpt.app.data.Conversation
import com.localgpt.app.data.DEFAULT_SYSTEM_PROMPT
import com.localgpt.app.data.Settings
import com.localgpt.app.data.SettingsRepository
import com.localgpt.app.artifacts.ArtifactStore
import com.localgpt.app.localai.LocalAiCatalog
import com.localgpt.app.localai.LocalModelDownloader
import com.localgpt.app.localai.LocalModelManager
import com.localgpt.app.rag.RagManager
import com.localgpt.app.util.CodeArtifacts
import com.localgpt.app.util.FileSaver
import com.localgpt.app.util.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID


data class PersonaPreset(
    val id: String,
    val name: String,
    val iconType: String = "general",
    val description: String = "",
    val greeting: String = "",
    val systemPrompt: String,
    val avatarUri: String = "",
    val temperature: Float? = null,
    val topK: Int? = null,
) {
    companion object {
        val DEFAULT_PRESETS: List<PersonaPreset> get() = PERSONA_PRESETS
    }
}

val PERSONA_PRESETS =
    listOf(
        PersonaPreset(
            id = "general",
            name = "General",
            iconType = "general",
            description = "Local on-device AI assistant ready to help with various tasks and inquiries.",
            greeting = "Hello! I am your local AI assistant. How can I help you today?",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        PersonaPreset(
            id = "coder",
            name = "Coder",
            iconType = "coder",
            description = "Software engineering and system architecture expert.",
            greeting = "Hello! Programming assistant ready.",
            systemPrompt = "You are an expert programming assistant.",
            temperature = 0.1f,
            topK = 20,
        ),
        PersonaPreset(
            id = "translator",
            name = "Translator",
            iconType = "translator",
            description = "Professional linguist.",
            greeting = "Hello! Please enter text to translate.",
            systemPrompt = "You are a professional translator.",
            temperature = 0.2f,
            topK = 30,
        ),
        PersonaPreset(
            id = "summarizer",
            name = "Summarize",
            iconType = "summarizer",
            description = "Condenses text into key takeaways.",
            greeting = "Hello! Provide text to summarize.",
            systemPrompt = "You are an expert summarizer.",
            temperature = 0.3f,
            topK = 40,
        ),
    )

data class BenchmarkResult(
    val prompt: String,
    val modelName: String,
    val backend: String,
    val tokensGenerated: Int,
    val ttftMs: Long,
    val decodeSpeedTokPerSec: Float,
    val totalDurationSec: Float,
    val responseText: String,
    val rating: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class ChatViewModel(
    appContext: Application,
) : AndroidViewModel(appContext) {
    private companion object {
        const val COMPRESS_KEEP_RECENT = 6
        const val COMPRESS_THRESHOLD = 0.75f
        const val CODE_OUTPUT_RULES =
            "\n\n[RESPONSE GUIDELINES]\n" +
            "1. Provide a clear, helpful explanation or summary for the user.\n" +
            "2. When providing code, ALWAYS enclose each complete file inside markdown code blocks (e.g. ```html ... ```).\n" +
            "3. On the FIRST LINE INSIDE the code block, include a filename comment with a clear extension (e.g. <!-- file: index.html -->, /* file: style.css */, // file: app.js, # file: main.py).\n" +
            "4. Always provide the full working code without diffs or placeholders."
    }

    private val app: Application = appContext
    private val settingsRepo = SettingsRepository(app)
    private val chatRepo = ChatRepository(app)
    private val modelManager = LocalModelManager(app)
    private val downloader get() = LocalModelDownloader.getInstance(app)
    private val engine get() = LiteRtEngineManager.getInstance(app)
    private val rag get() = RagManager.getInstance(app)
    private val artifactStore get() = ArtifactStore.getInstance(app)
    private val skillsManager get() = com.localgpt.app.skills.SkillsManager.getInstance(app)
    private val webSearch get() = com.localgpt.app.web.WebSearchManager.getInstance(app)

    val skills: StateFlow<List<com.localgpt.app.skills.Skill>> get() = skillsManager.skills
    val artifacts: StateFlow<List<com.localgpt.app.artifacts.Artifact>> get() = artifactStore.artifacts
    val isCompressing = mutableStateOf(false)
    val compressionTick = mutableStateOf(0)

    val ragDocuments: StateFlow<List<com.localgpt.app.rag.RagDocument>> get() = rag.documents

    val settings = MutableStateFlow(Settings())
    val messages = mutableStateListOf<ChatMessageEntry>()
    val isGenerating = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val installedModels = mutableStateOf<List<com.localgpt.app.localai.InstalledModel>>(emptyList())
    val conversations = mutableStateOf<List<Conversation>>(emptyList())

    val customPersonas = mutableStateListOf<PersonaPreset>()
    val isBenchmarking = mutableStateOf(false)
    val latestBenchmark = mutableStateOf<BenchmarkResult?>(null)

    val serverStatus: StateFlow<OpenAiServer.Status> = OpenAiServer.status
    val downloadStates = downloader.downloadStates
    // Active Persona tracking
    val activePersona = MutableStateFlow<PersonaPreset>(PERSONA_PRESETS.first())

    // Multimodal image attachment state
    val selectedImageUri = MutableStateFlow<Uri?>(null)

    val freeDiskSpaceBytes = mutableStateOf(modelManager.getFreeDiskSpace())

    // Delegated model state from engine
    val isModelLoaded: StateFlow<Boolean> get() = engine.isModelLoaded
    val isLoadingModel: StateFlow<Boolean> get() = engine.isLoadingModel
    val loadedModelPath: StateFlow<String?> get() = engine.loadedModelPath
    val activeBackendState: StateFlow<String?> get() = engine.activeBackendState
    val engineLogs: StateFlow<List<String>> get() = LiteRtEngineManager.engineLogs

    // Remote Provider state
    val remoteModels = mutableStateOf<List<RemoteModelItem>>(emptyList())
    val isFetchingRemoteModels = mutableStateOf(false)

    fun setSelectedImage(uri: Uri?) {
        selectedImageUri.value = uri
    }

    fun clearSelectedImage() {
        selectedImageUri.value = null
    }

    private fun persistImageFromUri(uri: Uri): File? {
        return try {
            val imagesDir = File(app.filesDir, "chat_images").apply { if (!exists()) mkdirs() }
            val destFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            val maxDim = 1024
            var sampleSize = 1
            val maxOriginal = maxOf(options.outWidth, options.outHeight)
            while ((maxOriginal / (sampleSize * 2)) >= maxDim) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = app.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null
            val width = bitmap.width
            val height = bitmap.height
            val finalBitmap = if (width > maxDim || height > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(width, height)
                val newW = (width * ratio).toInt().coerceAtLeast(1)
                val newH = (height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                if (scaled != bitmap) bitmap.recycle()
                scaled
            } else {
                bitmap
            }
            destFile.outputStream().use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            finalBitmap.recycle()
            destFile
        } catch (t: Throwable) {
            KLog.e("ChatVM", "Failed to save and downscale attached image", t)
            null
        }
    }

    var currentConversationId: String? = null
        private set
    private var conversation: Conversation? = null
    private var nodes = mutableListOf<ChatMessageEntry>() // flat tree storage for branching
    private var genJob: Job? = null

    // ── Conversation Tree (Branching) Helpers ────────────────────────

    private fun registerActiveChild(child: ChatMessageEntry) {
        val conv = conversation ?: return
        val id = child.id ?: return
        val map = conv.branchActive ?: mutableMapOf<String, String>().also { conv.branchActive = it }
        map[child.parentId ?: ChatRepository.ROOT_KEY] = id
    }

    private fun rebuildFromTree() {
        val conv = conversation ?: return
        chatRepo.normalize(conv)
        nodes = conv.messages.toMutableList()
        messages.clear()
        messages.addAll(chatRepo.resolveActivePath(conv))
    }

    /** Position (1-based) and total of sibling branches at [index], or null when single. */
    fun branchInfoFor(index: Int): Pair<Int, Int>? {
        val conv = conversation ?: return null
        val target = messages.getOrNull(index) ?: return null
        val targetId = target.id ?: return null
        val sibs = chatRepo.siblingsOf(conv, target)
        if (sibs.size <= 1) return null
        val pos = sibs.indexOfFirst { it.id == targetId }
        if (pos < 0) return null
        return (pos + 1) to sibs.size
    }

    fun switchBranch(index: Int, delta: Int) {
        if (isGenerating.value) return
        val conv = conversation ?: return
        val target = messages.getOrNull(index) ?: return
        val targetId = target.id ?: return
        val sibs = chatRepo.siblingsOf(conv, target)
        val pos = sibs.indexOfFirst { it.id == targetId }
        val nextPos = pos + delta
        if (pos < 0 || nextPos !in sibs.indices) return
        val nextId = sibs[nextPos].id ?: return
        val map = conv.branchActive ?: mutableMapOf<String, String>().also { conv.branchActive = it }
        map[target.parentId ?: ChatRepository.ROOT_KEY] = nextId
        rebuildFromTree()
        persist()
    }

    // ── RAG (Knowledge Documents) ────────────────────────────────────

    private fun ragContext(query: String): Pair<String, List<String>>? =
        if (settings.value.ragEnabled && query.isNotBlank()) {
            try {
                rag.retrieve(query, settings.value.useChatMemory)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    private suspend fun withRagSystem(base: String, query: String): Pair<String, List<String>?> {
        val ctx = ragContext(query)
        var system = if (base.isBlank()) CODE_OUTPUT_RULES.trimStart('\n') else base + CODE_OUTPUT_RULES
        val activeSkills = skillsManager.getActivePromptInstructions()
        if (activeSkills.isNotBlank()) {
            system = "$system$activeSkills"
        }
        ctx?.first?.let { system = "$system\n\n$it" }

        val allSources = (ctx?.second ?: emptyList()).toMutableList()
        val isExplicitSearch = query.startsWith("/search", ignoreCase = true) || query.startsWith("/web", ignoreCase = true)
        val isWebSkillActive = skills.value.any { it.isEnabled && (it.id == "builtin_web_researcher" || it.iconCategory.equals("search", ignoreCase = true) || it.category.equals("Research", ignoreCase = true)) }
        val hasSearchKeyword = query.contains("terbaru", ignoreCase = true) ||
                query.contains("terkini", ignoreCase = true) ||
                query.contains("berita", ignoreCase = true) ||
                query.contains("skor", ignoreCase = true) ||
                query.contains("hasil", ignoreCase = true) ||
                query.contains("jadwal", ignoreCase = true) ||
                query.contains("harga", ignoreCase = true) ||
                query.contains("cuaca", ignoreCase = true) ||
                query.contains("search", ignoreCase = true) ||
                query.contains("cari", ignoreCase = true) ||
                query.contains("latest", ignoreCase = true) ||
                query.contains("news", ignoreCase = true) ||
                query.contains("today", ignoreCase = true) ||
                query.contains("score", ignoreCase = true) ||
                query.contains("weather", ignoreCase = true)

        val shouldSearch = settings.value.enableWebSearch || isWebSkillActive || isExplicitSearch || hasSearchKeyword

        if (shouldSearch && query.isNotBlank()) {
            val cleanQuery = query.removePrefix("/search").removePrefix("/Search").removePrefix("/web").removePrefix("/Web").trim()
            val searchResult = runCatching { webSearch.search(cleanQuery.ifBlank { query }) }.getOrNull()
            if (searchResult != null) {
                system = "$system${searchResult.promptContext}"
                searchResult.items.forEach { item ->
                    allSources.add("Web: ${item.title}")
                }
            }
        }

        val finalSources = if (allSources.isNotEmpty()) allSources else null
        return system to finalSources
    }

    fun importRagDocument(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = rag.importFromUri(uri)
            if (doc == null) {
                withContext(Dispatchers.Main) {
                    errorMessage.value = "Unsupported or empty document. Text files only (txt, md, csv, json, code)."
                }
            }
        }
    }

    fun deleteRagDocument(id: String) {
        viewModelScope.launch(Dispatchers.IO) { rag.delete(id) }
    }

    fun setRagEnabled(v: Boolean) = launchSetting { settingsRepo.setRagEnabled(v) }

    fun setUseChatMemory(v: Boolean) = launchSetting { settingsRepo.setUseChatMemory(v) }

    fun setAutoCompress(v: Boolean) = launchSetting { settingsRepo.setAutoCompress(v) }

    fun setCaptureArtifacts(v: Boolean) = launchSetting { settingsRepo.setCaptureArtifacts(v) }

    fun setEnableVision(v: Boolean) = launchSetting { settingsRepo.setEnableVision(v) }

    fun setEnableWebSearch(v: Boolean) = launchSetting { settingsRepo.setEnableWebSearch(v) }

    // ── Context Compression (rolling summary + chat memory archive) ──


    /**
     * Messages that should be sent verbatim to the model: with a rolling
     * summary, only the not-yet-summarized tail is sent.
     */
    private fun promptWindowMessages(): List<ChatMessageEntry> {
        val summary = conversation?.summary
        if (summary.isNullOrBlank()) return messages.toList()
        return unfoldedMessages()
    }

    /**
     * Returns messages that were not yet folded into the rolling summary.
     * If there is no summary or no summarizedUntilId, all messages are returned.
     */
    private fun unfoldedMessages(): List<ChatMessageEntry> {
        val untilId = conversation?.summarizedUntilId ?: return messages.toList()
        val idx = messages.indexOfFirst { it.id == untilId }
        if (idx < 0) return messages.toList()
        return messages.subList(idx + 1, messages.size)
    }

    /**
     * Resolves a raw search query by injecting recent conversation context.
     * Returns (resolvedQuery, trackedSubject?)
     */
    private fun resolveSmartSearchQuery(
        rawQuery: String,
        history: List<PromptBuilder.ChatMessage>,
    ): Pair<String, String?> {
        // Find the most recent user message for subject tracking
        val recentUserMsg = history.lastOrNull { it.role == ChatConstants.ROLE_USER }?.content.orEmpty()
        // Extract likely subject from recent context (simple heuristic)
        val subject = recentUserMsg.takeIf { it.length in 3..100 }?.trim()
        // If query is very short or generic, prepend context
        if (rawQuery.length < 20 && !recentUserMsg.isNullOrBlank()) {
            return "$rawQuery (context: $recentUserMsg)" to subject
        }
        return rawQuery to subject
    }

    /** Tokens the model effectively sees right now (summary + unfolded tail). */
    fun effectiveContextTokens(): Int {
        val summary = conversation?.summary
        val unfolded = unfoldedMessages()
        return unfolded.sumOf { estimateTokens(it.content) } +
            (summary?.let { estimateTokens(it) } ?: 0)
    }

    private fun injectSummary(
        system: String,
        summary: String?,
        appliedWindow: Boolean,
    ): String =
        if (appliedWindow && !summary.isNullOrBlank()) {
            "$system\n\n[SUMMARY OF EARLIER CONVERSATION]\n$summary"
        } else {
            system
        }

    /**
     * Ensures the working context fits the window. When usage crosses the
     * trigger ratio, evicted turns are archived into Chat Memory (RAG) and a
     * rolling summary is (re)generated. Never mutates the visible history.
     */
    private suspend fun ensureContextFit(
        s: Settings,
        modelPath: String,
    ): String? {
        val conv = conversation ?: return null
        if (!s.autoCompress) return conv.summary
        val summary = conv.summary
        val unfolded = unfoldedMessages()

        // Effective usage = what the model sees now (summary + unfolded tail).
        // Reserve headroom for the upcoming response so generation never dies
        // mid-output at a full window.
        val effTok =
            unfolded.sumOf { estimateTokens(it.content) } +
                (summary?.let { estimateTokens(it) } ?: 0)
        val headroom = s.maxTokens + 256
        val triggerTok = (s.contextWindowTokens * COMPRESS_THRESHOLD).toInt() - headroom
        if (effTok < triggerTok) return summary

        // Fold everything older than the recent tail into the summary.
        val foldable =
            if (unfolded.size > COMPRESS_KEEP_RECENT) unfolded.dropLast(COMPRESS_KEEP_RECENT) else emptyList()
        if (foldable.isEmpty()) return summary

        isCompressing.value = true
        try {
            val clean =
                foldable.mapNotNull { m ->
                    stripThinking(m.content).takeIf { it.isNotBlank() }?.let { "[${m.role}] $it" }
                }
            if (clean.isEmpty()) {
                conv.summarizedUntilId = foldable.last().id
                return summary
            }
            if (s.useChatMemory) {
                runCatching {
                    rag.addChatMemory(conv.id, conv.title.ifBlank { "chat" }, clean.joinToString("\n\n").take(200_000))
                }
            }
            val prev = conv.summary
            val prompt =
                buildString {
                    appendLine("You maintain a rolling memory summary of an ongoing conversation.")
                    if (!prev.isNullOrBlank()) {
                        appendLine("Current summary so far:")
                        appendLine(prev)
                        appendLine()
                    }
                    appendLine("Dialogue to fold into the summary:")
                    appendLine(clean.joinToString("\n"))
                    appendLine()
                    appendLine("Write ONE updated compact summary paragraph (max 180 words) preserving key facts, names, decisions, code/file topics, and open questions. Output ONLY the summary text.")
                }
            val params =
                LiteRtEngineManager.EngineParams(
                    modelPath = modelPath,
                    temperature = 0.2f,
                    topK = 40,
                    topP = 0.90f,
                    maxTokens = 256,
                    contextWindow = maxOf(1024, s.contextWindowTokens),
                    backend = s.backend,
                    systemPrompt = "",
                    enableThinking = false,
                )
            val generated =
                try {
                    stripThinking(engine.generateResponse(prompt, params))
                } catch (t: Throwable) {
                    KLog.w("ChatVM", "Compression failed: ${t.message}")
                    ""
                }
            val merged = generated.ifBlank { prev.orEmpty() }
            conv.summary = merged.ifBlank { null }
            conv.summarizedUntilId = foldable.last().id
            compressionTick.value++
            viewModelScope.launch { runCatching { chatRepo.save(conv) } }
            return conv.summary
        } finally {
            isCompressing.value = false
        }
    }

    // ── Artifact capture & management ────────────────────────────────

    private fun captureArtifacts(
        messageId: String?,
        content: String,
    ) {
        if (!settings.value.captureArtifacts) return
        val convId = currentConversationId ?: return
        val mid = messageId ?: return
        if (content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val blocks = CodeArtifacts.extractFencedBlocks(content)
                if (CodeArtifacts.shouldCapture(blocks)) {
                    val files = CodeArtifacts.deriveProjectFiles(blocks)
                    artifactStore.capture("$convId/$mid", files, chatId = convId)
                }
            }
        }
    }

    fun deleteArtifact(id: String) {
        viewModelScope.launch(Dispatchers.IO) { artifactStore.delete(id) }
    }

    fun deleteArtifactProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) { artifactStore.deleteProject(projectId) }
    }

    fun exportArtifactProject(
        projectId: String,
        folderLabel: String,
        onDone: (Int) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = artifactStore.artifacts.value.filter { it.projectId == projectId }
            val map = LinkedHashMap<String, String>()
            files.forEach { a -> artifactStore.readContent(a)?.let { map[a.fileName] = it } }
            val n = FileSaver.exportFolder(app, folderLabel, map)
            withContext(Dispatchers.Main) { onDone(n) }
        }
    }

    /** Loads all files of a project for the in-app web preview. Entry = first .html file. */
    fun loadWebPreview(
        projectId: String,
        onReady: (String?, Map<String, String>) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = artifactStore.artifacts.value.filter { it.projectId == projectId }
            val map = LinkedHashMap<String, String>()
            var entry: String? = null
            files.forEach { a ->
                artifactStore.readContent(a)?.let {
                    map[a.fileName] = it
                    if (entry == null && a.fileName.endsWith(".html", ignoreCase = true)) entry = a.fileName
                }
            }
            withContext(Dispatchers.Main) { onReady(entry, map) }
        }
    }

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { s ->
                settings.value = s
                loadCustomPersonasFromJson(s.customPersonasJson)
            }
        }
        viewModelScope.launch {
            downloader.downloadStates.collect {
                refreshInstalledModels()
                refreshFreeDiskSpace()
            }
        }
        refreshInstalledModels()
        refreshConversations()
        refreshFreeDiskSpace()
    }

    fun refreshFreeDiskSpace() {
        freeDiskSpaceBytes.value = modelManager.getFreeDiskSpace()
    }

    private fun stripThinking(text: String): String =
        text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("<think>[\\s\\S]*"), "")
            .trim()

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return maxOf(1, Math.round(text.length / 3.5).toInt())
    }

    fun refreshInstalledModels() {
        viewModelScope.launch {
            installedModels.value = modelManager.getInstalledModels()
            refreshFreeDiskSpace()
        }
    }


    fun clearError() {
        errorMessage.value = null
    }

    fun refreshConversations() {
        viewModelScope.launch {
            conversations.value = chatRepo.listConversations()
        }
    }

    fun freeDiskGb(): Float = freeDiskSpaceBytes.value.toFloat() / (1024 * 1024 * 1024)

    fun isModelDownloaded(model: com.localgpt.app.localai.LocalAiModel): Boolean = modelManager.isModelDownloaded(model)

    fun setActiveCustomPath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setCustomModelPath(path)
            settingsRepo.setActiveModelId("")
            settingsRepo.setModelSource(ChatConstants.SOURCE_LOCAL)
            engine.unload()
            refreshInstalledModels()
        }
    }

    fun toggleServer(context: android.content.Context = app) {
        val current = serverStatus.value
        if (current is OpenAiServer.Status.Running || current is OpenAiServer.Status.Starting) {
            ChatServerService.stop(context)
        } else {
            ChatServerService.start(context)
        }
    }

    fun loadConversation(id: String) = selectConversation(id)

    fun selectConversation(id: String) {
        if (currentConversationId == id) return
        stopGeneration()
        viewModelScope.launch {
            val conv = chatRepo.get(id)
            if (conv != null) {
                conversation = conv
                currentConversationId = conv.id
                rebuildFromTree()
            }
        }
    }

    fun newChat(withPersona: PersonaPreset? = null) {
        // Detach the old conversation BEFORE cancelling generation, so the
        // cancelled job's persist() can never resurrect a just-deleted chat.
        conversation = null
        stopGeneration()
        val p = withPersona ?: activePersona.value
        val newConv = Conversation().also { currentConversationId = it.id }
        conversation = newConv
        nodes.clear()
        messages.clear()
        applyPersona(p)
        if (p.greeting.isNotBlank()) {
            messages.add(
                ChatMessageEntry(
                    role = ChatConstants.ROLE_ASSISTANT,
                    content = p.greeting,
                    stats = p.name,
                ),
            )
        }
        // No immediate save: the session is written lazily on the first real
        // message, so Delete All / New Conversation leaves no ghost entries.
        refreshConversations()
    }

    fun clearCurrentChat() {
        stopGeneration()
        messages.clear()
        nodes.clear()
        val conv = conversation
        conv?.messages = emptyList()
        conv?.branchActive = null
        // Remove the on-disk file entirely instead of saving an empty shell,
        // so "Clear All Messages" never leaves a leftover entry in history.
        val id = conv?.id
        if (id != null) {
            viewModelScope.launch { chatRepo.delete(id) }
        }
        refreshConversations()
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatRepo.delete(id)
            rag.clearChatMemory(id)
            if (currentConversationId == id) {
                newChat()
            }
            refreshConversations()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            chatRepo.rename(id, newTitle)
            if (currentConversationId == id) {
                conversation?.title = newTitle
            }
            refreshConversations()
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            chatRepo.deleteAll()
            rag.clearAllMemories()
            newChat()
            refreshConversations()
        }
    }

    fun clearAllHistory() {
        clearAllConversations()
    }

    fun clearLogs() {
        LiteRtEngineManager.clearLogs()
    }

    fun exportAllConversationsJson(): String {
        return com.google.gson.Gson().toJson(conversations.value)
    }

    fun exportChatMarkdown(conv: Conversation): String = chatRepo.exportToMarkdown(conv)

    fun exportCurrentChatMarkdown(): String {
        val conv = conversation ?: return ""
        return chatRepo.exportToMarkdown(conv)
    }

    fun exportCurrentChat(): String = exportCurrentChatMarkdown()

    fun stopGeneration() {
        genJob?.cancel()
        genJob = null
        engine.cancelGeneration()
        isGenerating.value = false
        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT && messages.last().content.isBlank()) {
            messages.removeAt(messages.lastIndex)
        }
        persist()
    }

    fun stopGenerating() = stopGeneration()

    // ── Autonomous Tool Calling Loop (Google AI Edge Architecture) ────


    private suspend fun handleAutonomousToolCall(
        modelPath: String,
        s: Settings,
        targetChatId: String?,
        history: List<PromptBuilder.ChatMessage>,
        effectiveSystem: String,
        currentSources: List<String>?,
        onMetricsUpdate: (LiteRtEngineManager.InferenceMetrics) -> Unit,
    ): List<String>? {
        val lastContent = messages.lastOrNull { it.role == ChatConstants.ROLE_ASSISTANT }?.content.orEmpty()
        val toolCall = com.localgpt.app.skills.ToolCallParser.parse(lastContent) ?: return currentSources
        if (!toolCall.name.startsWith("web_search", ignoreCase = true) || toolCall.query.isBlank()) return currentSources

        val rawQuery = toolCall.query.trim()
        val (contextualQuery, subject) = resolveSmartSearchQuery(rawQuery, history)

        KLog.d("ChatVM", "Autonomous Tool Call: tool='${toolCall.name}', lang='${toolCall.lang}', raw='$rawQuery', resolved='$contextualQuery', subject='$subject'")
        val idx = messages.lastIndex
        val searchMsg = "Searching the web for \"$contextualQuery\"..."
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = searchMsg)
        }

        var searchResult = runCatching { webSearch.search(contextualQuery, lang = toolCall.lang) }.getOrNull()

        // Smart Relevance Check: If a specific subject is being tracked, verify that search results actually mention it
        if (subject != null && searchResult != null && searchResult.items.isNotEmpty()) {
            val subWords = subject.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
            val hasMatch = searchResult.items.any { item ->
                val text = "${item.title} ${item.snippet}".lowercase()
                subWords.any { text.contains(it) }
            }
            if (!hasMatch) {
                val cleanAction = rawQuery.replace(
                    Regex("\\b(dia|ia|beliau|mereka|kapan|dimana|siapa|kenapa|mengapa|he|she|they|it|when|where|who|why|what|how)\\b", RegexOption.IGNORE_CASE),
                    ""
                ).trim()
                val refinedQuery = "$subject $cleanAction".trim()
                KLog.d("ChatVM", "Initial search lacked target subject '$subject'. Executing secondary refined search: '$refinedQuery'")
                val refinedResult = runCatching { webSearch.search(refinedQuery, lang = toolCall.lang) }.getOrNull()
                if (refinedResult != null && refinedResult.items.isNotEmpty()) {
                    searchResult = refinedResult
                }
            }
        } else if (searchResult == null || searchResult.items.isEmpty()) {
            searchResult = runCatching { webSearch.search(rawQuery, lang = toolCall.lang) }.getOrNull()
        }

        val updatedSources = (currentSources ?: emptyList()).toMutableList()

        val searchContext = if (searchResult != null && searchResult.items.isNotEmpty()) {
            searchResult.items.forEachIndexed { i, item ->
                val citation = com.localgpt.app.web.WebSourceCitation(
                    title = item.title,
                    url = item.url,
                    snippet = item.snippet,
                    index = i + 1,
                )
                updatedSources.add(citation.toJson())
            }
            searchResult.promptContext
        } else {
            "[WEB SEARCH RESULTS]\nTopic: \"$contextualQuery\"\nNo additional results found from the web. Answer based on available knowledge."
        }

        // Cleanly inject search result into the last user prompt turn
        val questionLabel = "Question"
        val followUpHistory = history.mapIndexed { i, msg ->
            if (i == history.lastIndex && msg.role.equals(ChatConstants.ROLE_USER, ignoreCase = true)) {
                PromptBuilder.ChatMessage(
                    role = ChatConstants.ROLE_USER,
                    content = "$searchContext\n\n$questionLabel: ${msg.content}"
                )
            } else {
                msg
            }
        }

        val followUpPrompt = PromptBuilder.build(
            messages = followUpHistory,
            systemPrompt = effectiveSystem,
            templateFormat = s.promptTemplateFormat,
            modelPath = modelPath,
            enableThinking = s.enableThinking,
            contextWindow = s.contextWindowTokens,
        )

        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = "", sources = updatedSources)
        }

        var lastEmittedTime = 0L
        val tokenBuffer = StringBuilder()

        engine.streamResponse(
            prompt = followUpPrompt,
            params = LiteRtEngineManager.EngineParams(
                modelPath = modelPath,
                temperature = s.temperature,
                topK = s.topK,
                topP = s.topP,
                maxTokens = s.maxTokens,
                contextWindow = s.contextWindowTokens,
                backend = s.backend,
                systemPrompt = effectiveSystem,
                enableThinking = s.enableThinking,
            ),
            onMetrics = { onMetricsUpdate(it) },
        ).collect { delta ->
            if (currentConversationId != targetChatId) return@collect
            tokenBuffer.append(delta)
            val now = System.currentTimeMillis()
            if (now - lastEmittedTime >= 50L) {
                lastEmittedTime = now
                val curIdx = messages.lastIndex
                if (curIdx >= 0 && messages[curIdx].role == ChatConstants.ROLE_ASSISTANT) {
                    val toAdd = tokenBuffer.toString()
                    tokenBuffer.setLength(0)
                    val cur = messages[curIdx]
                    messages[curIdx] = cur.copy(content = cur.content + toAdd)
                }
            }
        }

        if (tokenBuffer.isNotEmpty()) {
            val curIdx = messages.lastIndex
            if (curIdx >= 0 && messages[curIdx].role == ChatConstants.ROLE_ASSISTANT) {
                val cur = messages[curIdx]
                messages[curIdx] = cur.copy(content = cur.content + tokenBuffer.toString())
            }
        }

        return updatedSources
    }

    // ── Generation Logic (Local LiteRT vs Remote Provider) ───────────

    fun sendMessage(userText: String) {
        val body = userText.trim()
        val currentImageUri = selectedImageUri.value
        if ((body.isBlank() && currentImageUri == null) || isGenerating.value) return
        // Set flag immediately to prevent double-send race condition
        isGenerating.value = true
        errorMessage.value = null
        val s = settings.value

        var savedImageFile: File? = null
        var imageBytes: ByteArray? = null
        if (currentImageUri != null) {
            savedImageFile = persistImageFromUri(currentImageUri)
            imageBytes = savedImageFile?.let { try { it.readBytes() } catch (_: Exception) { null } }
            clearSelectedImage()
        }

        val promptText = if (body.isNotBlank()) body else "Describe this image."

        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            sendRemoteMessage(promptText, s)
            return
        }

        val modelPath = ChatServerService.resolveModelPath(app, s)
        if (modelPath == null) {
            errorMessage.value = "No on-device model found. Please download a model or switch to Remote Provider."
            isGenerating.value = false
            return
        }

        if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }
        val userEntry =
            ChatMessageEntry(
                role = ChatConstants.ROLE_USER,
                content = promptText,
                imagePath = savedImageFile?.absolutePath,
                parentId = messages.lastOrNull()?.id,
            )
        messages.add(userEntry)
        registerActiveChild(userEntry)

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = userEntry.id)
        messages.add(assistant)
        registerActiveChild(assistant)

        var lastMetrics: LiteRtEngineManager.InferenceMetrics? = null
        var ragSources: List<String>? = null

        val targetChatId = currentConversationId
        genJob =
            viewModelScope.launch {
                try {
                    val activeSummary = runCatching { ensureContextFit(s, modelPath) }.getOrNull()
                    val appliedWindow = activeSummary != null
                    val windowed = promptWindowMessages()
                    val (ragSystem, sources1) = withRagSystem(s.systemPrompt, promptText)
                    ragSources = sources1
                    val effectiveSystem = injectSummary(ragSystem, activeSummary, appliedWindow)
                    val history =
                        windowed
                            .dropLast(1)
                            .map { PromptBuilder.ChatMessage(it.role, it.content) }
                    val prompt =
                        PromptBuilder.build(
                            messages = history,
                            systemPrompt = effectiveSystem,
                            templateFormat = s.promptTemplateFormat,
                            modelPath = modelPath,
                            enableThinking = s.enableThinking,
                            contextWindow = s.contextWindowTokens,
                        )
                        var lastEmissionTime = 0L
                        val tokenBuffer = StringBuilder()

                        engine
                            .streamResponse(
                                prompt = prompt,
                                params =
                                    LiteRtEngineManager.EngineParams(
                                        modelPath = modelPath,
                                        temperature = s.temperature,
                                        topK = s.topK,
                                        topP = s.topP,
                                        maxTokens = s.maxTokens,
                                        contextWindow = s.contextWindowTokens,
                                        backend = s.backend,
                                        systemPrompt = effectiveSystem,
                                        enableThinking = s.enableThinking,
                                    ),
                                imagePath = savedImageFile?.absolutePath,
                                imageBytes = imageBytes,
                                onMetrics = { metrics ->
                                    lastMetrics = metrics
                                },
                            ).collect { delta ->
                                if (currentConversationId != targetChatId) return@collect
                                tokenBuffer.append(delta)
                                val now = System.currentTimeMillis()
                                if (now - lastEmissionTime >= 50L) {
                                    lastEmissionTime = now
                                    val idx = messages.lastIndex
                                    if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                                        val toAdd = tokenBuffer.toString()
                                        tokenBuffer.setLength(0)
                                        val current = messages[idx]
                                        messages[idx] = current.copy(content = current.content + toAdd)
                                    }
                                }
                            }

                        if (tokenBuffer.isNotEmpty()) {
                            val idx = messages.lastIndex
                            if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                                val current = messages[idx]
                                messages[idx] = current.copy(content = current.content + tokenBuffer.toString())
                            }
                        }

                    ragSources = handleAutonomousToolCall(
                        modelPath = modelPath,
                        s = s,
                        targetChatId = targetChatId,
                        history = history,
                        effectiveSystem = effectiveSystem,
                        currentSources = ragSources,
                        onMetricsUpdate = { lastMetrics = it },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException || currentConversationId != targetChatId) return@launch
                    KLog.e("ChatVM", "Generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    if (currentConversationId == targetChatId) {
                        isGenerating.value = false
                        if (lastMetrics != null && messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            val variants = if (current.variants.orEmpty().isEmpty()) listOf(current.content) else current.variants
                            messages[idx] = current.copy(stats = lastMetrics.displayBadge, variants = variants, sources = ragSources)
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                    }
                }
            }
    }

    private fun sendRemoteMessage(body: String, s: Settings) {
        if (s.remoteBaseUrl.isBlank() || s.remoteModelId.isBlank()) {
            errorMessage.value = "Please configure Remote Base URL and select a model in Models tab."
            return
        }
        if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }

        val branchQuery = body.ifBlank { messages.lastOrNull { it.role == ChatConstants.ROLE_USER }?.content ?: "" }
        val userEntry =
            ChatMessageEntry(
                role = ChatConstants.ROLE_USER,
                content = body,
                parentId = messages.lastOrNull()?.id,
            )
        messages.add(userEntry)
        registerActiveChild(userEntry)

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = userEntry.id)
        messages.add(assistant)
        registerActiveChild(assistant)
        isGenerating.value = true

        val start = System.currentTimeMillis()
        var tokenCount = 0
        val targetRemoteChatId = currentConversationId
        var ragSources: List<String>? = null

        genJob =
            viewModelScope.launch {
                try {
                    var systemContent = s.systemPrompt
                    if (!s.enableThinking) {
                        val noThinkDirective = "Do NOT think or reason inside <think>...</think> tags. Provide the final response directly."
                        systemContent = if (systemContent.isNotBlank()) "$systemContent\n$noThinkDirective" else noThinkDirective
                    }
                    val (ragSystem, sources) = withRagSystem(systemContent, branchQuery)
                    ragSources = sources
                    // Remote providers manage their own windows; reuse a stored summary when present.
                    val storedSummary = conversation?.summary
                    val appliedWindowRemote = !storedSummary.isNullOrBlank()
                    val finalSystem =
                        injectSummary(ragSystem, storedSummary, appliedWindowRemote)

                    val historyMap = mutableListOf<Map<String, String>>()
                    if (finalSystem.isNotBlank()) {
                        historyMap.add(mapOf("role" to "system", "content" to finalSystem))
                    }
                    val windowed = promptWindowMessages()
                    windowed.dropLast(1).forEach {
                        historyMap.add(mapOf("role" to it.role, "content" to it.content))
                    }

                    RemoteAiClient
                        .streamChat(
                            baseUrl = s.remoteBaseUrl,
                            apiKey = s.remoteApiKey,
                            model = s.remoteModelId,
                            messages = historyMap,
                            temperature = s.temperature,
                            topP = s.topP,
                            maxTokens = s.maxTokens,
                        ).collect { delta ->
                            if (currentConversationId != targetRemoteChatId) return@collect
                            val idx = messages.lastIndex
                            if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                                val current = messages[idx]
                                messages[idx] = current.copy(content = current.content + delta)
                            }
                            tokenCount++
                        }
                } catch (e: Exception) {
                    if (e is CancellationException || currentConversationId != targetRemoteChatId) return@launch
                    KLog.e("ChatVM", "Remote generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Remote generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    if (currentConversationId == targetRemoteChatId) {
                        isGenerating.value = false
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val duration = maxOf(0.1f, (System.currentTimeMillis() - start) / 1000f)
                            val tokSec = tokenCount / duration
                            val badge = "%.1f tok/s · %d tokens · Remote".format(tokSec, tokenCount)
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            val variants = if (current.variants.orEmpty().isEmpty()) listOf(current.content) else current.variants
                            messages[idx] = current.copy(stats = badge, variants = variants, sources = ragSources)
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                    }
                }
            }
    }

    // ── Chat UX: Edit User Message & Swiping Variants ────────────────

    /**
     * Edits a user message by forking a new branch at that point. The old
     * branch (original prompt and everything after it) is preserved in the
     * tree and remains reachable via the branch navigation arrows.
     */
    fun editUserMessageAndRegenerate(index: Int, newText: String) {
        if (index < 0 || index >= messages.size || isGenerating.value) return
        stopGeneration()
        val oldMsg = messages[index]
        if (oldMsg.role != ChatConstants.ROLE_USER) return
        val s = settings.value

        val forked =
            oldMsg.copy(
                content = newText.trim(),
                timestamp = System.currentTimeMillis(),
                stats = null,
                variants = emptyList(),
                selectedVariant = 0,
                id = UUID.randomUUID().toString(),
                sources = null,
            )
        nodes.add(forked)
        registerActiveChild(forked)

        while (messages.size > index) {
            messages.removeAt(messages.lastIndex)
        }
        messages.add(forked)

        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            sendRemoteMessage("", s)
            return
        }
        val modelPath = ChatServerService.resolveModelPath(app, s) ?: return

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = forked.id)
        messages.add(assistant)
        registerActiveChild(assistant)
        isGenerating.value = true

        val imageBytes =
            oldMsg.imagePath?.let { path ->
                try { File(path).takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }
            }

        var lastMetrics: LiteRtEngineManager.InferenceMetrics? = null
        var ragSources: List<String>? = null
        val targetChatId = currentConversationId

        genJob =
            viewModelScope.launch {
                try {
                    val activeSummary = runCatching { ensureContextFit(s, modelPath) }.getOrNull()
                    val appliedWindow = activeSummary != null
                    val windowed = promptWindowMessages()
                    val (ragSystem, sources2) = withRagSystem(s.systemPrompt, newText.trim())
                    ragSources = sources2
                    val effectiveSystem = injectSummary(ragSystem, activeSummary, appliedWindow)
                    val history =
                        windowed.dropLast(1).map { PromptBuilder.ChatMessage(it.role, it.content) }
                    val prompt =
                        PromptBuilder.build(
                            messages = history,
                            systemPrompt = effectiveSystem,
                            templateFormat = s.promptTemplateFormat,
                            modelPath = modelPath,
                            enableThinking = s.enableThinking,
                            contextWindow = s.contextWindowTokens,
                        )
                        var lastEmissionTime = 0L
                        val tokenBuffer = StringBuilder()

                        engine
                            .streamResponse(
                                prompt = prompt,
                                params =
                                    LiteRtEngineManager.EngineParams(
                                        modelPath = modelPath,
                                        temperature = s.temperature,
                                        topK = s.topK,
                                        topP = s.topP,
                                        maxTokens = s.maxTokens,
                                        contextWindow = s.contextWindowTokens,
                                        backend = s.backend,
                                        systemPrompt = effectiveSystem,
                                        enableThinking = s.enableThinking,
                                    ),
                                imagePath = oldMsg.imagePath,
                                imageBytes = imageBytes,
                                onMetrics = { lastMetrics = it },
                            ).collect { delta ->
                                if (currentConversationId != targetChatId) return@collect
                                tokenBuffer.append(delta)
                                val now = System.currentTimeMillis()
                                if (now - lastEmissionTime >= 50L) {
                                    lastEmissionTime = now
                                    val idx = messages.lastIndex
                                    if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                                        val toAdd = tokenBuffer.toString()
                                        tokenBuffer.setLength(0)
                                        messages[idx] = messages[idx].copy(content = messages[idx].content + toAdd)
                                    }
                                }
                            }

                        if (tokenBuffer.isNotEmpty()) {
                            val idx = messages.lastIndex
                            if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                                messages[idx] = messages[idx].copy(content = messages[idx].content + tokenBuffer.toString())
                            }
                        }

                    ragSources = handleAutonomousToolCall(
                        modelPath = modelPath,
                        s = s,
                        targetChatId = targetChatId,
                        history = history,
                        effectiveSystem = effectiveSystem,
                        currentSources = ragSources,
                        onMetricsUpdate = { lastMetrics = it },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException || currentConversationId != targetChatId) return@launch
                    KLog.e("ChatVM", "Edit generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    if (currentConversationId == targetChatId) {
                        isGenerating.value = false
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            messages[idx] =
                                current.copy(
                                    stats = lastMetrics?.displayBadge,
                                    variants = listOf(current.content),
                                    sources = ragSources,
                                )
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                    }
                }
            }
    }

    fun regenerateLastResponse() {
        if (isGenerating.value || messages.isEmpty()) return
        val lastAssistant = messages.lastOrNull { it.role == ChatConstants.ROLE_ASSISTANT }
        val existingVariants = lastAssistant?.variants?.toMutableList() ?: mutableListOf()
        if (lastAssistant != null && lastAssistant.content.isNotBlank() && !existingVariants.contains(lastAssistant.content)) {
            existingVariants.add(lastAssistant.content)
        }

        if (messages.last().role == ChatConstants.ROLE_ASSISTANT) {
            messages.removeAt(messages.lastIndex)
        }
        val lastUser = messages.lastOrNull { it.role == ChatConstants.ROLE_USER } ?: return
        val s = settings.value

        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            sendRemoteMessage("", s)
            return
        }

        val modelPath = ChatServerService.resolveModelPath(app, s)
        if (modelPath == null) {
            errorMessage.value = "No model found to regenerate response."
            return
        }

        val imageBytes =
            lastUser.imagePath?.let { path ->
                try { File(path).takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }
            }

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = lastUser.id)
        messages.add(assistant)
        registerActiveChild(assistant)
        isGenerating.value = true

        var lastMetrics: LiteRtEngineManager.InferenceMetrics? = null
        var ragSources: List<String>? = null
        val targetRegenChatId = currentConversationId

        genJob =
            viewModelScope.launch {
                try {
                    val activeSummary = runCatching { ensureContextFit(s, modelPath) }.getOrNull()
                    val appliedWindow = activeSummary != null
                    val windowed = promptWindowMessages()
                    val (ragSystem, sources3) = withRagSystem(s.systemPrompt, lastUser.content)
                    ragSources = sources3
                    val effectiveSystem = injectSummary(ragSystem, activeSummary, appliedWindow)
                    val history =
                        windowed.dropLast(1).map { PromptBuilder.ChatMessage(it.role, it.content) }
                    val prompt =
                        PromptBuilder.build(
                            messages = history,
                            systemPrompt = effectiveSystem,
                            templateFormat = s.promptTemplateFormat,
                            modelPath = modelPath,
                            enableThinking = s.enableThinking,
                            contextWindow = s.contextWindowTokens,
                        )
                        var lastEmissionTime = 0L
                        val tokenBuffer = StringBuilder()

                        engine
                            .streamResponse(
                                prompt = prompt,
                                params =
                                    LiteRtEngineManager.EngineParams(
                                        modelPath = modelPath,
                                        temperature = s.temperature,
                                        topK = s.topK,
                                        topP = s.topP,
                                        maxTokens = s.maxTokens,
                                        contextWindow = s.contextWindowTokens,
                                        backend = s.backend,
                                        systemPrompt = effectiveSystem,
                                        enableThinking = s.enableThinking,
                                    ),
                                imagePath = lastUser.imagePath,
                                imageBytes = imageBytes,
                                onMetrics = { lastMetrics = it },
                            ).collect { delta ->
                                if (currentConversationId != targetRegenChatId) return@collect
                                tokenBuffer.append(delta)
                                val now = System.currentTimeMillis()
                                if (now - lastEmissionTime >= 50L) {
                                    lastEmissionTime = now
                                    val idx = messages.lastIndex
                                    if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                                        val toAdd = tokenBuffer.toString()
                                        tokenBuffer.setLength(0)
                                        messages[idx] = messages[idx].copy(content = messages[idx].content + toAdd)
                                    }
                                }
                            }

                        if (tokenBuffer.isNotEmpty()) {
                            val idx = messages.lastIndex
                            if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                                messages[idx] = messages[idx].copy(content = messages[idx].content + tokenBuffer.toString())
                            }
                        }

                    ragSources = handleAutonomousToolCall(
                        modelPath = modelPath,
                        s = s,
                        targetChatId = targetRegenChatId,
                        history = history,
                        effectiveSystem = effectiveSystem,
                        currentSources = ragSources,
                        onMetricsUpdate = { lastMetrics = it },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException || currentConversationId != targetRegenChatId) return@launch
                    KLog.e("ChatVM", "Regen generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    if (currentConversationId == targetRegenChatId) {
                        isGenerating.value = false
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            val updatedVariants = existingVariants + current.content
                            messages[idx] =
                                current.copy(
                                    stats = lastMetrics?.displayBadge,
                                    variants = updatedVariants,
                                    selectedVariant = updatedVariants.lastIndex,
                                    sources = ragSources,
                                )
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                    }
                }
            }
    }

    fun selectMessageVariant(messageIndex: Int, variantIndex: Int) {
        if (messageIndex < 0 || messageIndex >= messages.size) return
        val msg = messages[messageIndex]
        val vs = msg.variants.orEmpty()
        if (variantIndex < 0 || variantIndex >= vs.size) return
        messages[messageIndex] = msg.copy(
            content = vs[variantIndex],
            selectedVariant = variantIndex,
        )
        persist()
    }

    private fun persist() {
        val conv = conversation ?: return
        messages.forEach { m ->
            val id = m.id ?: return@forEach
            val i = nodes.indexOfFirst { it.id == id }
            if (i >= 0) nodes[i] = m else nodes.add(m)
        }
        conv.messages = nodes.toList()
        // Lazy persistence: a brand-new session is only written to disk once it
        // contains at least one real user message. This keeps Delete All /
        // New Conversation from leaving empty "New Chat" entries behind.
        val hasRealContent = conv.messages.any { it.role == ChatConstants.ROLE_USER }
        viewModelScope.launch {
            if (!hasRealContent && !chatRepo.exists(conv.id)) return@launch
            chatRepo.save(conv)
            refreshConversations()
        }
    }

    // ── Sampler Presets & Per-Model Parameters ───────────────────────

    fun applySamplerPreset(preset: String) {
        when (preset.lowercase()) {
            "precise" -> {
                setTemperature(0.1f)
                setTopK(20)
                setTopP(0.80f)
                setMaxTokens(1024)
            }
            "creative" -> {
                setTemperature(0.8f)
                setTopK(60)
                setTopP(0.95f)
                setMaxTokens(2048)
            }
            else -> { // balanced
                setTemperature(0.3f)
                setTopK(40)
                setTopP(0.90f)
                setMaxTokens(2048)
            }
        }
    }

    fun setContextWindowTokens(tokens: Int) = launchSetting { settingsRepo.setContextWindowTokens(tokens) }

    fun setPromptTemplateFormat(format: String) = launchSetting { settingsRepo.setPromptTemplateFormat(format) }

    fun setModelSource(source: String) = launchSetting { settingsRepo.setModelSource(source) }

    fun setRemoteBaseUrl(url: String) = launchSetting { settingsRepo.setRemoteBaseUrl(url) }

    fun setRemoteApiKey(key: String) = launchSetting { settingsRepo.setRemoteApiKey(key) }

    fun setRemoteModelId(id: String) = launchSetting { settingsRepo.setRemoteModelId(id) }

    fun fetchRemoteModels(baseUrl: String, apiKey: String = "") {
        isFetchingRemoteModels.value = true
        viewModelScope.launch {
            val res = RemoteAiClient.fetchModels(baseUrl, apiKey)
            res.onSuccess {
                remoteModels.value = it
                if (it.isNotEmpty() && settings.value.remoteModelId.isBlank()) {
                    setRemoteModelId(it.first().id)
                }
            }.onFailure {
                errorMessage.value = "Failed to connect to remote server: ${it.message}"
            }
            isFetchingRemoteModels.value = false
        }
    }

    // ── Model Management ─────────────────────────────────────────────

    fun loadActiveModel() {
        val s = settings.value
        val modelPath = ChatServerService.resolveModelPath(app, s) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            engine.load(
                LiteRtEngineManager.EngineParams(
                    modelPath = modelPath,
                    temperature = s.temperature,
                    topK = s.topK,
                    topP = s.topP,
                    maxTokens = s.maxTokens,
                    contextWindow = s.contextWindowTokens,
                    backend = s.backend,
                    systemPrompt = s.systemPrompt,
                ),
            )
        }
    }

    fun unloadActiveModel() {
        viewModelScope.launch(Dispatchers.IO) { engine.unload() }
    }

    fun startDownload(modelId: String) {
        LocalAiCatalog.getModelById(modelId)?.let { downloader.startDownload(it) }
    }

    fun startDownload(model: com.localgpt.app.localai.LocalAiModel) {
        downloader.startDownload(model)
    }

    fun downloadCustomUrl(downloadUrl: String, fileName: String? = null, expectedSizeBytes: Long = 0L) {
        val fn = fileName?.ifBlank { null } ?: downloadUrl.substringAfterLast('/').substringBefore('?').ifBlank { "custom_model.litertlm" }
        val id = "custom_" + fn.hashCode()
        downloader.startCustomDownload(id, fn, downloadUrl, expectedSizeBytes)
    }

    fun cancelDownload(modelId: String) = downloader.cancelDownload(modelId)

    fun deleteModel(fileName: String) {
        if (loadedModelPath.value?.endsWith(fileName) == true) unloadActiveModel()
        modelManager.deleteFile(fileName)
        refreshInstalledModels()
    }

    fun deleteModel(model: com.localgpt.app.localai.LocalAiModel) {
        if (loadedModelPath.value?.endsWith(model.fileName) == true) unloadActiveModel()
        modelManager.deleteModel(model)
        refreshInstalledModels()
    }

    fun setActivePreset(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setActiveModelId(modelId)
            settingsRepo.setCustomModelPath("")
            settingsRepo.setModelSource(ChatConstants.SOURCE_LOCAL)
            engine.unload()
            refreshInstalledModels()
        }
    }

    fun importCustomModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = modelManager.importCustomModel(uri)
            if (path != null) {
                settingsRepo.setCustomModelPath(path)
                settingsRepo.setActiveModelId("")
                settingsRepo.setModelSource(ChatConstants.SOURCE_LOCAL)
                engine.unload()
                refreshInstalledModels()
            } else {
                withContext(Dispatchers.Main) {
                    errorMessage.value = "Failed to import model file"
                }
            }
        }
    }

    // ── Custom Personas CRUD (Character Card v2) ─────────────────────

    private fun loadCustomPersonasFromJson(json: String) {
        if (json.isBlank()) {
            customPersonas.clear()
            return
        }
        try {
            val listType = object : TypeToken<List<PersonaPreset>>() {}.type
            val list = Gson().fromJson<List<PersonaPreset>>(json, listType) ?: emptyList()
            customPersonas.clear()
            customPersonas.addAll(list)
        } catch (_: Exception) {
        }
    }

    fun addCustomPersona(
        name: String,
        iconType: String = "custom",
        systemPrompt: String,
        description: String = "",
        greeting: String = "",
        avatarUri: String = "",
        temperature: Float? = null,
        topK: Int? = null,
    ) {
        val newP =
            PersonaPreset(
                id = "custom_" + System.currentTimeMillis(),
                name = name.trim().ifBlank { "Custom Persona" },
                iconType = iconType.trim().ifBlank { "custom" },
                description = description.trim(),
                greeting = greeting.trim(),
                systemPrompt = systemPrompt.trim(),
                avatarUri = avatarUri.trim(),
                temperature = temperature,
                topK = topK,
            )
        val updated = customPersonas.toList() + newP
        saveCustomPersonas(updated)
    }

    fun deleteCustomPersona(id: String) {
        val updated = customPersonas.filter { it.id != id }
        saveCustomPersonas(updated)
    }

    private fun saveCustomPersonas(list: List<PersonaPreset>) {
        val json = Gson().toJson(list)
        launchSetting { settingsRepo.setCustomPersonasJson(json) }
    }

    fun exportPersonaJson(persona: PersonaPreset): String {
        return Gson().toJson(persona)
    }

    fun importPersonaJson(jsonString: String): Boolean {
        return try {
            val persona = Gson().fromJson(jsonString, PersonaPreset::class.java)
            if (persona != null && persona.name.isNotBlank()) {
                val newP = persona.copy(id = "imported_" + System.currentTimeMillis())
                saveCustomPersonas(customPersonas.toList() + newP)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun importPersonaFromJson(jsonString: String): Boolean = importPersonaJson(jsonString)

    fun applyPersona(persona: PersonaPreset) {
        activePersona.value = persona
        setSystemPrompt(persona.systemPrompt)
        persona.temperature?.let { setTemperature(it) }
        persona.topK?.let { setTopK(it) }
    }

    // ── LM Studio Benchmark Tool ────────────────────────────────────────

    fun runBenchmark(
        prompt: String = "Explain in 35 words why on-device AI is private and fast.",
        backendOverride: String? = null,
        onComplete: ((BenchmarkResult) -> Unit)? = null,
    ) {
        if (isBenchmarking.value || isGenerating.value) return
        val s = settings.value
        val modelPath = ChatServerService.resolveModelPath(app, s)
        if (modelPath == null) {
            errorMessage.value = "Please download or select a model first to run the benchmark."
            return
        }

        val testPrompt = prompt.ifBlank { "Explain in 35 words why on-device AI is private and fast." }
        val chosenBackend = backendOverride ?: s.backend
        val modelName = java.io.File(modelPath).name.removeSuffix(".litertlm")

        isBenchmarking.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()
                var fullResponse = ""
                var totalTokens = 0

                val params =
                    LiteRtEngineManager.EngineParams(
                        modelPath = modelPath,
                        temperature = 0.1f,
                        topK = 20,
                        topP = 0.90f,
                        maxTokens = 64,
                        backend = chosenBackend,
                        systemPrompt = "You are a concise AI benchmark responder.",
                    )

                engine.streamResponse(
                    prompt = testPrompt,
                    params = params,
                    onMetrics = { metrics ->
                        val duration = maxOf(0.05f, metrics.durationSec)
                        val rating =
                            when {
                                metrics.tokensPerSec >= 20.0f -> "Blazing Fast"
                                metrics.tokensPerSec >= 12.0f -> "Fast & Smooth"
                                metrics.tokensPerSec >= 6.0f -> "Good"
                                else -> "Moderate"
                            }
                        val result =
                            BenchmarkResult(
                                prompt = testPrompt,
                                modelName = modelName,
                                backend = metrics.backend,
                                tokensGenerated = metrics.totalTokens,
                                ttftMs = metrics.ttftMs,
                                decodeSpeedTokPerSec = metrics.tokensPerSec,
                                totalDurationSec = duration,
                                responseText = fullResponse,
                                rating = rating,
                            )
                        latestBenchmark.value = result
                        onComplete?.invoke(result)
                    },
                ).collect { delta ->
                    fullResponse += delta
                    totalTokens++
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    KLog.e("ChatVM", "Benchmark failed", e)
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "Benchmark failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            } finally {
                isBenchmarking.value = false
            }
        }
    }

    // ── Settings mutations ───────────────────────────────────────────

    fun resetParametersToRecommended() {
        launchSetting {
            settingsRepo.setTemperature(0.2f)
            settingsRepo.setTopK(40)
            settingsRepo.setTopP(0.90f)
            settingsRepo.setMaxTokens(512)
            settingsRepo.setContextWindowTokens(2048)
            settingsRepo.setPromptTemplateFormat("auto")
            settingsRepo.setBackend("GPU")
        }
    }

    fun setTemperature(v: Float) = launchSetting { settingsRepo.setTemperature(v) }

    fun setTopK(v: Int) = launchSetting { settingsRepo.setTopK(v) }

    fun setTopP(v: Float) = launchSetting { settingsRepo.setTopP(v) }

    fun setMaxTokens(v: Int) = launchSetting { settingsRepo.setMaxTokens(v) }

    fun setBackend(v: String) = launchSetting { settingsRepo.setBackend(v) }

    fun setThemeMode(v: String) = launchSetting { settingsRepo.setThemeMode(v) }

    fun setThemeColor(v: Long) = launchSetting { settingsRepo.setThemeColor(v) }

    fun setPureBlack(v: Boolean) = launchSetting { settingsRepo.setPureBlack(v) }

    fun setHuggingFaceToken(v: String) = launchSetting { settingsRepo.setHuggingFaceToken(v) }

    fun setServerPort(v: Int) = launchSetting { settingsRepo.setServerPort(v) }

    fun setServerBindAll(v: Boolean) = launchSetting { settingsRepo.setServerBindAll(v) }

    fun setServerAuthToken(v: String) = launchSetting { settingsRepo.setServerAuthToken(v) }

    fun setSystemPrompt(v: String) = launchSetting { settingsRepo.setSystemPrompt(v) }

    fun setEnableThinking(v: Boolean) = launchSetting { settingsRepo.setEnableThinking(v) }

    fun setShowTokensPerSec(v: Boolean) = launchSetting { settingsRepo.setShowTokensPerSec(v) }

    fun setAutoScroll(v: Boolean) = launchSetting { settingsRepo.setAutoScroll(v) }

    fun setSendOnEnter(v: Boolean) = launchSetting { settingsRepo.setSendOnEnter(v) }

    fun setKeepAwake(v: Boolean) = launchSetting { settingsRepo.setKeepAwake(v) }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
