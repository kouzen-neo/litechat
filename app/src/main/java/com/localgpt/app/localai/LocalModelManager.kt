package com.localgpt.app.localai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Model file representation for installed/downloaded models on device storage.
 */
data class InstalledModel(
    val id: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val sizeDisplay: String,
    val isPreset: Boolean,
    val presetModel: LocalAiModel? = null,
    val absolutePath: String,
)

/**
 * Storage and file management for On-Device LiteRT LLM models.
 * Operates in app-private external files directory (context.getExternalFilesDir("models"))
 * which is Scoped Storage safe and requires no dangerous runtime permissions.
 * (Ported from KZKT.)
 */
class LocalModelManager(
    private val context: Context,
) {
    val modelsDir: File
        get() {
            val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getModelFile(model: LocalAiModel): File = File(modelsDir, model.fileName)

    fun isModelDownloaded(model: LocalAiModel): Boolean {
        val file = getModelFile(model)
        return file.exists() && file.length() >= 50_000_000L
    }

    fun getDownloadedBytes(model: LocalAiModel): Long {
        val file = getModelFile(model)
        return if (file.exists()) file.length() else 0L
    }

    fun deleteModel(model: LocalAiModel): Boolean = deleteFile(model.fileName)

    fun deleteFile(fileName: String): Boolean {
        val file = File(modelsDir, fileName)
        val tmp = File(modelsDir, "$fileName.tmp")
        if (tmp.exists()) tmp.delete()
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Scans the models directory and returns all downloaded model files (.litertlm, .task, .bin).
     * Automatically maps recognized filenames to PresetModels if available.
     */
    fun getInstalledModels(): List<InstalledModel> =
        try {
            val files =
                modelsDir.listFiles { f ->
                    f.isFile &&
                        !f.name.endsWith(".tmp", ignoreCase = true) &&
                        f.length() > 0 &&
                        (
                            f.name.endsWith(".litertlm", ignoreCase = true) ||
                                f.name.endsWith(".task", ignoreCase = true) ||
                                f.name.endsWith(".bin", ignoreCase = true)
                        )
                } ?: emptyArray()

            files
                .map { file ->
                    val matchingPreset = LocalAiCatalog.PRESET_MODELS.find { it.fileName.equals(file.name, ignoreCase = true) }
                    val sizeBytes = file.length()
                    val sizeMb = sizeBytes.toFloat() / (1024 * 1024)
                    val sizeDisplay =
                        if (sizeMb >= 1024) {
                            String.format(Locale.ROOT, "%.2f GB", sizeMb / 1024)
                        } else {
                            String.format(Locale.ROOT, "%.0f MB", sizeMb)
                        }

                    val cleanName =
                        if (matchingPreset != null) {
                            matchingPreset.name
                        } else {
                            file.name
                                .removeSuffix(".litertlm")
                                .removeSuffix(".task")
                                .removeSuffix(".bin")
                                .replace("_", " ")
                                .replace("-", " ")
                        }

                    InstalledModel(
                        id = matchingPreset?.id ?: file.name,
                        fileName = file.name,
                        displayName = cleanName,
                        sizeBytes = sizeBytes,
                        sizeDisplay = sizeDisplay,
                        isPreset = matchingPreset != null,
                        presetModel = matchingPreset,
                        absolutePath = file.absolutePath,
                    )
                }.sortedByDescending { it.sizeBytes }
        } catch (_: Exception) {
            emptyList()
        }

    fun getFreeDiskSpace(): Long =
        try {
            modelsDir.freeSpace
        } catch (_: Exception) {
            0L
        }

    /**
     * Imports a user-selected .litertlm, .task, or .bin model file from a Content Uri.
     * Returns the absolute path of the imported model file, or null on failure.
     */
    suspend fun importCustomModel(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                var fileName = "custom_model.litertlm"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: "custom_model.litertlm"
                    }
                }

                val targetFile = File(modelsDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetFile.exists() && targetFile.length() > 0) {
                    targetFile.absolutePath
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
}
