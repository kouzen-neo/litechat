package com.localgpt.app.skills

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.regex.Pattern

data class ParsedToolCall(
    val name: String,
    val query: String,
    val rawCallText: String,
    val lang: String = if (name.contains("en", ignoreCase = true) || name.contains("global", ignoreCase = true)) "en" else "id",
)

object ToolCallParser {
    private val gson = Gson()
    private val SEARCH_FN_REGEX = Pattern.compile(
        "(?:call:)?(web_search(?:_id|_en|_indonesia|_global)?)\\s*\\(\\s*query\\s*=\\s*[\"']([^\"']+)[\"']\\s*\\)",
        Pattern.CASE_INSENSITIVE,
    )

    /**
     * Extracts a tool call from the generated text if present.
     */
    fun parse(text: String): ParsedToolCall? {
        if (text.isBlank()) return null

        // 1. Check for XML tag: <tool_call>...</tool_call>
        val xmlPattern = Pattern.compile(
            "<tool_call>([\\s\\S]*?)(?:<\\/tool_call>|$)",
            Pattern.CASE_INSENSITIVE,
        )
        val xmlMatcher = xmlPattern.matcher(text)
        if (xmlMatcher.find()) {
            val body = xmlMatcher.group(1).orEmpty().trim()
            val parsed = parseJsonOrFunction(body, xmlMatcher.group(0).orEmpty())
            if (parsed != null) return parsed
        }

        // 2. Check for Markdown code block: ```tool_call ... ```
        val mdPattern = Pattern.compile(
            "```(?:tool_call|tool)(?:\\s+([\\s\\S]*?))?(?:```|$)",
            Pattern.CASE_INSENSITIVE,
        )
        val mdMatcher = mdPattern.matcher(text)
        if (mdMatcher.find()) {
            val body = mdMatcher.group(1).orEmpty().trim()
            val parsed = parseJsonOrFunction(body, mdMatcher.group(0).orEmpty())
            if (parsed != null) return parsed
        }

        // 3. Check for direct JSON tool call: {"name": "web_search...", ...}
        if ((text.contains("\"web_search\"") || text.contains("\"web_search_id\"") || text.contains("\"web_search_en\"")) && text.contains("query")) {
            val jsonPattern = Pattern.compile(
                "\\{\\s*\"name\"\\s*:\\s*\"(web_search(?:_id|_en|_indonesia|_global)?)\"[\\s\\S]*?\\}",
                Pattern.CASE_INSENSITIVE,
            )
            val jMatcher = jsonPattern.matcher(text)
            if (jMatcher.find()) {
                val jsonStr = jMatcher.group(0).orEmpty()
                val parsed = parseJson(jsonStr, jsonStr)
                if (parsed != null) return parsed
            }
        }

        // 4. Check for function style: web_search(query="...") or call:web_search(...)
        val fnMatcher = SEARCH_FN_REGEX.matcher(text)
        if (fnMatcher.find()) {
            val name = fnMatcher.group(1).orEmpty().trim()
            val query = fnMatcher.group(2).orEmpty().trim()
            if (query.isNotBlank()) {
                return ParsedToolCall(name, query, fnMatcher.group(0).orEmpty())
            }
        }

        return null
    }

    private fun parseJsonOrFunction(body: String, rawText: String): ParsedToolCall? {
        val fromJson = parseJson(body, rawText)
        if (fromJson != null) return fromJson

        val fnMatcher = SEARCH_FN_REGEX.matcher(body)
        if (fnMatcher.find()) {
            val name = fnMatcher.group(1).orEmpty().trim()
            val q = fnMatcher.group(2).orEmpty().trim()
            if (q.isNotBlank()) {
                return ParsedToolCall(name, q, rawText)
            }
        }
        return null
    }

    private fun parseJson(jsonStr: String, rawText: String): ParsedToolCall? {
        return try {
            val obj = gson.fromJson(jsonStr, JsonObject::class.java) ?: return null
            val name = obj.get("name")?.asString.orEmpty()
            if (!name.startsWith("web_search", ignoreCase = true)) return null

            var query = ""
            if (obj.has("arguments")) {
                val args = obj.get("arguments")
                if (args.isJsonObject) {
                    query = args.asJsonObject.get("query")?.asString.orEmpty()
                } else if (args.isJsonPrimitive) {
                    query = args.asString
                }
            } else if (obj.has("query")) {
                query = obj.get("query")?.asString.orEmpty()
            }

            if (query.isNotBlank()) {
                ParsedToolCall(name, query.trim(), rawText)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
