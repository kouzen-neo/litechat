package com.localgpt.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes text artifacts into the public Downloads/LiteChat folder.
 *
 * - Android 10+ (Q): MediaStore.Downloads with RELATIVE_PATH — no storage permission required.
 * - Android 8-9: direct write to the public Downloads dir (requires legacy WRITE_EXTERNAL_STORAGE;
 *   on failure callers surface an error toast).
 */
object FileSaver {
    const val SUBDIR = "LiteChat"

    private val EXT_MIME =
        mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "ts" to "text/typescript",
            "json" to "application/json",
            "xml" to "application/xml",
            "svg" to "image/svg+xml",
            "md" to "text/markdown",
            "csv" to "text/csv",
            "txt" to "text/plain",
            "py" to "text/x-python",
            "java" to "text/x-java-source",
            "kt" to "text/x-kotlin",
            "sh" to "application/x-sh",
            "sql" to "application/sql",
            "yaml" to "text/yaml",
            "yml" to "text/yaml",
        )

    private val KNOWN_EXT =
        setOf(
            "html", "htm", "css", "js", "mjs", "ts", "json", "xml", "svg", "md", "csv", "txt",
            "py", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php",
            "swift", "dart", "sh", "bash", "bat", "ps1", "sql", "yaml", "yml", "toml", "ini",
            "gradle", "properties", "log", "lua", "r", "scala", "pl",
        )

    fun mimeFor(fileName: String): String = EXT_MIME[extensionOf(fileName)] ?: "application/octet-stream"

    /** Suggests a file name from a fenced code block language label. */
    fun defaultFileName(language: String): String {
        val lang = language.trim().lowercase(Locale.ROOT)
        val ext = when {
            lang in KNOWN_EXT -> lang
            lang == "kotlin" -> "kt"
            lang == "python" || lang == "python3" -> "py"
            lang == "javascript" || lang == "node" -> "js"
            lang == "typescript" -> "ts"
            lang == "shell" || lang == "bash" || lang == "zsh" -> "sh"
            lang == "c++" || lang == "cpp" -> "cpp"
            lang == "c#" || lang == "csharp" -> "cs"
            lang.matches(Regex("[a-z0-9]{1,8}")) -> lang
            else -> "txt"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "litechat_$stamp.$ext"
    }

    /**
     * Saves [text] as [fileName] under Downloads/LiteChat.
     * Returns the stored display name, or null on failure.
     */
    suspend fun saveToDownloads(
        context: Context,
        fileName: String,
        text: String,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val safeName = sanitize(fileName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(context, safeName, text, relativeSubDir = SUBDIR)
                } else {
                    saveLegacy(safeName, text)
                }
            } catch (t: Throwable) {
                KLog.w("FileSaver", "Save failed: ${t.message}")
                null
            }
        }

    /**
     * Exports a multi-file project into Downloads/LiteChat/<folderName>/.
     * Returns the number of successfully written files.
     */
    suspend fun exportFolder(
        context: Context,
        folderName: String,
        files: Map<String, String>,
    ): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            try {
                val folder = sanitize(folderName).ifBlank { "project" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    files.forEach { (name, content) ->
                        val stored =
                            saveViaMediaStore(
                                context,
                                sanitize(name),
                                content,
                                relativeSubDir = "$SUBDIR/$folder",
                            )
                        if (stored != null) count++
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$SUBDIR/$folder")
                    if (!dir.exists() && !dir.mkdirs()) return@withContext 0
                    files.forEach { (name, content) ->
                        try {
                            File(dir, sanitize(name)).writeText(content)
                            count++
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                KLog.w("FileSaver", "Export folder failed: ${t.message}")
            }
            count
        }

    private fun saveViaMediaStore(
        context: Context,
        fileName: String,
        text: String,
        relativeSubDir: String = SUBDIR,
    ): String? {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relativeSubDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return fileName
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        fileName: String,
        text: String,
    ): String? {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        var target = File(dir, fileName)
        if (target.exists()) {
            val name = fileName.substringBeforeLast('.')
            val ext = extensionOf(fileName)
            var i = 1
            while (target.exists()) {
                target = File(dir, "$name-$i.$ext")
                i++
            }
        }
        target.writeText(text)
        return target.name
    }

    private fun sanitize(name: String): String =
        name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "litechat_file.txt" }

    private fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
