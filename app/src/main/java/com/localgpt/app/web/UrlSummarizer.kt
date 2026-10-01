package com.localgpt.app.web

import android.content.Context
import com.localgpt.app.util.KLog
import com.localgpt.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.regex.Pattern

/**
 * Fetches a web page and extracts readable text for summarization.
 * Strategy: Jina AI Reader first (good article extraction), then direct
 * HTTP fetch with HTML stripping as fallback.
 */
object UrlSummarizer {
    data class PageContent(
        val title: String,
        val text: String,
        val url: String,
    )

    const val MAX_CHARS = 24_000

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /** Normalizes user input into a fetchable https URL, or null if invalid. */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme =
            when {
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
        return try {
            val uri = android.net.Uri.parse(withScheme)
            val host = uri.host?.takeIf { it.contains('.') } ?: return null
            if (host.equals("localhost", ignoreCase = true)) return null
            withScheme
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchReadableText(context: Context, rawUrl: String): PageContent? =
        withContext(Dispatchers.IO) {
            val url = normalizeUrl(rawUrl) ?: return@withContext null
            val stripper = WebSearchManager.getInstance(context)
            // Attempt 1: Jina AI Reader
            try {
                val jinaUrl = "https://r.jina.ai/$url"
                val request =
                    Request.Builder()
                        .url(jinaUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/plain")
                        .build()
                val text: String =
                    NetworkUtils.HttpClient.searchClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }.orEmpty().take(MAX_CHARS)
                if (text.isNotBlank() && text.length > 200) {
                    return@withContext PageContent(title = url, text = text.trim(), url = url)
                }
            } catch (e: Exception) {
                KLog.e("UrlSummarizer", "Jina Reader failed for $url", e)
            }
            // Attempt 2: direct fetch + HTML strip
            try {
                val request =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()
                val rawHtml: String =
                    NetworkUtils.HttpClient.searchClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }.orEmpty()
                if (rawHtml.isNotBlank()) {
                    val titleMatcher =
                        Pattern.compile("<title>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE)
                            .matcher(rawHtml)
                    val title =
                        if (titleMatcher.find()) stripper.stripHtml(titleMatcher.group(1).orEmpty()) else url
                    val bodyText = stripper.stripHtml(rawHtml).take(MAX_CHARS).trim()
                    if (bodyText.length > 200) {
                        return@withContext PageContent(title.ifBlank { url }, bodyText, url)
                    }
                }
            } catch (e: Exception) {
                KLog.e("UrlSummarizer", "Direct fetch failed for $url", e)
            }
            null
        }
}
