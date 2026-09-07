package com.localgpt.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeArtifactsTest {

    @Test
    fun `extracts multiple fenced blocks with languages`() {
        val md =
            """
            Here you go:
            ```html
            <p>hi</p>
            ```
            and css:
            ```css
            body {}
            ```
            """.trimIndent()

        val blocks = CodeArtifacts.extractFencedBlocks(md)

        assertEquals(2, blocks.size)
        assertEquals("html", blocks[0].language)
        assertEquals("<p>hi</p>", blocks[0].code)
        assertEquals("css", blocks[1].language)
    }

    @Test
    fun `unterminated trailing block is included`() {
        val md = "```py\nprint(1)\n"
        val blocks = CodeArtifacts.extractFencedBlocks(md)
        assertEquals(1, blocks.size)
        assertEquals("print(1)", blocks[0].code)
    }

    @Test
    fun `filename hint in fence info wins`() {
        val md = "```html filename=page.html\n<b>x</b>\n```"
        val files = CodeArtifacts.deriveProjectFiles(CodeArtifacts.extractFencedBlocks(md))
        assertEquals("page.html", files.single().fileName)
        assertEquals("<b>x</b>", files.single().content)
    }

    @Test
    fun `filename comment marker is detected and stripped`() {
        val md =
            """
            ```html
            <!-- file: index.html -->
            <div>ok</div>
            ```
            """.trimIndent()
        val files = CodeArtifacts.deriveProjectFiles(CodeArtifacts.extractFencedBlocks(md))
        assertEquals("index.html", files.single().fileName)
        assertFalse(files.single().content.contains("file:"))
        assertTrue(files.single().content.contains("<div>ok</div>"))
    }

    @Test
    fun `multi block without names gets standard web names`() {
        val md =
            """
            ```html
            <html></html>
            ```
            ```css
            body{}
            ```
            ```js
            console.log(1)
            ```
            """.trimIndent()

        val files = CodeArtifacts.deriveProjectFiles(CodeArtifacts.extractFencedBlocks(md))

        assertEquals(listOf("index.html", "style.css", "script.js"), files.map { it.fileName })
    }

    @Test
    fun `single unnamed block uses fallback stem and extension`() {
        val md = "```python\nx = 1\n```"
        val files = CodeArtifacts.deriveProjectFiles(CodeArtifacts.extractFencedBlocks(md))
        assertEquals("litechat.py", files.single().fileName)
    }

    @Test
    fun `unnamed html block derives name from title tag`() {
        val md = "```html\n<html><head><title>My Landing Page</title></head></html>\n```"
        val files = CodeArtifacts.deriveProjectFiles(CodeArtifacts.extractFencedBlocks(md))
        assertEquals("my-landing-page.html", files.single().fileName)
        assertTrue(files.single().content.contains("<title>"))
    }

    @Test
    fun `capture rule requires project-like output`() {
        assertTrue(CodeArtifacts.shouldCapture(CodeArtifacts.extractFencedBlocks("```a\n1\n```\n```b\n2\n```")))
        assertTrue(CodeArtifacts.shouldCapture(CodeArtifacts.extractFencedBlocks("```py\n" + List(12) { "x$it" }.joinToString("\n") + "\n```")))
        assertFalse(CodeArtifacts.shouldCapture(CodeArtifacts.extractFencedBlocks("```py\nx=1\ny=2\nz=3\n```")))
    }

    @Test
    fun `outside filename comment right before fence is captured`() {
        val md =
            """
            <!-- file: calculator.html -->
            ```html
            <div>calc</div>
            ```
            """.trimIndent()
        val blocks = CodeArtifacts.extractFencedBlocks(md)
        assertEquals("calculator.html", blocks.single().fileNameHint)
        val files = CodeArtifacts.deriveProjectFiles(blocks)
        assertEquals("calculator.html", files.single().fileName)
    }

    @Test
    fun `raw html without markdown fences is captured via fallback`() {
        val md =
            """
            <!-- file: calculator.html -->
            <!DOCTYPE html>
            <html>
            <head><title>Kalkulator Sederhana</title></head>
            <body><div class="calc">1+1=2</div></body>
            </html>
            """.trimIndent()
        val blocks = CodeArtifacts.extractFencedBlocks(md)
        assertEquals(1, blocks.size)
        assertEquals("calculator.html", blocks[0].fileNameHint)
        val files = CodeArtifacts.deriveProjectFiles(blocks)
        assertEquals("calculator.html", files.single().fileName)
        assertTrue(files.single().content.contains("<!DOCTYPE html>"))
    }

    @Test
    fun `deriveFileName extracts filename from comment or title`() {
        val htmlWithComment = "<!-- file: app.html -->\n<div>Hello</div>"
        assertEquals("app.html", CodeArtifacts.deriveFileName("html", htmlWithComment))

        val htmlWithTitle = "<html><head><title>Awesome App</title></head></html>"
        assertEquals("awesome-app.html", CodeArtifacts.deriveFileName("html", htmlWithTitle))

        val pythonCode = "# file: script.py\nprint('hello')"
        assertEquals("script.py", CodeArtifacts.deriveFileName("python", pythonCode))
    }
}
