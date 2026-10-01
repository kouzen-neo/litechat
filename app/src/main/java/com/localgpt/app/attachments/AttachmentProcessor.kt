package com.localgpt.app.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.localgpt.app.data.ChatAttachment
import com.localgpt.app.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A not-yet-processed attachment picked by the user.
 */
data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mime: String?,
    val isImage: Boolean,
)

/**
 * A persisted attachment ready to send, plus any text extracted from it
 * (documents) that should be injected into the prompt.
 */
data class PreparedAttachment(
    val attachment: ChatAttachment,
    val extractedText: String?,
)

/**
 * Turns user-picked URIs into app-private files:
 * - Images are downscaled to JPEG (max 1024px) for vision.
 * - Text-like files (.txt/.md/.json/.csv/.log/code) are read as text (capped).
 * - PDFs are rendered to page images (capped) so vision models can read them.
 * - Anything else is copied verbatim with a "preview unsupported" note.
 *
 * All heavy work runs on Dispatchers.IO.
 */
object AttachmentProcessor {
    const val MAX_IMAGE_DIM = 1024
    const val MAX_TEXT_CHARS_PER_FILE = 40_000
    const val MAX_TOTAL_TEXT_CHARS = 100_000
    const val MAX_PDF_PAGES = 5
    const val PDF_RENDER_WIDTH = 1024

    private val TEXT_EXTENSIONS =
        setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "log",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
            "html", "htm", "xml", "css", "c", "h", "cpp", "rs",
            "go", "sh", "yaml", "yml", "toml", "ini", "cfg", "sql",
        )

    fun resolveDisplayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        }
    }

    fun buildPending(context: Context, uri: Uri, forceImage: Boolean = false): PendingAttachment {
        val mime = context.contentResolver.getType(uri)
        val name = resolveDisplayName(context, uri)
        val isImage = forceImage || (mime?.startsWith("image/") == true)
        return PendingAttachment(uri, name, mime, isImage)
    }

    suspend fun prepare(context: Context, pending: List<PendingAttachment>): List<PreparedAttachment> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<PreparedAttachment>()
            var textBudget = MAX_TOTAL_TEXT_CHARS
            for (p in pending) {
                try {
                    when {
                        p.isImage -> prepareImage(context, p)?.let { out += it }
                        p.mime == "application/pdf" || p.name.endsWith(".pdf", ignoreCase = true) -> {
                            out += preparePdf(context, p)
                        }
                        isTextLike(p) -> {
                            val text = readTextCapped(context, p.uri, MAX_TEXT_CHARS_PER_FILE)
                            if (text != null) {
                                val file = copyToFilesDir(context, p)
                                val allowed = text.take(textBudget)
                                textBudget -= allowed.length
                                out +=
                                    PreparedAttachment(
                                        ChatAttachment("file", file.absolutePath, p.name, p.mime),
                                        allowed.ifBlank { null },
                                    )
                            }
                        }
                        else -> {
                            val file = copyToFilesDir(context, p)
                            out +=
                                PreparedAttachment(
                                    ChatAttachment("file", file.absolutePath, p.name, p.mime),
                                    null,
                                )
                        }
                    }
                } catch (t: Throwable) {
                    KLog.e("Attachments", "Failed to prepare ${p.name}", t)
                }
            }
            out
        }

    private fun isTextLike(p: PendingAttachment): Boolean {
        if (p.mime?.startsWith("text/") == true) return true
        val ext = p.name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    private fun imagesDir(context: Context): File =
        File(context.filesDir, "chat_images").apply { if (!exists()) mkdirs() }

    private fun filesDir(context: Context): File =
        File(context.filesDir, "chat_files").apply { if (!exists()) mkdirs() }

    private fun prepareImage(context: Context, p: PendingAttachment): PreparedAttachment? {
        val dest = File(imagesDir(context), "img_${System.currentTimeMillis()}_${p.name.hashCode().toString(16)}.jpg")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(p.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        var sampleSize = 1
        val maxOriginal = maxOf(options.outWidth, options.outHeight)
        while ((maxOriginal / (sampleSize * 2)) >= MAX_IMAGE_DIM) sampleSize *= 2
        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        val bitmap =
            context.contentResolver.openInputStream(p.uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null
        val finalBitmap =
            if (bitmap.width > MAX_IMAGE_DIM || bitmap.height > MAX_IMAGE_DIM) {
                val ratio = MAX_IMAGE_DIM.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled =
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ratio).toInt().coerceAtLeast(1),
                        (bitmap.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                if (scaled != bitmap) bitmap.recycle()
                scaled
            } else {
                bitmap
            }
        return try {
            dest.outputStream().use { finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            finalBitmap.recycle()
            PreparedAttachment(ChatAttachment("image", dest.absolutePath, p.name, "image/jpeg"), null)
        } catch (t: Throwable) {
            KLog.e("Attachments", "Failed to persist image ${p.name}", t)
            finalBitmap.recycle()
            null
        }
    }

    private fun preparePdf(context: Context, p: PendingAttachment): List<PreparedAttachment> {
        val out = mutableListOf<PreparedAttachment>()
        try {
            val pfd = context.contentResolver.openFileDescriptor(p.uri, "r") ?: return out
            pfd.use {
                val renderer = PdfRenderer(it)
                try {
                    val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                    for (i in 0 until pages) {
                        renderer.openPage(i).use { page ->
                            val ratio = PDF_RENDER_WIDTH.toFloat() / page.width
                            val h = (page.height * ratio).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(PDF_RENDER_WIDTH, h, Bitmap.Config.RGB_565)
                            // White background: PDFs can have transparency.
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val dest =
                                File(
                                    imagesDir(context),
                                    "pdf_${System.currentTimeMillis()}_p${i + 1}.jpg",
                                )
                            dest.outputStream().use { out2 ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out2)
                            }
                            bitmap.recycle()
                            out +=
                                PreparedAttachment(
                                    ChatAttachment("image", dest.absolutePath, "${p.name} (p${i + 1})", "image/jpeg"),
                                    null,
                                )
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
        } catch (t: Throwable) {
            KLog.e("Attachments", "Failed to render PDF ${p.name}", t)
        }
        return out
    }

    private fun readTextCapped(context: Context, uri: Uri, maxChars: Int): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                // Cheap charset handling: try UTF-8, fall back to ISO-8859-1.
                val text =
                    try {
                        String(bytes, Charsets.UTF_8)
                    } catch (_: Exception) {
                        String(bytes, Charsets.ISO_8859_1)
                    }
                // Strip NULs/control garbage that sometimes appears in mislabeled files.
                text.replace("\u0000", "").take(maxChars)
            }
        } catch (t: Throwable) {
            KLog.e("Attachments", "Failed to read text file", t)
            null
        }
    }

    private fun copyToFilesDir(context: Context, p: PendingAttachment): File {
        val safeName = p.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }
        val dest = File(filesDir(context), "${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(p.uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest
    }
}
