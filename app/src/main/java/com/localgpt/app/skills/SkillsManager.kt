package com.localgpt.app.skills

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.localgpt.app.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SkillsManager private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var instance: SkillsManager? = null

        fun getInstance(context: Context): SkillsManager =
            instance ?: synchronized(this) {
                instance ?: SkillsManager(context.applicationContext).also { instance = it }
            }

        val BUILTIN_SKILLS = listOf(
            Skill(
                id = "builtin_android_kotlin",
                name = "Android Kotlin & Compose Architect",
                description = "Expert in Kotlin 2.x, Jetpack Compose Material 3, Coroutines, Flow, and MVVM Architecture.",
                category = "Coding",
                iconCategory = "code",
                instructions = "You are an expert Android Architect specializing in Kotlin 2.x, Jetpack Compose Material 3, Coroutines, StateFlow, and modern MVVM architecture. Always write clean, production-ready, type-safe, idiomatic code with full implementations. Avoid omitting required imports or leaving placeholder comments.",
                isEnabled = true,
                isBuiltIn = true,
            ),
            Skill(
                id = "builtin_web_dev",
                name = "Modern Web UI Developer",
                description = "Produces standalone, elegant single-file web applications (HTML5/CSS3/ES6+ JavaScript).",
                category = "Coding",
                iconCategory = "palette",
                instructions = "You are a senior frontend developer who crafts modern, responsive, visually stunning single-file web applications. Use semantic HTML5, beautiful CSS3 (dark modes, glassmorphism, responsive grid/flexbox, smooth transitions), and clean vanilla JavaScript ES6+. Always return complete, self-contained, runnable code blocks.",
                isEnabled = true,
                isBuiltIn = true,
            ),
            Skill(
                id = "builtin_logic_solver",
                name = "Deep Problem Solver & Logic Analyst",
                description = "Step-by-step mathematical, algorithmic, and logical breakdown with constraint verification.",
                category = "Reasoning",
                iconCategory = "math",
                instructions = "You are a rigorous analytical thinker and problem solver. When presented with a problem, break it down systematically step-by-step: analyze preconditions, identify edge cases, formulate mathematical or logical proofs, verify constraints, and conclude with concise clarity.",
                isEnabled = false,
                isBuiltIn = true,
            ),
            Skill(
                id = "builtin_technical_writer",
                name = "Technical Writer & Multilingual Translator",
                description = "Specialist in technical documentation, Markdown structure, and fluent English/Indonesian translations.",
                category = "Writing",
                iconCategory = "write",
                instructions = "You are a professional technical communicator and translator. Structure content with clear Markdown hierarchies, clean bullet points, and tables. When translating between English, Indonesian, or other languages, ensure natural fluency while preserving technical terminology and precise meaning.",
                isEnabled = false,
                isBuiltIn = true,
            ),
            Skill(
                id = "builtin_web_search_id",
                name = "Web Search (Indonesia)",
                description = "Live real-time search for Indonesian news, media, sports, and Wikipedia ID.",
                category = "Research",
                iconCategory = "search",
                instructions = """
You are an expert Indonesian Web Researcher with real-time Indonesian internet access.
[AVAILABLE TOOLS]
- `web_search_id(query: string)`: Searches Indonesian news, live events, sports scores, weather, and Wikipedia Indonesia.

HOW TO CALL TOOLS:
1. IMPORTANT (PRONOUN RESOLUTION): If the user refers to pronouns like "dia", "beliau", "ia", "itu", or follow-up questions, you MUST replace the pronoun with the actual entity or subject name from conversation history (e.g. search "when did [Entity/Person Name] win the match" instead of "when did he win").
2. Construct concise, self-contained search queries with key event terms.

Output the tool call in this exact format:
<tool_call>
{"name": "web_search_id", "arguments": {"query": "specific search query"}}
</tool_call>

Once search results are provided in the context, synthesize the facts directly in the requested language and cite source URLs clearly using references [1], [2].
                """.trimIndent(),
                isEnabled = true,
                isBuiltIn = true,
            ),
            Skill(
                id = "builtin_web_search_en",
                name = "Web Search (Global / English)",
                description = "Live real-time search for global news, tech documentation, international events, and Wikipedia EN.",
                category = "Research",
                iconCategory = "search",
                instructions = """
You are an expert Global Web Researcher with real-time global internet access.
[AVAILABLE TOOLS]
- `web_search_en(query: string)`: Searches global English news, tech documentation, international sports, and Wikipedia EN.

HOW TO CALL TOOLS:
1. IMPORTANT (PRONOUN RESOLUTION): If the user refers to "he", "she", "they", "it", or asks follow-up questions, you MUST replace the pronoun with the actual entity or subject name from conversation history (e.g. search "when did [Entity/Person Name] win the championship" instead of "when did he win").
2. Construct detailed, self-contained search queries in English.

Output the tool call in this exact format:
<tool_call>
{"name": "web_search_en", "arguments": {"query": "detailed search query in english"}}
</tool_call>

Once search results are provided in the context, synthesize the facts directly in English and cite source URLs clearly using references [1], [2].
                """.trimIndent(),
                isEnabled = false,
                isBuiltIn = true,
            ),
        )
    }

    private val gson = Gson()
    private val dir = File(context.filesDir, "skills").apply { if (!exists()) mkdirs() }
    private val customSkillsFile get() = File(dir, "custom_skills.json")
    private val presetTogglesFile get() = File(dir, "preset_toggles.json")

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    init {
        loadSkills()
    }

    @Synchronized
    private fun loadSkills() {
        val customList: List<Skill> = try {
            if (customSkillsFile.exists()) {
                gson.fromJson(customSkillsFile.readText(), object : TypeToken<List<Skill>>() {}.type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        val toggles: Map<String, Boolean> = try {
            if (presetTogglesFile.exists()) {
                gson.fromJson(presetTogglesFile.readText(), object : TypeToken<Map<String, Boolean>>() {}.type) ?: emptyMap()
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }

        val builtinWithToggles = BUILTIN_SKILLS.map { preset ->
            if (toggles.containsKey(preset.id)) {
                preset.copy(isEnabled = toggles[preset.id] ?: preset.isEnabled)
            } else {
                preset
            }
        }

        _skills.value = builtinWithToggles + customList
    }

    private fun persistCustom(customSkills: List<Skill>) {
        try {
            customSkillsFile.writeText(gson.toJson(customSkills))
        } catch (e: Exception) {
            KLog.e("Skills", "Failed to persist custom skills: ${e.message}")
        }
    }

    private fun persistPresetToggles() {
        try {
            val map = _skills.value.filter { it.isBuiltIn }.associate { it.id to it.isEnabled }
            presetTogglesFile.writeText(gson.toJson(map))
        } catch (e: Exception) {
            KLog.e("Skills", "Failed to persist preset toggles: ${e.message}")
        }
    }

    suspend fun saveSkill(skill: Skill): Unit = withContext(Dispatchers.IO) {
        val current = _skills.value.toMutableList()
        val index = current.indexOfFirst { it.id == skill.id }
        if (index >= 0) {
            current[index] = skill
        } else {
            current.add(skill)
        }
        _skills.value = current
        val customOnly = current.filterNot { it.isBuiltIn }
        persistCustom(customOnly)
        if (skill.isBuiltIn) {
            persistPresetToggles()
        }
    }

    suspend fun toggleSkill(id: String, isEnabled: Boolean): Unit = withContext(Dispatchers.IO) {
        val current = _skills.value.map {
            if (it.id == id) it.copy(isEnabled = isEnabled) else it
        }
        _skills.value = current
        persistCustom(current.filterNot { it.isBuiltIn })
        persistPresetToggles()
    }

    suspend fun deleteSkill(id: String): Unit = withContext(Dispatchers.IO) {
        val current = _skills.value.filterNot { it.id == id && !it.isBuiltIn }
        _skills.value = current
        persistCustom(current.filterNot { it.isBuiltIn })
    }

    fun getActivePromptInstructions(): String {
        val active = _skills.value.filter { it.isEnabled && it.instructions.isNotBlank() }
        if (active.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n[ACTIVE SKILLS & SPECIALIZED DIRECTIVES]\n")
        active.forEach { skill ->
            sb.append("### Skill: ${skill.name}\n")
            sb.append("${skill.instructions.trim()}\n\n")
        }
        return sb.toString().trimEnd()
    }

    suspend fun importFromUri(uri: Uri): Skill? = withContext(Dispatchers.IO) {
        try {
            val fileName = queryDisplayName(uri) ?: "imported_skill.md"
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext null
            importFromText(text, fileName)
        } catch (e: Exception) {
            KLog.e("Skills", "Failed to import skill from URI: ${e.message}")
            null
        }
    }

    suspend fun importFromText(rawText: String, fileName: String? = null): Skill? = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext null
        try {
            // Check if JSON format
            if (rawText.trimStart().startsWith("{")) {
                val parsed = gson.fromJson(rawText, Skill::class.java)
                if (parsed != null && parsed.name.isNotBlank()) {
                    val skill = parsed.copy(
                        id = UUID.randomUUID().toString(),
                        isBuiltIn = false,
                        createdAt = System.currentTimeMillis()
                    )
                    saveSkill(skill)
                    return@withContext skill
                }
            }

            // Check if Frontmatter YAML Markdown
            var name = fileName?.substringBeforeLast(".")?.replace("_", " ")?.replace("-", " ")?.capitalizeWords() ?: "Custom Skill"
            var description = "Imported skill instructions."
            var category = "General"
            var iconCategory = "code"
            var instructions = rawText.trim()

            if (rawText.startsWith("---")) {
                val parts = rawText.split("---", limit = 3)
                if (parts.size >= 3) {
                    val frontmatter = parts[1]
                    instructions = parts[2].trim()

                    frontmatter.lines().forEach { line ->
                        val trimmed = line.trim()
                        when {
                            trimmed.startsWith("name:", ignoreCase = true) ->
                                name = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                            trimmed.startsWith("description:", ignoreCase = true) ->
                                description = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                            trimmed.startsWith("category:", ignoreCase = true) ->
                                category = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                            trimmed.startsWith("icon:", ignoreCase = true) ->
                                iconCategory = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                        }
                    }
                }
            } else if (rawText.startsWith("# ")) {
                val firstLine = rawText.lines().first()
                name = firstLine.removePrefix("# ").trim()
                instructions = rawText.lines().drop(1).joinToString("\n").trim()
            }

            val skill = Skill(
                id = UUID.randomUUID().toString(),
                name = name.take(80),
                description = description.take(200),
                instructions = instructions,
                category = category.take(30),
                iconCategory = iconCategory,
                isEnabled = true,
                isBuiltIn = false,
                createdAt = System.currentTimeMillis(),
            )
            saveSkill(skill)
            skill
        } catch (e: Exception) {
            KLog.e("Skills", "Import text parsing error: ${e.message}")
            null
        }
    }

    suspend fun exportSkill(skill: Skill): File? = withContext(Dispatchers.IO) {
        try {
            val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LiteChat/Skills")
            if (!baseDir.exists()) baseDir.mkdirs()
            val safeName = skill.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
            val file = File(baseDir, "$safeName.md")
            val content = buildString {
                appendLine("---")
                appendLine("name: \"${skill.name}\"")
                appendLine("description: \"${skill.description}\"")
                appendLine("category: \"${skill.category}\"")
                appendLine("icon: \"${skill.iconCategory}\"")
                appendLine("---")
                appendLine()
                appendLine(skill.instructions.trim())
            }
            file.writeText(content)
            file
        } catch (e: Exception) {
            KLog.e("Skills", "Failed to export skill: ${e.message}")
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
}
