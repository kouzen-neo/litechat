package com.localgpt.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.localgpt.app.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatMessageEntry(
    val role: String, // "user" | "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val stats: String? = null, // e.g. "18.2 tok/s · 154 tokens · GPU"
    val variants: List<String> = emptyList(),
    val selectedVariant: Int = 0,
    val imagePath: String? = null,
    val id: String? = UUID.randomUUID().toString(), // stable node id for conversation branching
    val parentId: String? = null, // previous node in the tree; null = root child
    val sources: List<String>? = null, // RAG citation labels e.g. ["notes.txt #2"]
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var messages: List<ChatMessageEntry> = emptyList(), // flat tree nodes
    var branchActive: MutableMap<String, String>? = null, // parentKey ("root"|nodeId) -> active childId
    var summary: String? = null, // rolling compressed-memory summary (context compression)
    var summarizedUntilId: String? = null, // id of the last message folded into the summary
)

/**
 * File-based conversation persistence: one JSON file per conversation under
 * filesDir/chats/. Asynchronous file I/O on Dispatchers.IO.
 */
class ChatRepository(
    context: Context,
) {
    private val gson = Gson()
    private val dir: File =
        File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }

    private fun fileFor(id: String) = File(dir, "$id.json")

    /**
     * Writes [text] to [file] atomically: content goes to a temp sibling file
     * first, then is renamed over the target. A crash mid-write can never leave
     * a half-written conversation file behind.
     *
     * @return true on success, false on failure (also logged via KLog).
     */
    private fun writeAtomically(
        file: File,
        text: String,
    ): Boolean =
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            if (tmp.exists()) tmp.delete()
            tmp.writeText(text)
            if (tmp.renameTo(file)) {
                true
            } else {
                // renameTo can fail across filesystems; fall back to copy+delete.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
                true
            }
        } catch (e: Exception) {
            KLog.e("ChatRepository", "Atomic write failed for ${file.name}", e)
            false
        }

    suspend fun listConversations(): List<Conversation> =
        withContext(Dispatchers.IO) {
            try {
                dir
                    .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                    ?.mapNotNull { f ->
                        try {
                            normalize(gson.fromJson(f.readText(), object : TypeToken<Conversation>() {}.type))
                        } catch (e: Exception) {
                            KLog.w("ChatRepository", "Skipping unreadable conversation file ${f.name}: ${e.message}")
                            null
                        }
                    }?.sortedByDescending { it.updatedAt }
                    ?: emptyList()
            } catch (e: Exception) {
                KLog.e("ChatRepository", "Failed to list conversations", e)
                emptyList()
            }
        }

    suspend fun get(id: String): Conversation? =
        withContext(Dispatchers.IO) {
            try {
                val f = fileFor(id)
                if (f.exists()) {
                    normalize(gson.fromJson(f.readText(), object : TypeToken<Conversation>() {}.type))
                } else {
                    null
                }
            } catch (e: Exception) {
                KLog.w("ChatRepository", "Failed to read conversation $id: ${e.message}")
                null
            }
        }

    suspend fun save(conversation: Conversation): Conversation =
        withContext(Dispatchers.IO) {
            conversation.updatedAt = System.currentTimeMillis()
            if (conversation.title.isBlank()) {
                conversation.title =
                    conversation.messages.firstOrNull { it.role == ChatConstants.ROLE_USER }
                        ?.content?.take(48)?.replace("\n", " ") ?: "New Chat"
            }
            if (!writeAtomically(fileFor(conversation.id), gson.toJson(conversation))) {
                KLog.e("ChatRepository", "save() failed for conversation ${conversation.id}")
            }
            conversation
        }

    suspend fun rename(id: String, newTitle: String): Boolean =
        withContext(Dispatchers.IO) {
            val conv = get(id) ?: return@withContext false
            conv.title = newTitle.trim().ifBlank { "Untitled Chat" }
            conv.updatedAt = System.currentTimeMillis()
            writeAtomically(fileFor(conv.id), gson.toJson(conv))
        }

    suspend fun delete(id: String): Boolean =
        withContext(Dispatchers.IO) {
            fileFor(id).let { it.exists() && it.delete() }
        }

    /** Whether the conversation already has a file on disk. */
    suspend fun exists(id: String): Boolean =
        withContext(Dispatchers.IO) { fileFor(id).exists() }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) {
            var n = 0
            dir.listFiles()?.forEach { if (it.name.endsWith(".json") && it.delete()) n++ }
            n
        }

    /**
     * Ensures every node has a stable id and a linear parent chain for legacy
     * conversations saved before branching existed. Idempotent.
     */
    fun normalize(conv: Conversation): Conversation = Companion.normalize(conv)

    /** Walks the tree from root following [Conversation.branchActive] to build the visible path. */
    fun resolveActivePath(conv: Conversation): List<ChatMessageEntry> = Companion.resolveActivePath(conv)

    /** Sibling branches of a node (same parent), ordered by creation time. */
    fun siblingsOf(
        conv: Conversation,
        msg: ChatMessageEntry,
    ): List<ChatMessageEntry> = Companion.siblingsOf(conv, msg)

    companion object {
        const val ROOT_KEY = "root"

        @Suppress("SENSELESS_COMPARISON", "USELESS_IS_CHECK")
        fun normalize(conv: Conversation): Conversation {
            val msgs = conv.messages
            if (msgs.isEmpty()) return conv
            var changed = false
            val out = ArrayList<ChatMessageEntry>(msgs.size)
            var prevId: String? = null
            for (m in msgs) {
                var id = m.id
                if (id.isNullOrBlank()) {
                    id = UUID.randomUUID().toString()
                    changed = true
                }
                var parent = m.parentId
                if (parent.isNullOrBlank() && prevId != null) {
                    parent = prevId
                    changed = true
                }
                // Rebuild via the constructor (not copy()): legacy JSON parsed by
                // Gson bypasses the Kotlin constructor and leaves non-null fields
                // like `variants` as JVM nulls, which copy()'s intrinsic checks reject.
                if (id.isNullOrBlank() || m.variants == null) changed = true
                val needsSanitize =
                    id != m.id ||
                        parent != m.parentId ||
                        m.variants == null
                out.add(
                    if (needsSanitize) {
                        ChatMessageEntry(
                            role = m.role,
                            content = m.content,
                            timestamp = m.timestamp,
                            stats = m.stats,
                            variants = m.variants.orEmpty(),
                            selectedVariant = m.selectedVariant,
                            imagePath = m.imagePath,
                            id = id,
                            parentId = parent,
                            sources = m.sources,
                        )
                    } else {
                        m
                    },
                )
                prevId = id
            }
            if (changed) conv.messages = out
            return conv
        }

        fun resolveActivePath(conv: Conversation): List<ChatMessageEntry> {
            val nodes = conv.messages
            if (nodes.isEmpty()) return emptyList()
            val byParent = nodes.groupBy { it.parentId ?: ROOT_KEY }
            val active = conv.branchActive
            val path = ArrayList<ChatMessageEntry>(nodes.size)
            val visited = HashSet<String>()
            var key = ROOT_KEY
            while (true) {
                if (!visited.add(key)) break // cycle guard against corrupted data
                val kids = byParent[key]?.sortedBy { it.timestamp } ?: break
                if (kids.isEmpty()) break
                val chosenId = active?.get(key)
                val next = kids.firstOrNull { it.id == chosenId && it.id != null } ?: kids.first()
                path.add(next)
                val nextId = next.id ?: break
                key = nextId
            }
            return path
        }

        fun siblingsOf(
            conv: Conversation,
            msg: ChatMessageEntry,
        ): List<ChatMessageEntry> =
            conv.messages
                .filter { (it.parentId ?: ROOT_KEY) == (msg.parentId ?: ROOT_KEY) }
                .sortedBy { it.timestamp }
    }

    fun exportToMarkdown(conversation: Conversation): String =
        buildString {
            appendLine("# ${conversation.title.ifBlank { "LiteChat Conversation" }}")
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(conversation.createdAt))
            appendLine("*Exported: $dateStr*")
            appendLine()
            resolveActivePath(conversation).forEach { msg ->
                val speaker = if (msg.role.equals(ChatConstants.ROLE_USER, ignoreCase = true)) "**User**" else "**Assistant**"
                appendLine("### $speaker")
                appendLine(msg.content.trim())
                if (!msg.stats.isNullOrBlank()) {
                    appendLine()
                    appendLine("_${msg.stats}_")
                }
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
}
