package com.localgpt.app.localai

/**
 * Metadata definition for LiteRT-LM on-device language models (.litertlm / .task).
 */
data class LocalAiModel(
    val id: String,
    val name: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sizeDisplay: String,
    val description: String,
    val supportedLanguages: String,
    val recommendedBackend: String = "GPU",
    val recommendedTemp: Float = 0.2f,
    val recommendedTopK: Int = 40,
    val recommendedMaxTokens: Int = 512,
)

/**
 * Curated catalog of tested and verified Google AI Edge LiteRT-LM models
 * (ported from KZKT's catalog).
 */
object LocalAiCatalog {
    private const val HF_BASE = "https://huggingface.co/litert-community"

    val PRESET_MODELS: List<LocalAiModel> =
        listOf(
            LocalAiModel(
                id = "gemma-4-e2b-it",
                name = "Gemma 4 E2B IT",
                fileName = "gemma-4-E2B-it.litertlm",
                downloadUrl = "$HF_BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                sizeBytes = 2_588_147_712L,
                sizeDisplay = "2.41 GB",
                description = "Google's flagship on-device model with strong multilingual and reasoning quality.",
                supportedLanguages = "English, Indonesian, Japanese, Multilingual",
                recommendedBackend = "GPU",
                recommendedTemp = 0.2f,
                recommendedTopK = 40,
                recommendedMaxTokens = 512,
            ),
            LocalAiModel(
                id = "gemma-4-e4b-it",
                name = "Gemma 4 E4B IT",
                fileName = "gemma-4-E4B-it.litertlm",
                downloadUrl = "$HF_BASE/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                sizeBytes = 3_659_530_240L,
                sizeDisplay = "3.41 GB",
                description = "High-capacity 4B model with deep knowledge, complex reasoning, and coding capabilities.",
                supportedLanguages = "English, Indonesian, Multilingual",
                recommendedBackend = "GPU",
                recommendedTemp = 0.2f,
                recommendedTopK = 40,
                recommendedMaxTokens = 512,
            ),
            LocalAiModel(
                id = "gemma-3-1b-it",
                name = "Gemma 3 1B IT",
                fileName = "gemma3-1b-it-int4.litertlm",
                downloadUrl = "https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert/resolve/main/gemma3-1b-it-int4.litertlm",
                sizeBytes = 584_417_280L,
                sizeDisplay = "557 MB",
                description = "Ultra-compact Google Gemma 3 1B model optimized with int4 quantization for fast mobile chat.",
                supportedLanguages = "English, Indonesian, Multilingual",
                recommendedBackend = "GPU",
                recommendedTemp = 0.2f,
                recommendedTopK = 40,
                recommendedMaxTokens = 512,
            ),
            LocalAiModel(
                id = "qwen3-1.7b",
                name = "Qwen 3 1.7B Instruct",
                fileName = "Qwen3_1.7B.litertlm",
                downloadUrl = "$HF_BASE/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm",
                sizeBytes = 2_056_729_520L,
                sizeDisplay = "1.92 GB",
                description = "Official Alibaba Qwen 3 1.7B model with next-generation reasoning and instruction quality.",
                supportedLanguages = "English, Chinese, Indonesian, Multilingual",
                recommendedBackend = "GPU",
                recommendedTemp = 0.2f,
                recommendedTopK = 40,
                recommendedMaxTokens = 512,
            ),
            LocalAiModel(
                id = "qwen3-0.6b",
                name = "Qwen 3 0.6B Instruct",
                fileName = "Qwen3-0.6B.litertlm",
                downloadUrl = "$HF_BASE/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                sizeBytes = 614_236_160L,
                sizeDisplay = "585 MB",
                description = "Next-gen ultra lightweight LiteRT model with rapid token generation and low RAM footprint.",
                supportedLanguages = "English, Chinese, Multilingual",
                recommendedBackend = "GPU",
                recommendedTemp = 0.2f,
                recommendedTopK = 40,
                recommendedMaxTokens = 512,
            ),
        )

    fun getModelById(id: String): LocalAiModel? = PRESET_MODELS.find { it.id == id }
}
