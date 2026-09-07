package com.localgpt.app.util

import java.util.Locale

/**
 * Pure-Kotlin extraction of fenced code blocks and multi-file project naming.
 * No Android dependencies — fully unit-testable.
 */
object CodeArtifacts {

    data class FencedBlock(
        val language: String,
        val fileNameHint: String?,
        val code: String,
    )

    data class ProjectFile(
        val fileName: String,
        val language: String,
        val content: String,
    )

    /** Extracts fenced ``` blocks. Supports outside filename comments and fallback for raw HTML. */
    fun extractFencedBlocks(markdown: String): List<FencedBlock> {
        if (markdown.isBlank()) return emptyList()
        val out = mutableListOf<FencedBlock>()
        var inBlock = false
        var info = ""
        var outsideHint: String? = null
        var lastNonEmptyLine = ""
        val buf = StringBuilder()
        for (line in markdown.lines()) {
            val trimmed = line.trim()
            if (!inBlock) {
                if (trimmed.startsWith("```")) {
                    inBlock = true
                    info = trimmed.removePrefix("```").trim()
                    outsideHint = findFileNameFromLine(lastNonEmptyLine)
                    buf.setLength(0)
                } else if (trimmed.isNotEmpty()) {
                    lastNonEmptyLine = trimmed
                }
            } else {
                if (trimmed.startsWith("```")) {
                    out.add(fromInfoAndCode(info, buf.toString().trimEnd(), outsideHint))
                    inBlock = false
                    outsideHint = null
                    buf.setLength(0)
                } else {
                    buf.appendLine(line)
                }
            }
        }
        if (inBlock && buf.isNotBlank()) {
            out.add(fromInfoAndCode(info, buf.toString().trimEnd(), outsideHint))
        }

        // Fallback: If no markdown fences were used, but the response is a raw full HTML document
        if (out.isEmpty()) {
            val trimmed = markdown.trim()
            if (trimmed.contains("<!DOCTYPE html", ignoreCase = true) || trimmed.contains("<html", ignoreCase = true)) {
                val marker = findFileNameCommentLine(trimmed)
                val explicitName = marker?.second ?: findFileNameFromLine(trimmed.lines().firstOrNull() ?: "")
                val hint = explicitName ?: htmlTitleSlug(trimmed)?.let { "$it.html" } ?: "index.html"
                val body = if (marker != null) {
                    trimmed.lines().filterIndexed { i, _ -> i != marker.first }.joinToString("\n").trimStart('\n')
                } else {
                    trimmed
                }
                out.add(FencedBlock(language = "html", fileNameHint = hint, code = body))
            }
        }

        return out
    }

    /** Auto-capture rule: project-like answers only (>=2 blocks, or one substantial block). */
    fun shouldCapture(blocks: List<FencedBlock>): Boolean =
        blocks.size >= 2 || blocks.any { it.code.lines().size >= 10 }

    /**
     * Derives a smart, clean filename for a single code snippet.
     */
    fun deriveFileName(
        language: String,
        code: String,
        fallbackStem: String = "litechat",
    ): String {
        val block = FencedBlock(language = language, fileNameHint = null, code = code)
        val files = deriveProjectFiles(listOf(block), fallbackStem)
        return files.firstOrNull()?.fileName ?: FileSaver.defaultFileName(language)
    }

    /**
     * Maps fenced blocks to named project files.
     * Priority: explicit filename hint (fence info / outside comment) > filename comment in code >
     * heuristic names (index.html / style.css / script.js / main.py ...).
     */
    fun deriveProjectFiles(
        blocks: List<FencedBlock>,
        fallbackStem: String = "litechat",
    ): List<ProjectFile> {
        if (blocks.isEmpty()) return emptyList()

        data class Resolved(val fileName: String, val language: String, val content: String)

        val resolved = ArrayList<Resolved>(blocks.size)
        val usedNames = HashSet<String>()

        fun unique(name: String): String {
            if (usedNames.add(name)) return name
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var i = 2
            while (true) {
                val candidate = "$base-$i.$ext"
                if (usedNames.add(candidate)) return candidate
                i++
            }
        }

        blocks.forEachIndexed { idx, b ->
            val lang = normalizeLang(b.language)
            val ext = extFor(lang, b.fileNameHint ?: b.language)
            var name = b.fileNameHint?.let { sanitize(it) }
            var body = b.code
            if (name == null) {
                val marker = findFileNameCommentLine(b.code)
                if (marker != null) {
                    name = sanitize(marker.second)
                    // Strip only the marker comment line so exported files stay clean.
                    body =
                        b.code
                            .lines()
                            .filterIndexed { i, _ -> i != marker.first }
                            .joinToString("\n")
                            .trimStart('\n')
                }
            }
            if (name == null) {
                name =
                    when {
                        lang == "html" ->
                            (htmlTitleSlug(b.code)?.let { "$it.html" })
                                ?: if (blocks.size == 1) "$fallbackStem.html" else "index.html"
                        blocks.size == 1 -> "$fallbackStem.$ext"
                        lang == "css" -> "style.css"
                        lang == "js" -> "script.js"
                        lang == "py" -> "main.py"
                        else -> "${fallbackStem}_${idx + 1}.$ext"
                    }
            }
            resolved.add(Resolved(unique(name), lang.ifBlank { extensionLanguage(name) }, body))
        }

        // Heuristic pass for multi-block sets where several entries collided on defaults:
        return resolved.map { ProjectFile(it.fileName, it.language, it.content) }
    }

    private fun fromInfoAndCode(
        info: String,
        code: String,
        outsideHint: String? = null,
    ): FencedBlock {
        val tokens = info.split(Regex("\\s+")).filter { it.isNotBlank() }
        val language = tokens.firstOrNull()?.lowercase(Locale.US) ?: ""
        val hintToken =
            outsideHint
                ?: tokens.drop(1).firstOrNull { it.startsWith("filename=") || it.startsWith("file=") }
                    ?.substringAfter('=')
                ?: tokens.drop(1).firstOrNull { it.contains('.') }
        return FencedBlock(language, hintToken, code)
    }

    private val FILE_COMMENT_REGEX =
        Regex(
            "^(?:\\s*)(?:<!--|/\\*|//|#)?\\s*(?:file|filename)\\s*[:=]\\s*([A-Za-z0-9._\\-/]+)",
            RegexOption.IGNORE_CASE,
        )

    private fun findFileNameFromLine(line: String): String? {
        val m = FILE_COMMENT_REGEX.find(line.trim()) ?: return null
        val candidate = m.groupValues[1].trim()
        return if (candidate.contains('.')) candidate.substringAfterLast('/') else null
    }

    /** Detects leading comments like `<!-- file: x.html -->`, `// file: x.js`, `# file: x.py`. */
    private fun findFileNameCommentLine(code: String): Pair<Int, String>? {
        code.lines().take(5).forEachIndexed { i, line ->
            val name = findFileNameFromLine(line)
            if (name != null) return i to name
        }
        return null
    }

    /** Derives a slug from an HTML <title> for meaningful default file names. */
    private fun htmlTitleSlug(code: String): String? {
        val m =
            Regex(
                "<title[^>]*>([\\s\\S]*?)</title>",
                RegexOption.IGNORE_CASE,
            ).find(code) ?: return null
        val slug =
            m.groupValues[1].trim()
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
        return slug.ifBlank { null }
    }

    private fun normalizeLang(raw: String): String =
        when (raw.lowercase(Locale.US)) {
            "python", "python3" -> "py"
            "javascript", "node", "nodejs" -> "js"
            "typescript" -> "ts"
            "shell", "bash", "zsh" -> "sh"
            "c++", "cpp" -> "cpp"
            "c#", "csharp" -> "cs"
            "kotlin" -> "kt"
            else -> raw.lowercase(Locale.US)
        }

    private fun extFor(
        lang: String,
        rawInfo: String,
    ): String =
        when {
            lang.matches(Regex("[a-z0-9]{1,8}")) -> lang
            rawInfo.contains('.') -> rawInfo.substringAfterLast('.').lowercase(Locale.US)
            else -> "txt"
        }

    private fun extensionLanguage(fileName: String): String = fileName.substringAfterLast('.', "").lowercase(Locale.US)

    private fun sanitize(name: String): String =
        name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(80)
            .ifBlank { "file.txt" }
}
