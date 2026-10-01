package com.localgpt.app.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Base64
import com.localgpt.app.attachments.AttachmentProcessor
import com.localgpt.app.attachments.PendingAttachment
import com.localgpt.app.attachments.PreparedAttachment
import com.localgpt.app.data.ChatAttachment
import com.localgpt.app.data.ChatConstants
import com.localgpt.app.web.UrlSummarizer
import java.io.File
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.localgpt.app.core.engine.LiteRtEngineManager
import com.localgpt.app.core.remote.RemoteAiClient
import com.localgpt.app.core.remote.RemoteChatMessage
import com.localgpt.app.core.remote.RemoteModelItem
import com.localgpt.app.core.remote.capMessagesToWindow
import com.localgpt.app.core.server.ChatServerService
import com.localgpt.app.core.server.OpenAiServer
import com.localgpt.app.core.server.PromptBuilder
import com.localgpt.app.data.ChatMessageEntry
import com.localgpt.app.data.ChatRepository
import com.localgpt.app.data.Conversation
import com.localgpt.app.data.ConversationHeader
import com.localgpt.app.data.DEFAULT_SYSTEM_PROMPT
import com.localgpt.app.data.Settings
import com.localgpt.app.data.SettingsRepository
import com.localgpt.app.data.effectiveRemoteContextWindow
import com.localgpt.app.data.parseRemoteModelContextWindows
import com.localgpt.app.data.parseRemoteModelPricing
import com.localgpt.app.data.remoteModelPricingToJson
import com.localgpt.app.reminder.ReminderParser
import com.localgpt.app.reminder.cancelAllReminders
import com.localgpt.app.reminder.scheduleReminder
import com.localgpt.app.artifacts.ArtifactStore
import com.localgpt.app.localai.LocalAiCatalog
import com.localgpt.app.localai.LocalModelDownloader
import com.localgpt.app.localai.LocalModelManager
import com.localgpt.app.rag.RagManager
import com.localgpt.app.util.CodeArtifacts
import com.localgpt.app.util.FileSaver
import com.localgpt.app.util.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
        /** Rough token cost of one attached image for remote requests. */
        const val REMOTE_IMAGE_TOKEN_ESTIMATE = 1500
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

    /** Estimated cumulative remote API spend in USD. */
    val remoteSpendUsd = mutableStateOf(0.0)
    val messages = mutableStateListOf<ChatMessageEntry>()
    val isGenerating = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val installedModels = mutableStateOf<List<com.localgpt.app.localai.InstalledModel>>(emptyList())
    val conversations = mutableStateOf<List<ConversationHeader>>(emptyList())

    val customPersonas = mutableStateListOf<PersonaPreset>()
    val isBenchmarking = mutableStateOf(false)
    val latestBenchmark = mutableStateOf<BenchmarkResult?>(null)

    val serverStatus: StateFlow<OpenAiServer.Status> = OpenAiServer.status
    val downloadStates = downloader.downloadStates
    // Active Persona tracking
    val activePersona = MutableStateFlow<PersonaPreset>(PERSONA_PRESETS.first())

    // Multimodal attachment state (images + documents)
    val selectedAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())

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
    val isDetectingContextWindow = mutableStateOf(false)

    fun addAttachments(uris: List<Uri>, forceImage: Boolean = false) {
        if (uris.isEmpty()) return
        val fresh =
            uris.mapNotNull { uri ->
                try {
                    AttachmentProcessor.buildPending(app, uri, forceImage)
                } catch (t: Throwable) {
                    KLog.e("ChatVM", "Failed to stage attachment", t)
                    null
                }
            }
        if (fresh.isNotEmpty()) {
            selectedAttachments.value = (selectedAttachments.value + fresh).take(8)
        }
    }

    fun setSelectedImage(uri: Uri?) {
        // Legacy single-image API: route through the multi-attachment list.
        if (uri == null) {
            selectedAttachments.value = selectedAttachments.value.filterNot { it.isImage }
        } else {
            addAttachments(listOf(uri), forceImage = true)
        }
    }

    fun removeAttachment(uri: Uri) {
        selectedAttachments.value = selectedAttachments.value.filterNot { it.uri == uri }
    }

    fun clearSelectedImage() {
        selectedAttachments.value = emptyList()
    }

    /** Default prompt when the user sends only attachment(s) without text. */
    private fun defaultPromptForAttachments(prepared: List<PreparedAttachment>): String {
        val hasImages = prepared.any { it.attachment.type == "image" }
        val hasDocs = prepared.any { it.attachment.type == "file" && it.extractedText != null }
        return when {
            hasImages && hasDocs -> "Describe the images and summarize the attached documents."
            hasImages -> if (prepared.count { it.attachment.type == "image" } > 1) "Describe these images." else "Describe this image."
            hasDocs -> "Summarize the attached document(s)."
            else -> "What can you tell me about the attached file(s)?"
        }
    }

    // Image-generation state: non-null while an image is being generated.
    val imageGenState = mutableStateOf<String?>(null)

    /** Generates an image via the remote provider's /v1/images/generations endpoint. */
    fun generateImage(prompt: String, size: String = "1024x1024") {
        val s = effectiveSettings()
        if (s.modelSource != ChatConstants.SOURCE_REMOTE || s.remoteBaseUrl.isBlank()) {
            errorMessage.value = "Image generation needs Remote Provider mode (e.g. OpenRouter)."
            return
        }
        val imageModel = s.remoteImageModelId.ifBlank { s.remoteModelId }
        if (imageModel.isBlank()) {
            errorMessage.value = "Pilih image model dulu di tab Models."
            return
        }
        if (isGenerating.value || imageGenState.value != null) return
        viewModelScope.launch {
            imageGenState.value = "Generating image…"
            try {
                val bytes =
                    RemoteAiClient.generateImage(
                        s.remoteBaseUrl,
                        s.remoteApiKey,
                        imageModel,
                        prompt,
                        size,
                    ).getOrThrow()
                val dir = File(app.filesDir, "chat_images").apply { if (!exists()) mkdirs() }
                val file = File(dir, "gen_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }
                val userEntry =
                    ChatMessageEntry(
                        role = ChatConstants.ROLE_USER,
                        content = "Generate image ($size): $prompt",
                        parentId = messages.lastOrNull()?.id,
                    )
                messages.add(userEntry)
                registerActiveChild(userEntry)
                val assistant =
                    ChatMessageEntry(
                        role = ChatConstants.ROLE_ASSISTANT,
                        content = "Generated with `$imageModel`:",
                        imagePath = file.absolutePath,
                        parentId = userEntry.id,
                    )
                messages.add(assistant)
                registerActiveChild(assistant)
                persist()
            } catch (t: Throwable) {
                KLog.e("ChatVM", "Image generation failed", t)
                errorMessage.value = "Image generation failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                imageGenState.value = null
            }
        }
    }

    // URL summarization state: non-null while a page is being fetched.
    val urlFetchState = mutableStateOf<String?>(null)

    /**
     * Pending composer prefill from the home-screen widget ("prefill_prompt"
     * intent extra). ChatScreen consumes this once via [consumePrefill].
     */
    val pendingPrefill = mutableStateOf<String?>(null)

    fun applyPrefill(text: String?) {
        if (!text.isNullOrBlank()) pendingPrefill.value = text
    }

    fun consumePrefill(): String? {
        val v = pendingPrefill.value
        pendingPrefill.value = null
        return v
    }

    /** Fetches a web page and asks the model to summarize it. */
    fun summarizeUrl(rawUrl: String) {
        if (isGenerating.value || urlFetchState.value != null) return
        if (UrlSummarizer.normalizeUrl(rawUrl) == null) {
            errorMessage.value = "URL tidak valid. Contoh: https://example.com/artikel"
            return
        }
        viewModelScope.launch {
            urlFetchState.value = "Mengambil halaman…"
            val page =
                try {
                    UrlSummarizer.fetchReadableText(app, rawUrl)
                } finally {
                    urlFetchState.value = null
                }
            if (page == null) {
                errorMessage.value = "Gagal mengambil halaman. Periksa URL atau koneksi internet."
                return@launch
            }
            sendMessage(
                "Ringkas halaman web berikut.\nJudul: ${page.title}\nURL: ${page.url}\n\n" +
                    "Berikan ringkasan dalam Bahasa Indonesia dengan poin-poin penting:\n\n${page.text}",
            )
        }
    }

    var currentConversationId: String? = null
        private set
    private var conversation: Conversation? = null
    private var nodes = mutableListOf<ChatMessageEntry>() // flat tree storage for branching
    private var genJob: Job? = null

    /**
     * Compare mode: pass 2 runs the same prompt on a challenger model, then
     * its output is merged as a second variant of pass 1's assistant message.
     */
    private data class ComparePass(
        val settings: Settings,
        val labelA: String,
        val labelB: String,
    )

    /** Pass 2 waiting to start after pass 1's finally (cleared on stop/error). */
    private var pendingComparePass: ComparePass? = null

    /** Pass 2 finished; merge its assistant message as a variant (consumed in finally). */
    private var pendingCompareMerge: ComparePass? = null

    // ── Conversation Tree (Branching) Helpers ────────────────────────

    private fun registerActiveChild(child: ChatMessageEntry) {
        val conv = conversation ?: return
        val id = child.id ?: return
        val map = conv.branchActive ?: mutableMapOf<String, String>().also { conv.branchActive = it }
        map[child.parentId ?: ChatRepository.ROOT_KEY] = id
    }

    /**
     * Removes the trailing assistant message, if any. Callers must validate
     * first — removing before validation would wipe the visible response on
     * failure.
     */
    private fun removeTrailingAssistantMessage() {
        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
            messages.removeAt(messages.lastIndex)
        }
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

    private suspend fun ragContext(query: String): Pair<String, List<String>>? =
        if (settings.value.ragEnabled && query.isNotBlank()) {
            try {
                rag.retrieve(query, settings.value.useChatMemory)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    private suspend fun withRagSystem(
        base: String,
        query: String,
        s: Settings? = null,
    ): Pair<String, List<String>?> {
        val ctx = ragContext(query)
        var system = if (base.isBlank()) CODE_OUTPUT_RULES.trimStart('\n') else base + CODE_OUTPUT_RULES
        val activeSkills = skillsManager.getActivePromptInstructions()
        if (activeSkills.isNotBlank()) {
            system = "$system$activeSkills"
        }
        s?.let { system += mcpToolsPromptBlock(it) }
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

    private var cachedContextTokens = 0
    private var cachedContextTokensAt = 0L

    /**
     * Throttled variant of [effectiveContextTokens] for UI display. The full
     * walk is O(n) over every message, so recomputing on each ~50ms token
     * batch visibly degrades long chats. The status-bar counter converges
     * within ~1s, which is plenty for display purposes.
     */
    fun effectiveContextTokensThrottled(): Int {
        val now = System.currentTimeMillis()
        if (now - cachedContextTokensAt >= 1_000L) {
            cachedContextTokens = effectiveContextTokens()
            cachedContextTokensAt = now
        }
        return cachedContextTokens
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
     *
     * @param modelPath on-device model for summary generation; null in remote
     * mode (no local summarizer) — then only the existing summary is reused
     * and the caller hard-caps the request history instead.
     * @param windowTokens effective context window; defaults to the on-device
     * setting, pass the remote window in remote mode.
     */
    private suspend fun ensureContextFit(
        s: Settings,
        modelPath: String?,
        windowTokens: Int = s.contextWindowTokens,
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
        val triggerTok = (windowTokens * COMPRESS_THRESHOLD).toInt() - headroom
        if (effTok < triggerTok) return summary

        // Fold everything older than the recent tail into the summary.
        val foldable =
            if (unfolded.size > COMPRESS_KEEP_RECENT) unfolded.dropLast(COMPRESS_KEEP_RECENT) else emptyList()
        if (foldable.isEmpty()) return summary

        // Remote mode without a local model: cannot run the summarizer, so
        // leave the summary untouched — the caller trims the request instead.
        if (modelPath == null) return summary

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
                    contextWindow = maxOf(1024, windowTokens),
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
                remoteSpendUsd.value = s.remoteSpendMicros / 1_000_000.0
                loadCustomPersonasFromJson(s.customPersonasJson)
            }
        }
        // MCP server configs with tokens re-hydrated from encrypted storage.
        viewModelScope.launch {
            settingsRepo.mcpServersFlow.collect { servers ->
                mcpManager.setServers(servers)
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
            conversations.value = chatRepo.listConversationHeaders()
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

    /** Currently pinned model for the active conversation, as (source, id). Null = follow global. */
    val modelPinState = mutableStateOf<Pair<String, String>?>(null)

    private fun refreshPinState() {
        val conv = conversation
        val src = conv?.pinnedModelSource
        val id = conv?.pinnedModelId
        modelPinState.value =
            if (!src.isNullOrBlank() && !id.isNullOrBlank()) {
                src to id
            } else {
                null
            }
    }

    /**
     * Effective settings for generation in the current conversation: a pinned
     * model overrides the global source/model selection.
     */
    fun effectiveSettings(): Settings {
        val s = settings.value
        val pin = modelPinState.value ?: return s
        val (src, id) = pin
        return when (src) {
            ChatConstants.SOURCE_REMOTE ->
                s.copy(modelSource = src, remoteModelId = id)
            else ->
                if (id.startsWith("custom:")) {
                    s.copy(
                        modelSource = ChatConstants.SOURCE_LOCAL,
                        customModelPath = id.removePrefix("custom:"),
                        activeModelId = "",
                    )
                } else {
                    s.copy(
                        modelSource = ChatConstants.SOURCE_LOCAL,
                        activeModelId = id,
                        customModelPath = "",
                    )
                }
        }
    }

    /** Pins the currently selected global model to this conversation. */
    fun pinCurrentModel() {
        val s = settings.value
        if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }
        val conv = conversation ?: return
        val (src, id) =
            when (s.modelSource) {
                ChatConstants.SOURCE_REMOTE -> s.modelSource to s.remoteModelId
                else ->
                    ChatConstants.SOURCE_LOCAL to
                        if (s.customModelPath.isNotBlank()) "custom:${s.customModelPath}" else s.activeModelId
            }
        if (id.isBlank()) {
            errorMessage.value = "Pilih model dulu sebelum pin."
            return
        }
        conv.pinnedModelSource = src
        conv.pinnedModelId = id
        persist()
        refreshPinState()
    }

    /** Removes the model pin; the conversation follows the global model again. */
    fun clearModelPin() {
        conversation?.pinnedModelSource = null
        conversation?.pinnedModelId = null
        persist()
        refreshPinState()
    }

    /** Moves a conversation into a folder ("" = no folder). */
    fun setConversationFolder(id: String, folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val conv = chatRepo.get(id) ?: return@launch
            conv.folder = folder.trim().take(40)
            chatRepo.save(conv)
            refreshConversations()
        }
    }

    /** Distinct non-empty folder names across conversations. */
    fun conversationFolders(): List<String> =
        conversations.value.mapNotNull { it.folder.takeIf { f -> f.isNotBlank() } }.distinct().sorted()

    /** Replaces a conversation's tags (unlike folder, a chat can carry several). */
    fun setConversationTags(id: String, tags: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val conv = chatRepo.get(id) ?: return@launch
            conv.tags = com.localgpt.app.data.sanitizeTags(tags)
            chatRepo.save(conv)
            refreshConversations()
        }
    }

    /** Distinct tags across conversations. */
    fun conversationTags(): List<String> =
        conversations.value.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted()

    fun selectConversation(id: String) {
        if (currentConversationId == id) return
        stopGeneration()
        viewModelScope.launch {
            val conv = chatRepo.get(id)
            if (conv != null) {
                conversation = conv
                currentConversationId = conv.id
                rebuildFromTree()
                refreshPinState()
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
        refreshPinState()
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

    /** Full-text body search across conversations; bodies are loaded on demand (P6). */
    suspend fun searchConversationBodies(query: String): Set<String> =
        chatRepo.listConversations()
            .filter { conv -> conv.messages.any { it.content.contains(query, ignoreCase = true) } }
            .map { it.id }
            .toSet()

    /** Full bodies are loaded on demand so the UI list stays on cheap headers (P6). */
    suspend fun exportAllConversationsJson(): String {
        return com.google.gson.Gson().toJson(chatRepo.listConversations())
    }

    fun exportChatMarkdown(conv: Conversation): String = chatRepo.exportToMarkdown(conv)

    /** Loads the full conversation on demand for per-item export from header lists (P6). */
    suspend fun exportChatMarkdownById(id: String): String =
        chatRepo.get(id)?.let { chatRepo.exportToMarkdown(it) } ?: ""

    /** Loads full bodies on demand for the "export all as markdown" share action (P6). */
    suspend fun exportAllChatsMarkdown(): String =
        chatRepo.listConversations().joinToString("\n\n---\n\n") { chatRepo.exportToMarkdown(it) }

    fun exportCurrentChatMarkdown(): String {
        val conv = conversation ?: return ""
        return chatRepo.exportToMarkdown(conv)
    }

    fun exportCurrentChat(): String = exportCurrentChatMarkdown()

    fun stopGeneration() {
        genJob?.cancel()
        genJob = null
        // A stopped compare must never chain pass 2 or merge into a later run.
        pendingComparePass = null
        pendingCompareMerge = null
        // Never leave an approval dialog hanging after stop/switch.
        denyMcpApproval()
        engine.cancelGeneration()
        isGenerating.value = false
        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT && messages.last().content.isBlank()) {
            messages.removeAt(messages.lastIndex)
        }
        persist()
    }

    fun stopGenerating() = stopGeneration()

    // ── Autonomous Tool Calling Loop (Google AI Edge Architecture) ────

    /**
     * Collects a stream of text deltas into the trailing assistant message.
     * UI updates are batched at ~50ms intervals to avoid excessive recomposition.
     * [onDelta] is invoked for every raw delta (e.g. for token counting).
     * Collection stops appending if the user switched conversation mid-stream.
     */
    private suspend fun collectStreamIntoMessage(
        deltas: Flow<String>,
        targetChatId: String?,
        onDelta: ((String) -> Unit)? = null,
    ) {
        var lastEmissionTime = 0L
        val tokenBuffer = StringBuilder()
        deltas.collect { delta ->
            if (currentConversationId != targetChatId) return@collect
            tokenBuffer.append(delta)
            onDelta?.invoke(delta)
            val now = System.currentTimeMillis()
            if (now - lastEmissionTime >= 50L) {
                lastEmissionTime = now
                flushTokenBuffer(tokenBuffer)
            }
        }
        flushTokenBuffer(tokenBuffer)
    }

    /** Appends the buffered text to the trailing assistant message, if any. */
    private fun flushTokenBuffer(tokenBuffer: StringBuilder) {
        if (tokenBuffer.isEmpty()) return
        val idx = messages.lastIndex
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            val current = messages[idx]
            messages[idx] = current.copy(content = current.content + tokenBuffer.toString())
            tokenBuffer.setLength(0)
        }
    }

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
        val toolCall = com.localgpt.app.skills.ToolCallParser.parse(lastContent)
        if (toolCall == null) {
            // Not a web_search call — maybe an MCP tool call.
            val mcpCall = com.localgpt.app.skills.ToolCallParser.parseGenericCall(lastContent)
            if (mcpCall != null && s.mcpEnabled && s.modelSource == ChatConstants.SOURCE_LOCAL) {
                return handleMcpToolCall(
                    call = mcpCall,
                    modelPath = modelPath,
                    s = s,
                    targetChatId = targetChatId,
                    history = history,
                    effectiveSystem = effectiveSystem,
                    currentSources = currentSources,
                    onMetricsUpdate = onMetricsUpdate,
                )
            }
            return currentSources
        }
        if (!toolCall.name.startsWith("web_search", ignoreCase = true) || toolCall.query.isBlank()) return currentSources

        val rawQuery = toolCall.query.trim()
        val (contextualQuery, subject) = resolveSmartSearchQuery(rawQuery, history)

        KLog.d("ChatVM", "Autonomous Tool Call: tool='${toolCall.name}', lang='${toolCall.lang}', raw='$rawQuery', resolved='$contextualQuery', subject='$subject'")
        val idx = messages.lastIndex
        val searchMsg = "Searching the web for \"$contextualQuery\"..."
        // B42: don't touch another conversation's messages if the user
        // switched chats while the tool call was being prepared.
        if (currentConversationId == targetChatId && idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
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

        // B42: the web search above is a network call — the user may have
        // switched conversations meanwhile. Never mutate the new
        // conversation's messages with this chat's tool-call state.
        if (currentConversationId != targetChatId) return currentSources
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = "", sources = updatedSources)
        }

        collectStreamIntoMessage(
            deltas =
                engine.streamResponse(
                    prompt = followUpPrompt,
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
                    onMetrics = { onMetricsUpdate(it) },
                ),
            targetChatId = targetChatId,
        )

        return updatedSources
    }

    /**
     * Executes one autonomous MCP tool call round: parse → explicit user
     * approval → execute → follow-up stream with the result injected.
     * Exactly one round per generation (no re-parsing of the follow-up), so a
     * runaway model cannot trigger unbounded tool executions.
     */
    private suspend fun handleMcpToolCall(
        call: com.localgpt.app.skills.ToolCallParser.GenericToolCall,
        modelPath: String,
        s: Settings,
        targetChatId: String?,
        history: List<PromptBuilder.ChatMessage>,
        effectiveSystem: String,
        currentSources: List<String>?,
        onMetricsUpdate: (LiteRtEngineManager.InferenceMetrics) -> Unit,
    ): List<String>? {
        val tool = mcpManager.findTool(call.name)
        val idx = messages.lastIndex
        val updatedSources = (currentSources ?: emptyList()).toMutableList()

        val toolContext: String
        if (tool == null) {
            KLog.w("ChatVM", "MCP tool '${call.name}' not found on any enabled server")
            toolContext =
                "[MCP TOOL UNAVAILABLE]\n" +
                    "Tool '${call.name}' is not available on any enabled MCP server. " +
                    "Only call tools from the [MCP TOOLS] list. Apologize briefly and ask the user how to proceed."
        } else {
            // Show status while waiting for the explicit approval gate.
            if (currentConversationId == targetChatId && idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                messages[idx] = messages[idx].copy(content = "🔧 Requesting approval to run `${tool.name}`…")
            }
            val req =
                com.localgpt.app.mcp.McpCallRequest(
                    serverId = tool.serverId,
                    serverName = tool.serverName,
                    toolName = tool.name,
                    arguments = call.arguments,
                )
            val approved = requestMcpApproval(req)
            // The user may have switched conversations while deciding.
            if (currentConversationId != targetChatId) return currentSources
            toolContext =
                if (!approved) {
                    KLog.d("ChatVM", "MCP tool '${tool.name}' denied by user")
                    "[MCP TOOL CALL DENIED]\n" +
                        "The user denied the `${tool.name}` call. Do not retry it. " +
                        "Explain briefly and ask how they'd like to continue."
                } else {
                    if (currentConversationId == targetChatId && idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                        messages[idx] = messages[idx].copy(content = "🔧 Running `${tool.name}`…")
                    }
                    val result =
                        runCatching { mcpManager.callTool(tool.serverId, tool.name, call.arguments) }
                    result.fold(
                        onSuccess = { r ->
                            updatedSources.add("MCP: ${tool.name} (${tool.serverName})")
                            buildString {
                                append("[MCP TOOL RESULT]\n")
                                append("Tool: ${tool.name}  [server: ${tool.serverName}]\n")
                                append("Arguments: ${req.argumentsJson.take(2000)}\n")
                                if (r.isError) append("The tool reported an error:\n")
                                append("Result:\n${r.text.take(8000)}")
                            }
                        },
                        onFailure = { e ->
                            KLog.w("ChatVM", "MCP tool '${tool.name}' failed: ${e.message}")
                            "[MCP TOOL ERROR]\n" +
                                "Calling `${tool.name}` failed: ${e.message ?: e.javaClass.simpleName}. " +
                                "Explain briefly and suggest alternatives."
                        },
                    )
                }
        }

        // Inject the tool outcome into the last user turn and stream a follow-up.
        val followUpHistory = history.mapIndexed { i, msg ->
            if (i == history.lastIndex && msg.role.equals(ChatConstants.ROLE_USER, ignoreCase = true)) {
                PromptBuilder.ChatMessage(
                    role = ChatConstants.ROLE_USER,
                    content = "$toolContext\n\nQuestion: ${msg.content}",
                )
            } else {
                msg
            }
        }
        val followUpPrompt =
            PromptBuilder.build(
                messages = followUpHistory,
                systemPrompt = effectiveSystem,
                templateFormat = s.promptTemplateFormat,
                modelPath = modelPath,
                enableThinking = s.enableThinking,
                contextWindow = s.contextWindowTokens,
            )

        // B42: never mutate a new conversation with this chat's tool-call state.
        if (currentConversationId != targetChatId) return currentSources
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = "", sources = updatedSources)
        }

        collectStreamIntoMessage(
            deltas =
                engine.streamResponse(
                    prompt = followUpPrompt,
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
                    onMetrics = { onMetricsUpdate(it) },
                ),
            targetChatId = targetChatId,
        )

        return updatedSources
    }

    // ── Generation Logic (Local LiteRT vs Remote Provider) ───────────

    private val reminderCancelId =
        Regex(
            """^(?:tolong\s+)?(?:batalkan|hapus)\s+(?:semua\s+)?(?:pengingat|reminder)s?\b.*$""",
            RegexOption.IGNORE_CASE,
        )
    private val reminderCancelEn =
        Regex("""^cancel\s+(?:all\s+)?reminders?\b.*$""", RegexOption.IGNORE_CASE)

    /**
     * Handles reminder commands locally (no model call). Returns true if the
     * text was a reminder command and was fully handled.
     */
    private fun tryHandleReminderCommand(body: String): Boolean {
        val trimmed = body.trim()
        if (reminderCancelId.matches(trimmed) || reminderCancelEn.matches(trimmed)) {
            cancelAllReminders(app)
            appendLocalExchange(
                trimmed,
                "✅ Semua pengingat yang dijadwalkan sudah dibatalkan.",
            )
            return true
        }
        val spec = ReminderParser.parse(trimmed) ?: return false
        scheduleReminder(app, spec)
        val whenStr =
            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("id"))
                .format(java.util.Date(spec.triggerAtMillis))
        appendLocalExchange(
            trimmed,
            "⏰ Pengingat diset untuk $whenStr:\n\"${spec.note}\"",
        )
        return true
    }

    /** Appends a user/assistant pair for locally-handled commands (no model call). */
    private fun appendLocalExchange(userText: String, assistantText: String) {
        if (conversation == null) {
            val newConv = Conversation().also { currentConversationId = it.id }
            conversation = newConv
            nodes.clear()
            messages.clear()
            refreshPinState()
        }
        val userEntry = ChatMessageEntry(role = ChatConstants.ROLE_USER, content = userText)
        messages.add(userEntry)
        messages.add(
            ChatMessageEntry(
                role = ChatConstants.ROLE_ASSISTANT,
                content = assistantText,
                parentId = userEntry.id,
            ),
        )
        persist()
    }

    fun sendMessage(userText: String) {
        val body = userText.trim()
        val pending = selectedAttachments.value
        if ((body.isBlank() && pending.isEmpty()) || isGenerating.value) return
        // Local command: reminder scheduling/cancellation is handled on-device
        // without a model call.
        if (pending.isEmpty() && tryHandleReminderCommand(body)) return
        // Set flag immediately to prevent double-send race condition
        isGenerating.value = true
        errorMessage.value = null
        val s = effectiveSettings()
        // Compare mode: stash the challenger pass; pass 1 runs on the active
        // model, pass 2 is chained from the generation finally-block.
        pendingComparePass = comparePassFor(s)
        genJob =
            viewModelScope.launch {
            // Persist + process attachments: images downscaled, document text
            // extracted, PDFs rendered to page images.
            val prepared: List<PreparedAttachment> = AttachmentProcessor.prepare(app, pending)
            clearSelectedImage()

            val attachments = prepared.map { it.attachment }
            val imagePaths = attachments.filter { it.type == "image" }.map { it.path }
            // Inject extracted document text into the prompt.
            val docBlocks =
                prepared.mapNotNull { pa ->
                    pa.extractedText?.takeIf { it.isNotBlank() }?.let { text ->
                        "\n\n[Attached file \"${pa.attachment.name}\":]\n$text"
                    }
                }
            val unsupportedNote =
                prepared
                    .filter { it.attachment.type == "file" && it.extractedText == null }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { "\"${it.attachment.name}\"" }
                    ?.let { "\n\n[Note: attached file(s) $it could not be read as text.]" }
                    .orEmpty()

            val promptText =
                buildString {
                    append(if (body.isNotBlank()) body else defaultPromptForAttachments(prepared))
                    docBlocks.forEach { append(it) }
                    append(unsupportedNote)
                }

            if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
                sendRemoteMessage(promptText, s, imagePaths, attachments)
                return@launch
            }

            val modelPath = ChatServerService.resolveModelPath(app, s)
            if (modelPath == null) {
                errorMessage.value = "No on-device model found. Please download a model or switch to Remote Provider."
                isGenerating.value = false
                return@launch
            }

            if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }
            // Local engine supports a single vision image: use the first one.
            val firstImageBytes = imagePaths.firstOrNull()?.let { try { File(it).readBytes() } catch (_: Exception) { null } }
            val userEntry =
                ChatMessageEntry(
                    role = ChatConstants.ROLE_USER,
                    content = promptText,
                    imagePath = imagePaths.firstOrNull(),
                    attachments = attachments,
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
            // Single coroutine for the whole send flow: previously a redundant
            // nested launch overwrote genJob mid-flight, so stopGeneration()
            // could miss the outer job.
            try {
                        val activeSummary = runCatching { ensureContextFit(s, modelPath) }.getOrNull()
                        val appliedWindow = activeSummary != null
                        val windowed = promptWindowMessages()
                        val (ragSystem, sources1) = withRagSystem(s.systemPrompt, promptText, s)
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
                            collectStreamIntoMessage(
                                deltas =
                                    engine.streamResponse(
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
                                        imagePath = imagePaths.firstOrNull(),
                                        imageBytes = firstImageBytes,
                                        onMetrics = { lastMetrics = it },
                                    ),
                                targetChatId = targetChatId,
                            )

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
                        // Drop compare state: a failed/cancelled/switched pass 1 must not chain pass 2.
                        pendingComparePass = null
                        if (e is CancellationException || currentConversationId != targetChatId) return@launch
                        KLog.e("ChatVM", "Generation failed", e)
                        if (messages.lastOrNull()?.content?.isBlank() == true) {
                            messages.removeAt(messages.lastIndex)
                        }
                        errorMessage.value = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        // Consume compare state up-front: a conversation switch
                        // mid-generation must not leak a stale pass into a
                        // later, unrelated generation.
                        val pass2 = pendingComparePass
                        val merge = pendingCompareMerge
                        pendingComparePass = null
                        pendingCompareMerge = null
                        if (currentConversationId == targetChatId) {
                            if (lastMetrics != null && messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                                val idx = messages.lastIndex
                                val current = messages[idx]
                                val variants = if (current.variants.orEmpty().isEmpty()) listOf(current.content) else current.variants
                                messages[idx] = current.copy(stats = lastMetrics.displayBadge, variants = variants, sources = ragSources)
                            }
                            persist()
                            captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                            when {
                                // Compare pass 1 done: stay in generating state, run the challenger.
                                pass2 != null -> {
                                    pendingCompareMerge = pass2
                                    runComparePass2(pass2)
                                }
                                // Compare pass 2 done: fold the challenger output into A's variants.
                                merge != null -> {
                                    isGenerating.value = false
                                    mergeLastAssistantAsVariant(merge)
                                }
                                else -> isGenerating.value = false
                            }
                        }
                    }
        }
    }

    /**
     * Encodes a persisted attachment image as an OpenAI-style
     * `data:image/jpeg;base64,...` URL for remote vision models.
     * The persisted file is already a downscaled JPEG (≤1024px, q85).
     */
    private suspend fun encodeImageDataUrl(imagePath: String?): String? =
        withContext(Dispatchers.IO) {
            if (imagePath.isNullOrBlank()) return@withContext null
            try {
                val file = File(imagePath)
                if (!file.exists() || file.length() == 0L || file.length() > 8_000_000L) return@withContext null
                "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            } catch (t: Throwable) {
                KLog.w("ChatVM", "Failed to encode image for remote: ${t.message}")
                null
            }
        }

    private fun sendRemoteMessage(
        body: String,
        s: Settings,
        imagePaths: List<String> = emptyList(),
        attachments: List<ChatAttachment> = emptyList(),
    ) {
        if (s.remoteBaseUrl.isBlank() || s.remoteModelId.isBlank()) {
            errorMessage.value = "Please configure Remote Base URL and select a model in Models tab."
            // B1: sendMessage() already set isGenerating=true before calling us;
            // reset it so the send button doesn't get stuck as a Stop button.
            isGenerating.value = false
            return
        }
        if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }

        // Regenerate/edit flows pass a blank body to reuse the existing user
        // message — don't append an empty user node in that case.
        val userEntry =
            if (body.isBlank()) {
                messages.lastOrNull { it.role == ChatConstants.ROLE_USER }
                    ?: run {
                        errorMessage.value = "No user message to regenerate."
                        return
                    }
            } else {
                ChatMessageEntry(
                    role = ChatConstants.ROLE_USER,
                    content = body,
                    imagePath = imagePaths.firstOrNull(),
                    attachments = attachments,
                    parentId = messages.lastOrNull()?.id,
                ).also {
                    messages.add(it)
                    registerActiveChild(it)
                }
            }
        val branchQuery = userEntry.content
        // Regenerate reuses the stored attachments of the existing message.
        val effectiveImagePaths =
            if (imagePaths.isNotEmpty()) {
                imagePaths
            } else {
                userEntry.attachments.filter { it.type == "image" }.map { it.path }
                    .ifEmpty { listOfNotNull(userEntry.imagePath) }
            }

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = userEntry.id)
        messages.add(assistant)
        registerActiveChild(assistant)
        isGenerating.value = true

        val start = System.currentTimeMillis()
        var tokenCount = 0
        var promptTokensEstimate = 0
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
                    val (ragSystem, sources) = withRagSystem(systemContent, branchQuery, s)
                    ragSources = sources
                    // Remote context window: per-model override wins, otherwise
                    // the global remote default. The on-device setting is left
                    // untouched so switching back to local restores it as-is.
                    val remoteWindow = s.effectiveRemoteContextWindow(s.remoteModelId)
                    val localModelPath = ChatServerService.resolveModelPath(app, s)
                    val activeSummary =
                        runCatching { ensureContextFit(s, localModelPath, remoteWindow) }.getOrNull()
                    val appliedWindowRemote = !activeSummary.isNullOrBlank()
                    val finalSystem =
                        injectSummary(ragSystem, activeSummary, appliedWindowRemote)

                    val imageDataUrls = effectiveImagePaths.mapNotNull { encodeImageDataUrl(it) }
                    val history = mutableListOf<RemoteChatMessage>()
                    if (finalSystem.isNotBlank()) {
                        history.add(RemoteChatMessage(role = "system", text = finalSystem))
                    }
                    val windowed = promptWindowMessages()
                    windowed.dropLast(1).forEach {
                        history.add(RemoteChatMessage(role = it.role, text = it.content))
                    }
                    // Attach the image to the latest user message (last entry).
                    if (imageDataUrls.isNotEmpty() && history.isNotEmpty()) {
                        val last = history.last()
                        history[history.lastIndex] = last.copy(imageDataUrls = imageDataUrls)
                    }
                    // Hard-cap to the remote window so long conversations are
                    // trimmed client-side instead of failing with a 400 from
                    // the server.
                    val tokenCounts =
                        history.map { msg ->
                            estimateTokens(msg.text) + msg.imageDataUrls.size * REMOTE_IMAGE_TOKEN_ESTIMATE
                        }
                    val capped = capMessagesToWindow(history, tokenCounts, remoteWindow, s.maxTokens + 256)
                    promptTokensEstimate = tokenCounts.sum()

                    collectStreamIntoMessage(
                        deltas =
                            RemoteAiClient.streamChat(
                                baseUrl = s.remoteBaseUrl,
                                apiKey = s.remoteApiKey,
                                model = s.remoteModelId,
                                messages = capped,
                                temperature = s.temperature,
                                topP = s.topP,
                                maxTokens = s.maxTokens,
                            ),
                        targetChatId = targetRemoteChatId,
                        onDelta = { tokenCount++ },
                    )
                } catch (e: Exception) {
                    // Drop compare state: a failed/cancelled/switched pass 1 must not chain pass 2.
                    pendingComparePass = null
                    if (e is CancellationException || currentConversationId != targetRemoteChatId) return@launch
                    KLog.e("ChatVM", "Remote generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Remote generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    // Consume compare state up-front (see sendMessage's finally).
                    val pass2 = pendingComparePass
                    val merge = pendingCompareMerge
                    pendingComparePass = null
                    pendingCompareMerge = null
                    if (currentConversationId == targetRemoteChatId) {
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val duration = maxOf(0.1f, (System.currentTimeMillis() - start) / 1000f)
                            val tokSec = tokenCount / duration
                            val badge = "%.1f tok/s · %d tokens · Remote".format(tokSec, tokenCount)
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            val variants = if (current.variants.orEmpty().isEmpty()) listOf(current.content) else current.variants
                            messages[idx] = current.copy(stats = badge, variants = variants, sources = ragSources)
                            recordRemoteCost(s.remoteModelId, promptTokensEstimate, tokenCount)
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                        when {
                            pass2 != null -> {
                                pendingCompareMerge = pass2
                                runComparePass2(pass2)
                            }
                            merge != null -> {
                                isGenerating.value = false
                                mergeLastAssistantAsVariant(merge)
                            }
                            else -> isGenerating.value = false
                        }
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
        val s = effectiveSettings()

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
                    val (ragSystem, sources2) = withRagSystem(s.systemPrompt, newText.trim(), s)
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
                        collectStreamIntoMessage(
                            deltas =
                                engine.streamResponse(
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
                                ),
                            targetChatId = targetChatId,
                        )

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
        regenerateInternal(sOverride = null, keepExisting = false, allowWhileGenerating = false)
    }

    /**
     * Regenerate flow shared by the UI button and compare-mode pass 2.
     *
     * @param sOverride settings to generate with (default: effective settings).
     * @param keepExisting when true the trailing assistant message is kept and
     * the new output is appended after it (compare mode) instead of replacing it.
     * @param allowWhileGenerating bypass the [isGenerating] guard — compare mode
     * chains pass 2 while the flag is still true.
     */
    private fun regenerateInternal(
        sOverride: Settings?,
        keepExisting: Boolean,
        allowWhileGenerating: Boolean,
    ) {
        if ((!allowWhileGenerating && isGenerating.value) || messages.isEmpty()) return
        val lastAssistant = messages.lastOrNull { it.role == ChatConstants.ROLE_ASSISTANT }
        val existingVariants = lastAssistant?.variants?.toMutableList() ?: mutableListOf()
        if (lastAssistant != null && lastAssistant.content.isNotBlank() && !existingVariants.contains(lastAssistant.content)) {
            existingVariants.add(lastAssistant.content)
        }

        val lastUser = messages.lastOrNull { it.role == ChatConstants.ROLE_USER } ?: return
        val s = sOverride ?: effectiveSettings()

        // Validate before touching the message list: a failed validation must
        // not wipe the currently visible response.
        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            if (s.remoteBaseUrl.isBlank() || s.remoteModelId.isBlank()) {
                errorMessage.value = "Please configure Remote Base URL and select a model in Models tab."
                return
            }
            if (!keepExisting) removeTrailingAssistantMessage()
            sendRemoteMessage("", s)
            return
        }

        val modelPath = ChatServerService.resolveModelPath(app, s)
        if (modelPath == null) {
            errorMessage.value = "No model found to regenerate response."
            return
        }
        if (!keepExisting) removeTrailingAssistantMessage()

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
                    val (ragSystem, sources3) = withRagSystem(s.systemPrompt, lastUser.content, s)
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
                        collectStreamIntoMessage(
                            deltas =
                                engine.streamResponse(
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
                                ),
                            targetChatId = targetRegenChatId,
                        )

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
                    // Consume compare-merge state up-front (see sendMessage's finally).
                    val merge = pendingCompareMerge
                    pendingCompareMerge = null
                    if (currentConversationId == targetRegenChatId) {
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
                        if (merge != null) {
                            mergeLastAssistantAsVariant(merge)
                        }
                        isGenerating.value = false
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

    fun setRemoteImageModelId(id: String) = launchSetting { settingsRepo.setRemoteImageModelId(id) }

    fun setRemoteModelId(id: String) =
        launchSetting {
            settingsRepo.setRemoteModelId(id)
            // Auto-detect the context window for a newly selected model, once:
            // skip when a per-model override was already stored.
            if (id.isNotBlank() &&
                parseRemoteModelContextWindows(settings.value.remoteModelContextWindowsJson)[id] == null
            ) {
                detectRemoteContextWindow(id)
            }
        }

    fun setRemoteContextWindowTokens(tokens: Int) =
        launchSetting { settingsRepo.setRemoteContextWindowTokens(tokens) }

    fun clearRemoteModelContextWindow(modelId: String) =
        launchSetting { settingsRepo.clearRemoteModelContextWindow(modelId) }

    fun setCompareMode(enabled: Boolean) = launchSetting { settingsRepo.setCompareMode(enabled) }

    fun setCompareChallenger(source: String, modelId: String) =
        launchSetting { settingsRepo.setCompareChallenger(source, modelId) }

    /** Short display name of the currently active model (compare label A). */
    private fun activeModelLabel(s: Settings): String =
        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            "🌐 ${s.remoteModelId.ifBlank { "remote" }}"
        } else {
            val path = ChatServerService.resolveModelPath(app, s)
            val name = path?.let { File(it).nameWithoutExtension }.orEmpty()
            "📱 ${name.ifBlank { s.activeModelId.ifBlank { "local" } }}"
        }

    /**
     * Builds the challenger pass for compare mode, or null when compare is
     * off / misconfigured / identical to the active model.
     */
    private fun comparePassFor(s: Settings): ComparePass? {
        if (!s.compareMode) return null
        val labelA = activeModelLabel(s)
        return when (s.compareSource) {
            ChatConstants.SOURCE_REMOTE -> {
                if (s.compareModelId.isBlank() || s.remoteBaseUrl.isBlank()) return null
                if (s.modelSource == ChatConstants.SOURCE_REMOTE && s.compareModelId == s.remoteModelId) return null
                ComparePass(
                    settings = s.copy(modelSource = ChatConstants.SOURCE_REMOTE, remoteModelId = s.compareModelId),
                    labelA = labelA,
                    labelB = "🌐 ${s.compareModelId}",
                )
            }
            ChatConstants.SOURCE_LOCAL -> {
                val path =
                    s.compareModelId.ifBlank { ChatServerService.resolveModelPath(app, s) }
                        ?: return null
                val curPath = ChatServerService.resolveModelPath(app, s)
                if (s.modelSource == ChatConstants.SOURCE_LOCAL && path == curPath) return null
                ComparePass(
                    settings = s.copy(modelSource = ChatConstants.SOURCE_LOCAL, customModelPath = path),
                    labelA = labelA,
                    labelB = "📱 ${File(path).nameWithoutExtension.ifBlank { "local" }}",
                )
            }
            else -> null
        }
    }

    /**
     * Starts compare pass 2: reuses the last user message and generates with
     * the challenger settings. The resulting assistant message is merged as
     * a variant of pass 1's message in the generation finally-block.
     * Must be called with [isGenerating] still true.
     */
    private fun runComparePass2(pass: ComparePass) {
        val jobBefore = genJob
        regenerateInternal(sOverride = pass.settings, keepExisting = true, allowWhileGenerating = true)
        if (genJob == null || genJob === jobBefore) {
            // Validation failed inside regenerateInternal: nothing launched.
            // Bail out of compare cleanly instead of leaving a stale merge.
            isGenerating.value = false
            if (errorMessage.value.isNullOrBlank()) {
                errorMessage.value = "Compare challenger unavailable."
            }
        } else {
            pendingCompareMerge = pass
        }
    }

    /**
     * Merges the last assistant message (compare pass 2 output) into the
     * previous assistant message as an extra variant, then drops the orphaned
     * node so reloads keep a single message with a 1/2 switcher.
     */
    private fun mergeLastAssistantAsVariant(pass: ComparePass) {
        if (messages.size < 2) return
        val b = messages.removeAt(messages.lastIndex)
        if (b.role != ChatConstants.ROLE_ASSISTANT) {
            messages.add(b)
            return
        }
        val aIdx = messages.lastIndex
        if (aIdx < 0) {
            messages.add(b)
            return
        }
        val a = messages[aIdx]
        if (a.role != ChatConstants.ROLE_ASSISTANT) {
            messages.add(b)
            return
        }
        nodes.removeAll { it.id == b.id }
        if (b.content.isBlank()) {
            // Challenger produced nothing (e.g. stopped): just drop it.
            registerActiveChild(a)
            persist()
            return
        }
        val variants = if (a.variants.isEmpty()) listOf(a.content) else a.variants
        val merged =
            a.copy(
                variants = variants + b.content,
                stats = "${a.stats} · ⚖️ ${pass.labelA} vs ${pass.labelB}",
            )
        messages[aIdx] = merged
        registerActiveChild(merged)
        persist()
    }

    // ── MCP client ────────────────────────────────────────────────────

    private val mcpManager get() = com.localgpt.app.mcp.McpManager.getInstance(app)
    val mcpServers: StateFlow<List<com.localgpt.app.mcp.McpServerConfig>> get() = mcpManager.servers
    val mcpToolsByServer: StateFlow<Map<String, List<com.localgpt.app.mcp.McpTool>>> get() = mcpManager.toolsByServer
    val mcpToolErrors: StateFlow<Map<String, String>> get() = mcpManager.errors

    /**
     * Explicit safety boundary for autonomous MCP tool calls: when the model
     * emits a tool call, generation suspends here until the user approves or
     * denies it in the UI. Never auto-approved.
     */
    val pendingMcpApproval = mutableStateOf<com.localgpt.app.mcp.McpCallRequest?>(null)
    private var mcpApproval: CompletableDeferred<Boolean>? = null

    fun setMcpEnabled(enabled: Boolean) = launchSetting { settingsRepo.setMcpEnabled(enabled) }

    fun upsertMcpServer(
        server: com.localgpt.app.mcp.McpServerConfig,
        authToken: String? = null,
    ) = viewModelScope.launch(Dispatchers.IO) { settingsRepo.upsertMcpServer(server, authToken) }

    fun removeMcpServer(serverId: String) =
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.removeMcpServer(serverId) }

    fun setMcpServerEnabled(server: com.localgpt.app.mcp.McpServerConfig, enabled: Boolean) =
        upsertMcpServer(server.copy(enabled = enabled))

    fun refreshMcpTools() =
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mcpManager.refreshAllEnabled() }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "MCP discovery failed: ${e.message}"
                    }
                }
        }

    fun refreshMcpServerTools(server: com.localgpt.app.mcp.McpServerConfig) =
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mcpManager.refreshTools(server) }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "MCP '${server.name}': ${e.message}"
                    }
                }
        }

    /**
     * Manual tool invocation from the MCP screen. This is an explicit user
     * action, so it bypasses the approval gate used by autonomous calls.
     */
    fun testMcpCall(
        serverId: String,
        toolName: String,
        argsJson: String,
        onResult: (String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching {
                    val args =
                        runCatching {
                            com.google.gson.JsonParser.parseString(argsJson.ifBlank { "{}" }).asJsonObject
                        }.getOrElse { com.google.gson.JsonObject() }
                    mcpManager.callTool(serverId, toolName, args)
                }
            withContext(Dispatchers.Main) {
                onResult(
                    result.fold(
                        onSuccess = {
                            if (it.isError) "⚠️ Tool returned an error:\n${it.text}" else "✅ Result:\n${it.text}"
                        },
                        onFailure = { "❌ Call failed: ${it.message}" },
                    ),
                )
            }
        }
    }

    /** Called from the approval dialog. */
    fun respondMcpApproval(approved: Boolean) {
        val d = mcpApproval
        mcpApproval = null
        pendingMcpApproval.value = null
        d?.complete(approved)
    }

    /** Denies any pending approval (stop / conversation switch). */
    private fun denyMcpApproval() {
        val d = mcpApproval
        mcpApproval = null
        pendingMcpApproval.value = null
        d?.complete(false)
    }

    private suspend fun requestMcpApproval(req: com.localgpt.app.mcp.McpCallRequest): Boolean {
        // Deny a stale request before arming a new one.
        mcpApproval?.complete(false)
        val d = CompletableDeferred<Boolean>()
        mcpApproval = d
        pendingMcpApproval.value = req
        return try {
            d.await()
        } finally {
            // Only clear if this request is still the current one — a newer
            // request may have armed itself while this one was settling.
            if (mcpApproval === d) {
                mcpApproval = null
                pendingMcpApproval.value = null
            }
        }
    }

    /**
     * Builds the MCP tools section injected into the system prompt for local
     * generation. Capped so small-context on-device models aren't starved.
     * Remote providers are excluded: their tool calls have no executor here.
     */
    private fun mcpToolsPromptBlock(s: Settings): String {
        if (!s.mcpEnabled || s.modelSource != ChatConstants.SOURCE_LOCAL) return ""
        val tools = mcpManager.allEnabledTools().take(12)
        if (tools.isEmpty()) return ""
        return buildString {
            append("\n\n[MCP TOOLS]\n")
            append("You may call tools hosted on the user's connected MCP servers. To call one, emit EXACTLY one block and then stop:\n")
            append("<tool_call>{\"name\": \"<tool_name>\", \"arguments\": {…}}</tool_call>\n")
            append("The user must approve each call; after approval the tool result is provided and you continue. Never invent tool results.\n")
            append("Available tools:\n")
            tools.forEach { t ->
                append("- ${t.signature}")
                val desc = t.description.take(160)
                if (desc.isNotBlank()) append(": $desc")
                append("  [server: ${t.serverName}]\n")
            }
        }.take(2500)
    }

    /**
     * Best-effort detection of the selected remote model's context window
     * (Ollama `/api/show`, OpenRouter `context_length`). On success the value
     * is stored as a per-model override; the global remote default and the
     * on-device setting are untouched.
     */
    fun detectRemoteContextWindow(modelId: String = settings.value.remoteModelId) {
        val s = settings.value
        if (s.remoteBaseUrl.isBlank() || modelId.isBlank()) {
            errorMessage.value = "Set the Remote Base URL and select a model first."
            return
        }
        if (isDetectingContextWindow.value) return
        isDetectingContextWindow.value = true
        viewModelScope.launch {
            try {
                val detected =
                    RemoteAiClient.detectContextWindow(s.remoteBaseUrl, s.remoteApiKey, modelId)
                if (detected != null) {
                    settingsRepo.setRemoteModelContextWindow(modelId, detected)
                } else {
                    errorMessage.value =
                        "Could not detect the context window for this provider — set it manually below."
                }
            } finally {
                isDetectingContextWindow.value = false
            }
        }
    }

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
            // Best-effort: cache per-model pricing for cost estimates (OpenRouter only).
            runCatching {
                val pricing = RemoteAiClient.fetchOpenRouterPricing(baseUrl, apiKey)
                if (pricing.isNotEmpty()) {
                    settingsRepo.setRemoteModelPricingJson(remoteModelPricingToJson(pricing))
                }
            }
            isFetchingRemoteModels.value = false
        }
    }

    /** Records the estimated USD cost of one remote generation. */
    private fun recordRemoteCost(modelId: String, promptTokens: Int, completionTokens: Int) {
        if (modelId.isBlank() || (promptTokens <= 0 && completionTokens <= 0)) return
        val pricing = parseRemoteModelPricing(settings.value.remoteModelPricingJson)[modelId] ?: return
        val usd = promptTokens * pricing.promptPerToken + completionTokens * pricing.completionPerToken
        if (usd <= 0) return
        viewModelScope.launch {
            settingsRepo.addRemoteSpendMicros((usd * 1_000_000).toLong().coerceAtLeast(1L))
        }
    }

    fun resetRemoteSpend() {
        viewModelScope.launch { settingsRepo.resetRemoteSpend() }
    }

    // ── Model Management ─────────────────────────────────────────────

    fun loadActiveModel() {
        val s = settings.value
        val modelPath = ChatServerService.resolveModelPath(app, s) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
            } catch (e: Exception) {
                // Jangan biarkan exception tak tertangkap membunuh proses (force close),
                // mis. file model tidak ada. Tampilkan sebagai pesan error biasa.
                KLog.e("ChatViewModel", "loadActiveModel failed", e)
                withContext(Dispatchers.Main) {
                    errorMessage.value = "Gagal memuat model: ${e.message}"
                }
            }
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

    fun downloadCustomUrl(downloadUrl: String, fileName: String? = null, expectedSizeBytes: Long = 0L, sha256: String? = null) {
        val fn = fileName?.ifBlank { null } ?: downloadUrl.substringAfterLast('/').substringBefore('?').ifBlank { "custom_model.litertlm" }
        val id = "custom_" + fn.hashCode()
        downloader.startCustomDownload(id, fn, downloadUrl, expectedSizeBytes, expectedSha256 = sha256)
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
            settingsRepo.setBackend(ChatConstants.BACKEND_GPU)
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
