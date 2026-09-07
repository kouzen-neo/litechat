package com.localgpt.app.artifacts

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.localgpt.app.util.CodeArtifacts
import com.localgpt.app.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class Artifact(
    val id: String,
    val projectId: String,
    val fileName: String,
    val language: String,
    val charCount: Int,
    val version: Int = 1,
    val chatId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Internal store for code artifacts generated from assistant answers.
 * Metadata lives in artifacts.json; content in <id>.txt next to it.
 * Re-capturing the same project+file overwrites content and bumps version.
 */
class ArtifactStore private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: ArtifactStore? = null

        fun getInstance(context: Context): ArtifactStore =
            instance ?: synchronized(this) {
                instance ?: ArtifactStore(context.applicationContext).also { instance = it }
            }
    }

    private val gson = Gson()
    private val dir = File(context.filesDir, "artifacts").apply { if (!exists()) mkdirs() }
    private val metaFile get() = File(dir, "artifacts.json")

    private val _artifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val artifacts: StateFlow<List<Artifact>> = _artifacts.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        _artifacts.value =
            try {
                if (metaFile.exists()) {
                    gson.fromJson(metaFile.readText(), object : TypeToken<List<Artifact>>() {}.type)
                        ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (t: Throwable) {
                KLog.w("Artifacts", "Meta reload failed: ${t.message}")
                emptyList()
            }
    }

    private fun saveMeta(list: List<Artifact>) {
        try {
            metaFile.writeText(gson.toJson(list))
        } catch (_: Throwable) {
        }
    }

    /**
     * Captures generated project files. Existing entries with the same
     * projectId+fileName are overwritten with version bump.
     */
    suspend fun capture(
        projectId: String,
        files: List<CodeArtifacts.ProjectFile>,
        chatId: String? = null,
    ): Int =
        withContext(Dispatchers.IO) {
            if (files.isEmpty()) return@withContext 0
            try {
                val current = _artifacts.value.toMutableList()
                var written = 0
                files.forEach { f ->
                    val existingIdx =
                        current.indexOfFirst { it.projectId == projectId && it.fileName == f.fileName }
                    val now = System.currentTimeMillis()
                    val previous = if (existingIdx >= 0) current[existingIdx] else null
                    val artifact =
                        if (previous != null) {
                            Artifact(
                                id = previous.id,
                                projectId = projectId,
                                fileName = f.fileName,
                                language = f.language,
                                charCount = f.content.length,
                                version = previous.version + 1,
                                chatId = previous.chatId ?: chatId,
                                createdAt = previous.createdAt,
                                updatedAt = now,
                            ).also { current[existingIdx] = it }
                        } else {
                            Artifact(
                                id = UUID.randomUUID().toString(),
                                projectId = projectId,
                                fileName = f.fileName,
                                language = f.language,
                                charCount = f.content.length,
                                chatId = chatId,
                            ).also { current.add(it) }
                        }
                    File(dir, "${artifact.id}.txt").writeText(f.content)
                    written++
                }
                saveMeta(current.sortedBy { it.createdAt })
                reload()
                written
            } catch (t: Throwable) {
                KLog.w("Artifacts", "Capture failed: ${t.message}")
                0
            }
        }

    suspend fun readContent(artifact: Artifact): String? =
        withContext(Dispatchers.IO) {
            try {
                File(dir, "${artifact.id}.txt").readText()
            } catch (_: Throwable) {
                null
            }
        }

    suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            try {
                File(dir, "$id.txt").delete()
                saveMeta(_artifacts.value.filterNot { it.id == id })
                reload()
            } catch (_: Throwable) {
            }
        }

    suspend fun deleteProject(projectId: String) =
        withContext(Dispatchers.IO) {
            try {
                _artifacts.value.filter { it.projectId == projectId }.forEach {
                    File(dir, "${it.id}.txt").delete()
                }
                saveMeta(_artifacts.value.filterNot { it.projectId == projectId })
                reload()
            } catch (_: Throwable) {
            }
        }
}
