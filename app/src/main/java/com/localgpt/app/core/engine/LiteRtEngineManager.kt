package com.localgpt.app.core.engine

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.localgpt.app.util.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton wrapper around Google AI Edge LiteRT-LM.
 *
 * Ported from KZKT's LiteRtInferenceEngine with one key addition: token-level
 * streaming ([streamResponse]) so both the in-app chat UI and the embedded
 * OpenAI-compatible /v1 server can render tokens as they are generated.
 *
 * A single [Mutex] serializes all requests: the LLM cannot truly run in
 * parallel, so concurrent callers (UI + HTTP clients) simply queue up.
 */
class LiteRtEngineManager private constructor(
    private val context: Context,
) {
    companion object {
        @Volatile
        private var instance: LiteRtEngineManager? = null

        fun getInstance(context: Context): LiteRtEngineManager =
            instance ?: synchronized(this) {
                instance ?: LiteRtEngineManager(context.applicationContext).also { instance = it }
            }

        private val _engineLogs = MutableStateFlow<List<String>>(emptyList())
        val engineLogs: StateFlow<List<String>> = _engineLogs

        private val logScope = CoroutineScope(Dispatchers.IO)
        private var logFile: File? = null

        fun initDiskLogging(context: Context) {
            if (logFile == null) {
                val dir = File(context.cacheDir, "litert_logs").apply { mkdirs() }
                logFile = File(dir, "litert_runtime.log")
            }
        }

        fun logEngine(message: String) {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val formatted = "[$timestamp] $message"
            val current = _engineLogs.value.toMutableList()
            if (current.size > 300) current.removeAt(0)
            current.add(formatted)
            _engineLogs.value = current

            logScope.launch {
                try {
                    logFile?.appendText("$formatted\n")
                } catch (_: Throwable) {
                }
            }
        }

        fun getFullLogs(context: Context): String {
            val dir = File(context.cacheDir, "litert_logs")
            val file = File(dir, "litert_runtime.log")
            return if (file.exists()) {
                try {
                    file.readText()
                } catch (_: Exception) {
                    _engineLogs.value.joinToString("\n")
                }
            } else {
                _engineLogs.value.joinToString("\n")
            }
        }

        fun clearLogs() {
            _engineLogs.value = emptyList()
            logScope.launch {
                try {
                    logFile?.writeText("")
                } catch (_: Throwable) {
                }
            }
        }

        fun getSystemSpecs(context: Context): SystemSpecs {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalRamGb = memInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
            val availRamGb = memInfo.availMem.toDouble() / (1024 * 1024 * 1024)
            val cores = Runtime.getRuntime().availableProcessors()
            val manufacturer = android.os.Build.MANUFACTURER
            val model = android.os.Build.MODEL
            val hardware =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    android.os.Build.SOC_MODEL
                } else {
                    android.os.Build.HARDWARE
                }
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val osVersion = "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
            return SystemSpecs(
                deviceModel = "$manufacturer $model",
                chipset = hardware,
                cores = cores,
                abi = abi,
                osVersion = osVersion,
                availRamGb = availRamGb,
                totalRamGb = totalRamGb,
                isLowMemory = memInfo.lowMemory,
            )
        }

        data class SystemSpecs(
            val deviceModel: String,
            val chipset: String,
            val cores: Int,
            val abi: String,
            val osVersion: String,
            val availRamGb: Double,
            val totalRamGb: Double,
            val isLowMemory: Boolean,
        )
    }

    data class EngineParams(
        val modelPath: String,
        val temperature: Float = 0.2f,
        val topK: Int = 40,
        val topP: Float = 0.90f,
        val maxTokens: Int = 512,
        val contextWindow: Int = 2048,
        val backend: String = "GPU", // "GPU" | "CPU"
        val systemPrompt: String = "",
        val enableThinking: Boolean = true,
    )

    init {
        initDiskLogging(context)
    }

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var activeModelPath: String? = null
    private var activeBackend: String? = null
    private var activeVisionSupported: Boolean = false

    /** Conversation of the in-flight generation, so [cancelGeneration] can abort it natively. */
    @Volatile
    private var activeConversation: com.google.ai.edge.litertlm.Conversation? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    private val _isLoadingModel = MutableStateFlow(false)
    val isLoadingModel: StateFlow<Boolean> = _isLoadingModel

    private val _loadedModelPath = MutableStateFlow<String?>(null)
    val loadedModelPath: StateFlow<String?> = _loadedModelPath

    private val _activeBackendState = MutableStateFlow<String?>(null)
    val activeBackendState: StateFlow<String?> = _activeBackendState

    /** True while a generation request (chat or /v1) is being executed. */
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    /**
     * Explicitly loads the model into memory/GPU. Returns true on success.
     * Guaranteed to run on Dispatchers.IO to never block the Android UI Main Thread.
     */
    suspend fun load(params: EngineParams): Boolean =
        withContext(Dispatchers.IO) {
            _isLoadingModel.value = true
            try {
                getOrCreateEngine(params)
                _isModelLoaded.value
            } finally {
                _isLoadingModel.value = false
            }
        }

    suspend fun unload() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                unloadLocked()
            }
        }

    data class InferenceMetrics(
        val tokensPerSec: Float,
        val ttftMs: Long,
        val totalTokens: Int,
        val durationSec: Float,
        val backend: String,
    ) {
        val displayBadge: String
            get() = "%.1f tok/s · %d tokens · %s".format(Locale.US, tokensPerSec, totalTokens, backend)
    }

    /**
     * Streaming generation. Emits response deltas as they are produced by the
     * model. Initializes the engine on demand (with GPU -> CPU fallback).
     */
    fun streamResponse(
        prompt: String,
        params: EngineParams,
        imagePath: String? = null,
        imageBytes: ByteArray? = null,
        onMetrics: ((InferenceMetrics) -> Unit)? = null,
    ): Flow<String> =
        flow {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (e: Throwable) {
                KLog.e("LiteRT", "Failed to set thread priority: ${e.message}")
            }

            val requestStart = System.currentTimeMillis()

            // Honor EngineParams.systemPrompt. Chat flows normally bake it into the
            // prompt via PromptBuilder; apply it here only when it isn't already
            // present, so direct callers (e.g. benchmark) get a working persona too.
            val systemText = params.systemPrompt.trim()
            val effectivePrompt =
                if (systemText.isNotEmpty() && !prompt.contains(systemText)) {
                    logEngine("[LiteRT] Applying EngineParams.systemPrompt at engine layer")
                    "$systemText\n\n$prompt"
                } else {
                    prompt
                }
            val inputTokens = estimateTokenCount(effectivePrompt)
            val modelName = File(params.modelPath).name

            // Stage vision input outside the lock: pure IO, no shared state.
            val validImageFile: File? =
                if (!imagePath.isNullOrBlank()) {
                    File(imagePath).takeIf { it.exists() }
                } else if (imageBytes != null && imageBytes.isNotEmpty()) {
                    try {
                        val temp = File(context.cacheDir, "temp_vision_input.jpg")
                        temp.writeBytes(imageBytes)
                        temp
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

            _isGenerating.value = true
            try {
                var firstTokenTime = 0L
                var engineReadyTime = 0L
                var outputChars = 0
                var maxTokensReached = false
                val maxTokens = params.maxTokens.takeIf { it > 0 } ?: Int.MAX_VALUE

                var outputText = ""
                var inThinkingBlock = false
                var thoughtSeen = ""

                val processChunk: suspend (Message) -> Unit = { chunk ->
                    val textDelta =
                        chunk.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                    val rawThought = chunk.channels.values.joinToString("")
                    var thoughtDelta = ""
                    if (rawThought.isNotEmpty()) {
                        thoughtDelta =
                            if (rawThought.startsWith(thoughtSeen)) {
                                rawThought.substring(thoughtSeen.length)
                            } else {
                                rawThought
                            }
                        thoughtSeen =
                            if (rawThought.startsWith(thoughtSeen)) rawThought else thoughtSeen + rawThought
                    }

                    var out = ""
                    if (thoughtDelta.isNotEmpty()) {
                        if (!inThinkingBlock) {
                            out += "<think>"
                            inThinkingBlock = true
                        }
                        out += thoughtDelta
                    }
                    if (textDelta.isNotEmpty()) {
                        if (inThinkingBlock) {
                            out += "</think>\n\n"
                            inThinkingBlock = false
                        }
                        out += textDelta
                    }
                    if (out.isNotEmpty()) {
                        if (firstTokenTime == 0L) {
                            firstTokenTime = System.currentTimeMillis()
                        }
                        outputText += out
                        outputChars += out.length
                        emit(out)
                        // Per-request maxTokens enforcement (chars/3.8 token estimate).
                        // Stops the native generation; the resulting CancellationException
                        // is treated as a normal stop by the collector below.
                        if (!maxTokensReached && outputChars / 3.8 >= maxTokens) {
                            maxTokensReached = true
                            logEngine("[LiteRT] maxTokens ($maxTokens) reached — stopping generation")
                            cancelGeneration()
                        }
                    }
                }

                // Engine acquisition happens INSIDE the same mutex as generation, so
                // unload() can never close the engine out from under an in-flight request.
                var backendName = params.backend
                mutex.withLock {
                    val activeEngine = withContext(Dispatchers.IO) { getOrCreateEngineLocked(params) }
                    engineReadyTime = System.currentTimeMillis()
                    backendName = activeBackend ?: params.backend

                    val imgTag =
                        if (!imagePath.isNullOrBlank()) " + image ($imagePath)"
                        else if (imageBytes != null && imageBytes.isNotEmpty()) " + image (${imageBytes.size / 1024} KB)"
                        else ""
                    logEngine("[LiteRT Request] Stream on $backendName ($modelName)$imgTag")
                    logEngine("  ├── Input: ~$inputTokens tokens (${effectivePrompt.length} chars) · temp=${params.temperature}, topK=${params.topK}, maxTokens=${params.maxTokens}")

                    val sampler =
                        SamplerConfig(
                            topK = params.topK,
                            topP = params.topP.toDouble(),
                            temperature = params.temperature.toDouble(),
                        )
                    val conv = activeEngine.createConversation(ConversationConfig(samplerConfig = sampler))
                    activeConversation = conv
                    val thinkingConfig = ThinkingConfig(enableThinking = params.enableThinking)

                    try {
                        val stream =
                            if (validImageFile != null && activeVisionSupported) {
                                try {
                                    val contents =
                                        Contents.of(
                                            Content.ImageFile(validImageFile.absolutePath),
                                            Content.Text(effectivePrompt),
                                        )
                                    conv.sendMessageAsync(contents, thinkingConfig = thinkingConfig)
                                } catch (e: Throwable) {
                                    logEngine("[WARN] Multimodal vision input rejected (${e.message}). Falling back to text prompt.")
                                    conv.sendMessageAsync(effectivePrompt, thinkingConfig = thinkingConfig)
                                }
                            } else {
                                if (validImageFile != null && !activeVisionSupported) {
                                    logEngine("[WARN] Active model has no vision encoder. Prompting with text only.")
                                }
                                conv.sendMessageAsync(effectivePrompt, thinkingConfig = thinkingConfig)
                            }

                        try {
                            stream.collect(processChunk)
                        } catch (ce: CancellationException) {
                            // Deliberate stop at maxTokens: treat as normal completion.
                            // External cancellation (user pressed stop) still rethrows.
                            if (!maxTokensReached) throw ce
                            logEngine("[LiteRT] Generation stopped at maxTokens limit")
                        } catch (t: Throwable) {
                            if (validImageFile != null && outputText.isBlank()) {
                                logEngine("[WARN] Multimodal streaming encountered error (${t.message}), retrying with text prompt fallback...")
                                val fallbackStream = conv.sendMessageAsync(effectivePrompt, thinkingConfig = thinkingConfig)
                                try {
                                    fallbackStream.collect(processChunk)
                                } catch (ce: CancellationException) {
                                    if (!maxTokensReached) throw ce
                                    logEngine("[LiteRT] Generation stopped at maxTokens limit")
                                }
                            } else {
                                throw t
                            }
                        }
                    } finally {
                        activeConversation = null
                        try {
                            conv.close()
                        } catch (_: Exception) {
                        }
                    }
                }

                if (inThinkingBlock) {
                    outputText += "</think>"
                    emit("</think>")
                }

                val now = System.currentTimeMillis()
                val totalDurationSec = maxOf(0.01f, (now - requestStart) / 1000.0f)
                val decodeDurationSec = if (firstTokenTime > 0L) maxOf(0.01f, (now - firstTokenTime) / 1000.0f) else totalDurationSec
                // Token estimate from generated chars, not from chunk count.
                val outputTokens = estimateTokenCount(outputText)
                val tokPerSec = outputTokens / decodeDurationSec
                // Pure TTFT: measured from engine-ready, excluding cold-start model load.
                val ttft = if (firstTokenTime > 0L && engineReadyTime > 0L) maxOf(0L, firstTokenTime - engineReadyTime) else 0L

                logEngine("[LiteRT Response] Generated $outputTokens tokens in %.2fs (%.1f tok/s, TTFT: ${ttft}ms)".format(Locale.US, decodeDurationSec, tokPerSec))

                if (outputText.isBlank()) {
                    logEngine("[ERROR] Inference finished but output was EMPTY.")
                    KLog.e("LiteRT", "Empty model response")
                    throw IllegalStateException("Model response was empty")
                }

                val metrics =
                    InferenceMetrics(
                        tokensPerSec = tokPerSec,
                        ttftMs = ttft,
                        totalTokens = outputTokens,
                        durationSec = totalDurationSec,
                        backend = backendName,
                    )
                onMetrics?.invoke(metrics)
            } finally {
                _isGenerating.value = false
            }
        }.flowOn(Dispatchers.Default)

    /**
     * Non-streaming generation (accumulates [streamResponse]).
     */
    suspend fun generateResponse(
        prompt: String,
        params: EngineParams,
        imagePath: String? = null,
        imageBytes: ByteArray? = null,
    ): String {
        val sb = StringBuilder()
        streamResponse(prompt = prompt, params = params, imagePath = imagePath, imageBytes = imageBytes).collect { sb.append(it) }
        return sb.toString().trim()
    }

    /**
     * Aborts the in-flight generation natively (no-op if none is running).
     * The streaming collector receives a CancellationException and the
     * conversation is closed by [streamResponse]'s finally block.
     */
    fun cancelGeneration() {
        try {
            activeConversation?.cancelProcess()
        } catch (_: Exception) {
        }
    }

    /**
     * Initializes or retrieves an active LiteRT-LM Engine.
     * Falls back from GPU (OpenCL) to CPU (XNNPack) when initialization fails.
     * Fully runs on Dispatchers.IO to guarantee zero blocking of Android main thread.
     */
    suspend fun getOrCreateEngine(params: EngineParams): Engine =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                getOrCreateEngineLocked(params)
            }
        }

    /**
     * Engine init core. The caller must already hold [mutex]: [streamResponse]
     * acquires the engine inside the same lock that guards generation, so
     * [unload] can never close the engine underneath an in-flight request.
     */
    private suspend fun getOrCreateEngineLocked(params: EngineParams): Engine {
        val file = File(params.modelPath)
        if (!file.exists() || file.length() == 0L) {
            val err = "Model file not found at: ${params.modelPath}"
            logEngine("[ERROR] $err")
            KLog.e("LiteRT", err)
            throw IllegalStateException(err)
        }

        val needsReload =
            engine == null ||
                activeModelPath != params.modelPath ||
                (activeBackend != null && !activeBackend!!.startsWith(params.backend, ignoreCase = true))

        if (needsReload) {
            unloadLocked()
            val specs = getSystemSpecs(context)
            val fileSizeStr = formatBytes(file.length())
            val tokenBudget = maxOf(1024, maxOf(params.contextWindow, params.maxTokens))

            logEngine("[LiteRT Device] ${specs.deviceModel} (${specs.chipset}) · ${specs.cores} Cores · ${specs.abi}")
            logEngine(
                String.format(
                    Locale.US,
                    "[LiteRT Device] RAM: %.2f GB Free / %.2f GB Total (Low Memory: %b)",
                    specs.availRamGb,
                    specs.totalRamGb,
                    specs.isLowMemory,
                ),
            )
            logEngine("[LiteRT Engine] Model: ${file.name} ($fileSizeStr) · Target: ${params.backend} · Budget: $tokenBudget tokens")

            val startInit = System.currentTimeMillis()
            val candidateForVision = isVisionCandidate(params.modelPath)

            try {
                val targetBackend =
                    if (params.backend.equals("CPU", ignoreCase = true)) Backend.CPU() else Backend.GPU()
                val initTarget = if (targetBackend is Backend.GPU) "OpenCL GPU shader cache" else "CPU XNNPack engine"
                logEngine("[LiteRT Engine] Initializing $initTarget...")

                val (newEngine, visionOk) =
                    initEngineInstance(
                        modelPath = params.modelPath,
                        targetBackend = targetBackend,
                        tokenBudget = tokenBudget,
                        tryVision = candidateForVision,
                    )
                engine = newEngine
                activeBackend = params.backend
                activeVisionSupported = visionOk
                val initDuration = System.currentTimeMillis() - startInit
                val visionBadge = if (visionOk) " + Vision" else " (Text Only)"
                logEngine("[LiteRT Engine] ${params.backend}$visionBadge Initialization Successful (${initDuration}ms)")
            } catch (e: Exception) {
                if (!params.backend.equals("CPU", ignoreCase = true)) {
                    logEngine("[WARN] GPU OpenCL rejected (${e.message ?: e.javaClass.simpleName}). Falling back to CPU (XNNPack)...")
                    KLog.w("LiteRT", "GPU OpenCL rejected, falling back to CPU: ${e.message}")
                    try {
                        val (newEngine, visionOk) =
                            initEngineInstance(
                                modelPath = params.modelPath,
                                targetBackend = Backend.CPU(),
                                tokenBudget = tokenBudget,
                                tryVision = candidateForVision,
                            )
                        engine = newEngine
                        activeBackend = "CPU (Fallback)"
                        activeVisionSupported = visionOk
                        val fallbackDuration = System.currentTimeMillis() - startInit
                        logEngine("[LiteRT Engine] CPU Fallback Successful (${fallbackDuration}ms)")
                    } catch (cpuEx: Exception) {
                        val failMsg = "GPU and CPU initialization failed: ${cpuEx.message ?: cpuEx.javaClass.simpleName}"
                        logEngine("[ERROR] $failMsg")
                        KLog.e("LiteRT", failMsg, cpuEx)
                        throw IllegalStateException("Failed to load LiteRT model ($failMsg)", cpuEx)
                    }
                } else {
                    val failMsg = "CPU initialization failed: ${e.message ?: e.javaClass.simpleName}"
                    logEngine("[ERROR] $failMsg")
                    KLog.e("LiteRT", failMsg, e)
                    throw IllegalStateException("Failed to load LiteRT model ($failMsg)", e)
                }
            }
            activeModelPath = params.modelPath
            _activeBackendState.value = activeBackend
            _isModelLoaded.value = true
            _loadedModelPath.value = params.modelPath
        }

        return engine ?: throw IllegalStateException("Google AI Edge LiteRT-LM engine initialization failed.")
    }

    private fun isVisionCandidate(modelPath: String): Boolean {
        val name = File(modelPath).name.lowercase()
        return name.contains("e2b") ||
            name.contains("e4b") ||
            name.contains("paligemma") ||
            name.contains("vision") ||
            name.contains("vl-") ||
            name.contains("-vl") ||
            name.contains("multimodal")
    }

    private fun initEngineInstance(
        modelPath: String,
        targetBackend: Backend,
        tokenBudget: Int,
        tryVision: Boolean,
    ): Pair<Engine, Boolean> {
        if (tryVision) {
            var visionEng: Engine? = null
            try {
                visionEng =
                    Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = targetBackend,
                            visionBackend = targetBackend,
                            maxNumTokens = tokenBudget,
                            maxNumImages = 1,
                            cacheDir = context.cacheDir.absolutePath,
                        ),
                    )
                visionEng.initialize()
                // Verify conversation creation succeeds without TF_LITE_VISION_ENCODER missing error
                try {
                    val testConv = visionEng.createConversation()
                    testConv.close()
                    val ready = visionEng
                    if (ready != null) return Pair(ready, true)
                } catch (convEx: Throwable) {
                    if (!isVisionMissingError(convEx)) throw convEx
                    logEngine("[INFO] Model lacks vision encoder (${convEx.message}). Re-initializing as text LLM...")
                }
            } catch (initEx: Throwable) {
                if (!isVisionMissingError(initEx)) {
                    // Non-vision failure: release the half-built engine, then propagate.
                    closeQuietly(visionEng)
                    throw initEx
                }
                logEngine("[INFO] Model lacks vision encoder. Re-initializing as text LLM...")
            }
            // Vision attempt failed: never leak the native engine.
            closeQuietly(visionEng)
        }

        // Initialize pure text LLM (visionBackend = null, maxNumImages = null)
        val textEng =
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = targetBackend,
                    visionBackend = null,
                    maxNumTokens = tokenBudget,
                    maxNumImages = null,
                    cacheDir = context.cacheDir.absolutePath,
                ),
            )
        textEng.initialize()
        return Pair(textEng, false)
    }

    private fun isVisionMissingError(t: Throwable): Boolean =
        t.message?.contains("VISION_ENCODER", ignoreCase = true) == true ||
            t.message?.contains("TF_LITE_VISION", ignoreCase = true) == true

    private fun closeQuietly(eng: Engine?) {
        try {
            eng?.close()
        } catch (e: Exception) {
            KLog.e("LiteRT", "Failed to close engine", e)
        }
    }

    private fun unloadLocked() {
        try {
            engine?.close()
            logEngine("[LiteRT Engine] Model unloaded from memory successfully.")
        } catch (e: Exception) {
            logEngine("[WARN] Error while unloading engine: ${e.message}")
        } finally {
            engine = null
            activeModelPath = null
            activeBackend = null
            _activeBackendState.value = null
            _isModelLoaded.value = false
            _loadedModelPath.value = null
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return maxOf(1, Math.round(text.length / 3.8).toInt())
    }
}
