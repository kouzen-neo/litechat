package com.localgpt.app.core.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.localgpt.app.core.engine.LiteRtEngineManager
import com.localgpt.app.util.KLog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded OpenAI-compatible HTTP server (Ktor CIO).
 *
 * Endpoints:
 *   GET  /v1/models              -> list the active model
 *   POST /v1/chat/completions    -> stream:true = SSE, stream:false = JSON
 *   GET  /health                 -> "ok"
 *
 * All generation goes through [LiteRtEngineManager], whose mutex serializes
 * concurrent requests from multiple clients.
 */
object OpenAiServer {
    // ── Rate Limiting ────────────────────────────────────────────────
    private data class RateLimitEntry(
        val count: Int = 1,
        val windowStart: Long = System.currentTimeMillis(),
    )

    private val rateLimits = ConcurrentHashMap<String, RateLimitEntry>()
    private const val RATE_LIMIT_MAX_REQUESTS = 10
    private const val RATE_LIMIT_WINDOW_MS = 60_000L // 1 minute
    private val lastRateLimitCleanup = AtomicLong(0L)

    /**
     * Client IP taken from the actual TCP connection.
     * Never trust X-Forwarded-For here: on a direct LAN connection no proxy
     * sets it, and a client-supplied value is trivially spoofable — one client
     * could evade the limit or poison another client's bucket.
     */
    private fun ApplicationCall.remoteIp(): String = request.origin.remoteHost

    /** Returns true if the request is allowed, false if rate limited. */
    private fun checkRateLimit(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        // Lazy eviction: drop expired windows at most once per window so the
        // map cannot grow unboundedly from rotating/spoofed client IPs.
        val lastCleanup = lastRateLimitCleanup.get()
        if (now - lastCleanup > RATE_LIMIT_WINDOW_MS &&
            lastRateLimitCleanup.compareAndSet(lastCleanup, now)
        ) {
            rateLimits.entries.removeIf { now - it.value.windowStart > RATE_LIMIT_WINDOW_MS }
        }
        // NB: copy() instead of mutating — compute()'s lambda may be retried.
        val entry =
            rateLimits.compute(clientIp) { _, existing ->
                if (existing == null || now - existing.windowStart > RATE_LIMIT_WINDOW_MS) {
                    RateLimitEntry(1, now)
                } else {
                    existing.copy(count = existing.count + 1)
                }
            } ?: return true
        return entry.count <= RATE_LIMIT_MAX_REQUESTS
    }
    sealed class Status {
        data object Stopped : Status()

        data object Starting : Status()

        data class Running(
            val port: Int,
            val bindAll: Boolean,
        ) : Status()

        data class Error(
            val message: String,
        ) : Status()
    }

    data class ServerRequestLog(
        val id: String,
        val timestamp: Long = System.currentTimeMillis(),
        val method: String,
        val path: String,
        val clientIp: String,
        val status: Int,
        val durationMs: Long,
        val stream: Boolean,
    )

    private val _recentLogs = MutableStateFlow<List<ServerRequestLog>>(emptyList())
    val recentLogs: StateFlow<List<ServerRequestLog>> = _recentLogs.asStateFlow()

    private fun logRequest(entry: ServerRequestLog) {
        _recentLogs.update { list ->
            (if (list.size >= 25) list.drop(1) else list) + entry
        }
    }

    /** Everything the routes need, resolved once at startup. */
    data class Config(
        val port: Int,
        val bindAll: Boolean,
        val authToken: String,
        val systemPrompt: String,
        val temperature: Float,
        val topK: Int,
        val topP: Float = 0.90f,
        val maxTokens: Int,
        val backend: String,
        val modelPath: String,
    )

    private val gson = Gson()
    private var server: EmbeddedServer<*, *>? = null

    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _requestCount = MutableStateFlow(0L)
    val requestCount: StateFlow<Long> = _requestCount.asStateFlow()

    private val _activeRequests = MutableStateFlow(0)

    /** Number of generations currently in flight (used for WakeLock management). */
    val activeRequests: StateFlow<Int> = _activeRequests.asStateFlow()

    /** Runs [block] while counting it as an in-flight request. */
    private suspend fun <T> trackActive(block: suspend () -> T): T {
        _activeRequests.update { it + 1 }
        try {
            return block()
        } finally {
            _activeRequests.update { it - 1 }
        }
    }

    private const val MAX_BODY_BYTES = 2 * 1024 * 1024 // 2 MB

    /**
     * Reads the request body, rejecting oversized payloads with 413.
     * Returns null when the request was already answered with an error.
     */
    private suspend fun ApplicationCall.receiveTextLimited(): String? {
        val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > MAX_BODY_BYTES) {
            respondText(
                errorJson("invalid_request_error", "Request body too large (max 2 MB)"),
                ContentType.Application.Json,
                HttpStatusCode.PayloadTooLarge,
            )
            return null
        }
        val text = receiveText()
        if (text.toByteArray().size > MAX_BODY_BYTES) {
            respondText(
                errorJson("invalid_request_error", "Request body too large (max 2 MB)"),
                ContentType.Application.Json,
                HttpStatusCode.PayloadTooLarge,
            )
            return null
        }
        return text
    }

    fun start(config: Config, engineManager: LiteRtEngineManager) {
        if (_status.value is Status.Running || _status.value is Status.Starting) return
        _status.value = Status.Starting
        try {
            val host = if (config.bindAll) "0.0.0.0" else "127.0.0.1"
            val srv =
                embeddedServer(CIO, host = host, port = config.port) {
                    routing {
                        // Global CORS preflight handler
                        options("{...}") {
                            call.response.headers.append("Access-Control-Allow-Origin", "*")
                            call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
                            call.response.headers.append("Access-Control-Allow-Headers", "*")
                            call.respondText("")
                        }
                        get("/health") {
                            call.response.headers.append("Access-Control-Allow-Origin", "*")
                            call.respondText("ok")
                        }
                        get("/v1/models") {
                            call.response.headers.append("Access-Control-Allow-Origin", "*")
                            call.respondText(modelsJson(config.modelId()), ContentType.Application.Json)
                        }
                        get("/v1/models/{model}") {
                            call.response.headers.append("Access-Control-Allow-Origin", "*")
                            val requestedModel = call.parameters["model"] ?: config.modelId()
                            call.respondText(modelDetailJson(requestedModel), ContentType.Application.Json)
                        }
                        post("/v1/chat/completions") {
                            call.response.headers.append("Access-Control-Allow-Origin", "*")
                            call.handleChatCompletion(config, engineManager)
                        }
                        post("/v1/completions") {
                            call.response.headers.append("Access-Control-Allow-Origin", "*")
                            call.handleRawCompletion(config, engineManager)
                        }
                    }
                }
            server = srv
            srv.start(wait = false)
            _status.value = Status.Running(config.port, config.bindAll)
            KLog.d("OpenAiServer", "Listening on http://$host:${config.port}/v1")
        } catch (e: Exception) {
            KLog.e("OpenAiServer", "Failed to start on port ${config.port}", e)
            _status.value = Status.Error(e.message ?: e.javaClass.simpleName)
            server = null
        }
    }

    fun stop() {
        try {
            server?.stop(500, 1500)
        } catch (_: Exception) {
        }
        server = null
        _status.value = Status.Stopped
        KLog.d("OpenAiServer", "Server stopped")
    }

    private fun Config.modelId(): String =
        modelPath.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".litertlm").ifBlank { "litechat" }

    private suspend fun ApplicationCall.handleChatCompletion(
        config: Config,
        engineManager: LiteRtEngineManager,
    ) {
        // Optional bearer auth
        val token = config.authToken
        if (token.isNotBlank()) {
            val provided = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            if (provided != token) {
                respondText(
                    errorJson("invalid_api_key", "Invalid or missing API key"),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return
            }
        }

        // Rate limiting (keyed by the real connection IP, not a client header)
        val clientHost = remoteIp()
        if (!checkRateLimit(clientHost)) {
            respondText(
                errorJson("rate_limit_exceeded", "Too many requests. Please try again later."),
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
            return
        }

        val bodyText = receiveTextLimited() ?: return
        val request =
            try {
                gson.fromJson(bodyText, ChatCompletionRequest::class.java)
            } catch (_: Exception) {
                null
            }
        if (request == null || request.messages.isNullOrEmpty()) {
            respondText(
                errorJson("invalid_request_error", "messages[] is required"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val startTime = System.currentTimeMillis()
        val isStream = request.stream == true

        val modelId = config.modelId()
        val prompt = PromptBuilder.build(request.messages, config.systemPrompt, modelPath = config.modelPath)
        val params =
            LiteRtEngineManager.EngineParams(
                modelPath = config.modelPath,
                temperature = request.temperature ?: config.temperature,
                topK = request.top_k ?: config.topK,
                topP = request.top_p ?: config.topP,
                maxTokens = request.max_tokens ?: config.maxTokens,
                backend = config.backend,
                systemPrompt = config.systemPrompt,
            )

        _requestCount.update { it + 1 }

        if (isStream) {
            response.headers.append("Cache-Control", "no-cache")
            respondTextWriter(contentType = ContentType.Text.EventStream) {
                val id = SseProtocol.newId()
                write(SseProtocol.roleChunk(id, modelId))
                write("\n\n")
                flush()
                try {
                    trackActive {
                        engineManager.streamResponse(prompt, params).collect { delta ->
                            write(SseProtocol.chunk(id, modelId, delta))
                            write("\n\n")
                            flush()
                        }
                    }
                    write(SseProtocol.chunk(id, modelId, null, finishReason = "stop"))
                    write("\n\n")
                    write(SseProtocol.done())
                    write("\n\n")
                    flush()
                    logRequest(
                        ServerRequestLog(
                            id = id,
                            timestamp = startTime,
                            method = "POST",
                            path = "/v1/chat/completions",
                            clientIp = clientHost,
                            status = 200,
                            durationMs = System.currentTimeMillis() - startTime,
                            stream = true,
                        ),
                    )
                } catch (e: Exception) {
                    KLog.e("OpenAiServer", "Streaming failed", e)
                    try {
                        // Signal the truncation instead of letting the client
                        // mistake a bare [DONE] for a clean finish.
                        write(SseProtocol.errorChunk(e.message ?: "Generation failed"))
                        write("\n\n")
                        write(SseProtocol.done())
                        write("\n\n")
                        flush()
                    } catch (_: Exception) {
                    }
                    logRequest(
                        ServerRequestLog(
                            id = id,
                            timestamp = startTime,
                            method = "POST",
                            path = "/v1/chat/completions",
                            clientIp = clientHost,
                            status = 500,
                            durationMs = System.currentTimeMillis() - startTime,
                            stream = true,
                        ),
                    )
                }
            }
        } else {
            val reqId = SseProtocol.newId()
            try {
                val content = trackActive { engineManager.generateResponse(prompt, params) }
                respondText(
                    SseProtocol.completion(reqId, modelId, content).toString(),
                    ContentType.Application.Json,
                )
                logRequest(
                    ServerRequestLog(
                        id = reqId,
                        timestamp = startTime,
                        method = "POST",
                        path = "/v1/chat/completions",
                        clientIp = clientHost,
                        status = 200,
                        durationMs = System.currentTimeMillis() - startTime,
                        stream = false,
                    ),
                )
            } catch (e: Exception) {
                KLog.e("OpenAiServer", "Generation failed", e)
                respondText(
                    errorJson("generation_failed", e.message ?: "Generation failed"),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                logRequest(
                    ServerRequestLog(
                        id = reqId,
                        timestamp = startTime,
                        method = "POST",
                        path = "/v1/chat/completions",
                        clientIp = clientHost,
                        status = 500,
                        durationMs = System.currentTimeMillis() - startTime,
                        stream = false,
                    ),
                )
            }
        }
    }

    private suspend fun ApplicationCall.handleRawCompletion(
        config: Config,
        engineManager: LiteRtEngineManager,
    ) {
        val token = config.authToken
        if (token.isNotBlank()) {
            val provided = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            if (provided != token) {
                respondText(
                    errorJson("invalid_api_key", "Invalid or missing API key"),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                return
            }
        }

        // Rate limiting (keyed by the real connection IP, not a client header)
        val clientHost = remoteIp()
        if (!checkRateLimit(clientHost)) {
            respondText(
                errorJson("rate_limit_exceeded", "Too many requests. Please try again later."),
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
            return
        }

        val bodyText = receiveTextLimited() ?: return
        val request =
            try {
                gson.fromJson(bodyText, RawCompletionRequest::class.java)
            } catch (_: Exception) {
                null
            }

        val prompt = request?.prompt?.ifBlank { null } ?: run {
            respondText(
                errorJson("invalid_request_error", "prompt is required"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val startTime = System.currentTimeMillis()
        val isStream = request.stream == true
        val modelId = config.modelId()

        // Optional per-request system prompt: prepended to the raw prompt so it
        // actually takes effect (mirrors the chat endpoint's behavior).
        val requestSystem = request.system?.takeIf { it.isNotBlank() }
        val effectivePrompt = if (requestSystem != null) "$requestSystem\n\n$prompt" else prompt

        val params =
            LiteRtEngineManager.EngineParams(
                modelPath = config.modelPath,
                temperature = request.temperature ?: config.temperature,
                topK = request.top_k ?: config.topK,
                topP = request.top_p ?: config.topP,
                maxTokens = request.max_tokens ?: config.maxTokens,
                backend = config.backend,
                systemPrompt = request.system ?: config.systemPrompt,
            )

        _requestCount.update { it + 1 }

        if (isStream) {
            response.headers.append("Cache-Control", "no-cache")
            respondTextWriter(contentType = ContentType.Text.EventStream) {
                val id = SseProtocol.newCompletionId()
                try {
                    trackActive {
                        engineManager.streamResponse(effectivePrompt, params).collect { delta ->
                            write(SseProtocol.textChunk(id, modelId, delta))
                            write("\n\n")
                            flush()
                        }
                    }
                    write(SseProtocol.textChunk(id, modelId, null, finishReason = "stop"))
                    write("\n\n")
                    write(SseProtocol.done())
                    write("\n\n")
                    flush()
                    logRequest(
                        ServerRequestLog(
                            id = id,
                            timestamp = startTime,
                            method = "POST",
                            path = "/v1/completions",
                            clientIp = clientHost,
                            status = 200,
                            durationMs = System.currentTimeMillis() - startTime,
                            stream = true,
                        ),
                    )
                } catch (e: Exception) {
                    KLog.e("OpenAiServer", "Raw streaming failed", e)
                    try {
                        // Signal the truncation instead of closing the stream silently.
                        write(SseProtocol.errorChunk(e.message ?: "Generation failed"))
                        write("\n\n")
                        write(SseProtocol.done())
                        write("\n\n")
                        flush()
                    } catch (_: Exception) {
                    }
                    logRequest(
                        ServerRequestLog(
                            id = id,
                            timestamp = startTime,
                            method = "POST",
                            path = "/v1/completions",
                            clientIp = clientHost,
                            status = 500,
                            durationMs = System.currentTimeMillis() - startTime,
                            stream = true,
                        ),
                    )
                }
            }
        } else {
            val reqId = SseProtocol.newCompletionId()
            try {
                val content = trackActive { engineManager.generateResponse(effectivePrompt, params) }
                respondText(
                    SseProtocol.textCompletion(reqId, modelId, content).toString(),
                    ContentType.Application.Json,
                )
                logRequest(
                    ServerRequestLog(
                        id = reqId,
                        timestamp = startTime,
                        method = "POST",
                        path = "/v1/completions",
                        clientIp = clientHost,
                        status = 200,
                        durationMs = System.currentTimeMillis() - startTime,
                        stream = false,
                    ),
                )
            } catch (e: Exception) {
                KLog.e("OpenAiServer", "Raw generation failed", e)
                respondText(
                    errorJson("generation_failed", e.message ?: "Generation failed"),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                logRequest(
                    ServerRequestLog(
                        id = reqId,
                        timestamp = startTime,
                        method = "POST",
                        path = "/v1/completions",
                        clientIp = clientHost,
                        status = 500,
                        durationMs = System.currentTimeMillis() - startTime,
                        stream = false,
                    ),
                )
            }
        }
    }

    private fun modelDetailJson(modelId: String): String =
        JsonObject()
            .apply {
                addProperty("id", modelId)
                addProperty("object", "model")
                addProperty("owned_by", "localgpt")
                addProperty("permission", "public")
            }.toString()

    private fun modelsJson(modelId: String): String =
        JsonObject()
            .apply {
                addProperty("object", "list")
                add(
                    "data",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("id", modelId)
                                addProperty("object", "model")
                                addProperty("owned_by", "localgpt")
                            },
                        )
                    },
                )
            }.toString()

    private fun errorJson(
        type: String,
        message: String,
    ): String =
        JsonObject()
            .apply {
                add(
                    "error",
                    JsonObject().apply {
                        addProperty("message", message)
                        addProperty("type", type)
                    },
                )
            }.toString()
}

/** Gson-mapped OpenAI chat completion request. */
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<PromptBuilder.ChatMessage>? = null,
    val stream: Boolean? = false,
    val temperature: Float? = null,
    val top_k: Int? = null,
    val top_p: Float? = null,
    val max_tokens: Int? = null,
)

/** Gson-mapped raw completion request (/v1/completions). */
data class RawCompletionRequest(
    val model: String? = null,
    val prompt: String? = null,
    val stream: Boolean? = false,
    val temperature: Float? = null,
    val top_k: Int? = null,
    val top_p: Float? = null,
    val max_tokens: Int? = null,
    /** Optional system prompt; prepended to [prompt] when present. */
    val system: String? = null,
)
