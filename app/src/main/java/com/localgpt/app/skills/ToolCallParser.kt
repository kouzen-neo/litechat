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
        "(?<![\\w])(?:call:)?(web_search(?:_id|_en|_indonesia|_global)?)\\s*\\(\\s*query\\s*=\\s*[\"']([^\"']+)[\"']\\s*\\)",
        Pattern.CASE_INSENSITIVE,
    )
    private val JSON_NAME_PATTERN = Pattern.compile(
        "\"name\"\\s*:\\s*\"web_search(?:_id|_en|_indonesia|_global)?\"",
        Pattern.CASE_INSENSITIVE,
    )

    /**
     * Extracts a tool call from the generated text if present.
     *
     * Iteration contract: this parser is stateless and never loops by itself.
     * Bounding the agentic loop is the caller's responsibility.
     * ChatViewModel.handleAutonomousToolCall() performs exactly ONE tool-call
     * round per generation (parse -> web search -> follow-up stream) and does
     * not re-parse the follow-up, so a runaway model cannot trigger unbounded
     * searches. If multi-step looping is added elsewhere, cap it (e.g. max 3
     * iterations) and stop when no new tool call is produced.
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
        // Balanced-brace scan: a lazy `.*?}` would stop at the first inner
        // brace and truncate nested "arguments" objects, producing invalid JSON.
        if ((text.contains("\"web_search\"") || text.contains("\"web_search_id\"") || text.contains("\"web_search_en\"")) && text.contains("query")) {
            val nameMatcher = JSON_NAME_PATTERN.matcher(text)
            if (nameMatcher.find()) {
                extractBalancedJson(text, nameMatcher.start())?.let { jsonStr ->
                    parseJson(jsonStr, jsonStr)?.let { return it }
                }
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
            val name = obj.optString("name")
            if (!name.startsWith("web_search", ignoreCase = true)) return null

            val args = obj.get("arguments")
            val query = when {
                args != null && args.isJsonObject -> args.asJsonObject.optString("query")
                args != null && args.isJsonPrimitive && args.asJsonPrimitive.isString -> args.asString
                else -> obj.optString("query")
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

    /**
     * Extracts the first balanced `{...}` JSON object surrounding the match at
     * [fromIndex] (the `"name"` key sits *inside* the object, so the opening
     * brace is searched backwards). String literals (with escapes) are
     * respected so braces inside quoted values don't break the scan. Returns
     * null when no balanced object is found within a sane bound.
     */
    private fun extractBalancedJson(text: String, fromIndex: Int): String? {
        var i = fromIndex
        while (i >= 0 && text[i] != '{') i--
        if (i < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        val sb = StringBuilder()
        for (j in i until text.length) {
            val c = text[j]
            sb.append(c)
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return sb.toString()
                    }
                }
            }
            // Safety bound: a single tool call never needs more than this.
            if (sb.length > 8192) return null
        }
        return null
    }

    private fun JsonObject.optString(key: String): String =
        runCatching {
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
        }.getOrDefault("")
}
