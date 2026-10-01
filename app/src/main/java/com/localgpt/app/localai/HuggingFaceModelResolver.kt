package com.localgpt.app.localai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves Hugging Face repository URLs into direct downloadable LiteRT model (.litertlm / .task / .bin / .tflite) assets.
 * Handles single file URLs, repo URLs, branches, LFS size resolutions, and gated model authorization.
 */
object HuggingFaceModelResolver {
    const val USER_AGENT = "LiteChat-App/0.1.0 (Android; LiteRT-Downloader)"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private val gson = Gson()

    data class HfTreeItem(
        @SerializedName("type") val type: String? = null,
        @SerializedName("path") val path: String? = null,
        @SerializedName("size") val size: Long? = null,
        @SerializedName("lfs") val lfs: HfLfsInfo? = null,
    )

    data class HfLfsInfo(
        @SerializedName("size") val size: Long? = null,
        @SerializedName("oid") val oid: String? = null,
    )

    data class HfSibling(
        @SerializedName("rfilename") val rfilename: String,
        @SerializedName("size") val size: Long? = null,
    )

    data class HfModelApiResponse(
        @SerializedName("id") val id: String?,
        @SerializedName("siblings") val siblings: List<HfSibling>?,
    )

    data class ResolvedModelFile(
        val repoId: String,
        val fileName: String,
        val downloadUrl: String,
        val sizeBytes: Long = 0L,
    ) {
        val sizeDisplay: String
            get() {
                if (sizeBytes <= 0) return "Unknown size"
                val gb = sizeBytes.toDouble() / (1024 * 1024 * 1024)
                return if (gb >= 1.0) {
                    "%.2f GB".format(gb)
                } else {
                    val mb = sizeBytes.toDouble() / (1024 * 1024)
                    "%.1f MB".format(mb)
                }
            }
    }

    sealed class ResolveResult {
        data class SingleFile(
            val file: ResolvedModelFile,
        ) : ResolveResult()

        data class MultipleFiles(
            val repoId: String,
            val files: List<ResolvedModelFile>,
        ) : ResolveResult()

        data class Error(
            val message: String,
        ) : ResolveResult()
    }

    /**
     * Resolves a Hugging Face model card URL, repo ID, or direct file URL into downloadable files.
     */
    suspend fun resolve(
        input: String,
        hfToken: String = "",
    ): ResolveResult =
        withContext(Dispatchers.IO) {
            val raw = input.trim()
            if (raw.isBlank()) {
                return@withContext ResolveResult.Error("URL cannot be empty.")
            }

            val isHuggingFace =
                raw.contains("huggingface.co", ignoreCase = true) ||
                    raw.contains("hf.co", ignoreCase = true) ||
                    raw.matches(Regex("^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$"))

            // Direct non-HuggingFace file URL (e.g. https://example.com/model.litertlm or GitHub release)
            if (!isHuggingFace) {
                // B11: only https survives; the downloader additionally rejects
                // non-public IPs on every redirect hop.
                if (!raw.startsWith("https://", ignoreCase = true)) {
                    return@withContext ResolveResult.Error("Only HTTPS download URLs are allowed.")
                }
                val fileName = raw.substringAfterLast("/").substringBefore("?").substringBefore("#").trim()
                return@withContext if (fileName.isNotBlank() && isSupportedModelExtension(fileName)) {
                    ResolveResult.SingleFile(
                        ResolvedModelFile(
                            repoId = "custom",
                            fileName = fileName,
                            downloadUrl = raw,
                            sizeBytes = 0L,
                        ),
                    )
                } else {
                    ResolveResult.Error("URL does not point to a supported model file (.litertlm, .task, .bin, .tflite).")
                }
            }

            // Extract repoId and optional target filename from Hugging Face URL
            var cleanUrl =
                raw
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .trim()

            // Remove host prefix
            cleanUrl = cleanUrl
                .removePrefix("www.huggingface.co/")
                .removePrefix("huggingface.co/")
                .removePrefix("www.hf.co/")
                .removePrefix("hf.co/")
                .removePrefix("/")

            val segments = cleanUrl.split("/").filter { it.isNotBlank() }

            if (segments.size < 2) {
                return@withContext ResolveResult.Error("Invalid Hugging Face model format. Expected: 'owner/model-name'.")
            }

            val owner = segments[0]
            val repo = segments[1]
            val repoId = "$owner/$repo"

            // Check if specific file is targeted (e.g. /blob/main/file.litertlm or /resolve/main/file.litertlm)
            if (segments.size >= 4 && (segments[2].equals("blob", ignoreCase = true) || segments[2].equals("resolve", ignoreCase = true) || segments[2].equals("raw", ignoreCase = true))) {
                val branch = segments[3]
                val specificFileWithParams = segments.drop(4).joinToString("/")
                val specificFile = specificFileWithParams.substringBefore("?").substringBefore("#").trim()

                if (specificFile.isNotBlank() && isSupportedModelExtension(specificFile)) {
                    val downloadUrl = "https://huggingface.co/$repoId/resolve/$branch/$specificFile?download=true"
                    val fileName = specificFile.substringAfterLast("/")
                    return@withContext ResolveResult.SingleFile(
                        ResolvedModelFile(
                            repoId = repoId,
                            fileName = fileName,
                            downloadUrl = downloadUrl,
                            sizeBytes = 0L,
                        ),
                    )
                }
            }

            val branch = if (segments.size >= 4 && segments[2].equals("tree", ignoreCase = true)) segments[3] else "main"

            // 1. Attempt Hugging Face Tree API to get accurate file sizes & LFS info
            val treeUrl = "https://huggingface.co/api/models/$repoId/tree/$branch"
            val treeReqBuilder =
                Request.Builder()
                    .url(treeUrl)
                    .header("User-Agent", USER_AGENT)

            if (hfToken.isNotBlank()) {
                treeReqBuilder.header("Authorization", "Bearer $hfToken")
            }

            var treeFiles: List<ResolvedModelFile>? = null
            try {
                client.newCall(treeReqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string() ?: ""
                        val treeType = object : TypeToken<List<HfTreeItem>>() {}.type
                        val treeData: List<HfTreeItem>? = gson.fromJson(bodyString, treeType)
                        if (!treeData.isNullOrEmpty()) {
                            treeFiles =
                                treeData
                                    .filter { it.path != null && isSupportedModelExtension(it.path) }
                                    .map { item ->
                                        val p = item.path!!
                                        val actualSize = item.lfs?.size ?: item.size ?: 0L
                                        ResolvedModelFile(
                                            repoId = repoId,
                                            fileName = p.substringAfterLast("/"),
                                            downloadUrl = "https://huggingface.co/$repoId/resolve/$branch/$p?download=true",
                                            sizeBytes = actualSize,
                                        )
                                    }
                        }
                    } else if (response.code in listOf(401, 403)) {
                        return@withContext ResolveResult.Error(
                            "Access restricted. If this is a gated model, please enter your Hugging Face Token in Parameters / Settings.",
                        )
                    } else if (response.code == 404) {
                        // May be invalid branch or model not found, let fallback attempt /api/models/$repoId
                    }
                }
            } catch (e: Exception) {
                // Ignore and try fallback
            }

            if (!treeFiles.isNullOrEmpty()) {
                val list = treeFiles!!
                return@withContext if (list.size == 1) {
                    ResolveResult.SingleFile(list.first())
                } else {
                    ResolveResult.MultipleFiles(repoId, list)
                }
            }

            // 2. Fallback to basic Hugging Face Model API
            val apiUrl = "https://huggingface.co/api/models/$repoId"
            val reqBuilder =
                Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", USER_AGENT)

            if (hfToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $hfToken")
            }

            try {
                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext when (response.code) {
                            401, 403 ->
                                ResolveResult.Error(
                                    "Access restricted. If this is a gated model, please enter your Hugging Face Token in Parameters / Settings.",
                                )
                            404 -> ResolveResult.Error("Hugging Face model '$repoId' not found.")
                            else -> ResolveResult.Error("Hugging Face API returned error (HTTP ${response.code}).")
                        }
                    }

                    val bodyString = response.body?.string() ?: ""
                    val apiData = gson.fromJson(bodyString, HfModelApiResponse::class.java)
                    val modelFiles =
                        apiData.siblings
                            ?.filter { isSupportedModelExtension(it.rfilename) }
                            ?.map { sibling ->
                                ResolvedModelFile(
                                    repoId = repoId,
                                    fileName = sibling.rfilename.substringAfterLast("/"),
                                    downloadUrl = "https://huggingface.co/$repoId/resolve/main/${sibling.rfilename}?download=true",
                                    sizeBytes = sibling.size ?: 0L,
                                )
                            } ?: emptyList()

                    if (modelFiles.isEmpty()) {
                        return@withContext ResolveResult.Error(
                            "No LiteRT model files (.litertlm, .task, .bin, .tflite) found in repository '$repoId'.",
                        )
                    }

                    if (modelFiles.size == 1) {
                        ResolveResult.SingleFile(modelFiles.first())
                    } else {
                        ResolveResult.MultipleFiles(repoId, modelFiles)
                    }
                }
            } catch (e: Exception) {
                ResolveResult.Error("Failed to connect to Hugging Face: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    fun isSupportedModelExtension(filename: String): Boolean {
        val clean = filename.substringBefore("?").substringBefore("#").trim().lowercase()
        return clean.endsWith(".litertlm") ||
            clean.endsWith(".task") ||
            clean.endsWith(".bin") ||
            clean.endsWith(".tflite")
    }
}
