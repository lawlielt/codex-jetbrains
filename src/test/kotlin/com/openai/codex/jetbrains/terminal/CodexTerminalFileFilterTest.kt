package com.openai.codex.jetbrains.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files

class CodexTerminalFileFilterTest {
    @Test
    fun `parses project paths with line column and line ranges`() {
        val line = "See src/main/App.kt:12:4, README.md#L8-L10, and test/Foo.kt:20-22."

        val references = TerminalFileReferenceParser.parse(line)

        assertEquals(
            listOf(
                TerminalFileReference("src/main/App.kt", 12, 4, null, 4, 24),
                TerminalFileReference("README.md", 8, null, 10, 26, 42),
                TerminalFileReference("test/Foo.kt", 20, null, 22, 48, 65),
            ),
            references,
        )
    }

    @Test
    fun `parses delimited paths with spaces without including delimiters`() {
        val line = "Open `docs/design notes.md#L3-L5` or \"src/more notes.kt:9\"."

        val references = TerminalFileReferenceParser.parse(line)

        assertEquals(2, references.size)
        assertEquals("docs/design notes.md", references[0].path)
        assertEquals("docs/design notes.md#L3-L5", line.substring(references[0].startOffset, references[0].endOffset))
        assertEquals("src/more notes.kt", references[1].path)
        assertEquals("src/more notes.kt:9", line.substring(references[1].startOffset, references[1].endOffset))
    }

    @Test
    fun `parses Windows separators and drive-qualified paths`() {
        val line = "src\\main\\App.kt:7 C:\\work\\demo\\Main.cs#L11-L14"

        val references = TerminalFileReferenceParser.parse(line)

        assertEquals("src\\main\\App.kt", references[0].path)
        assertEquals(7, references[0].line)
        assertEquals("C:\\work\\demo\\Main.cs", references[1].path)
        assertEquals(11, references[1].line)
        assertEquals(14, references[1].endLine)
    }

    @Test
    fun `rejects URLs invalid positions and descending ranges`() {
        val line = "https://example.test/a.kt:12 bad.kt:0 other.kt#L9-L2 huge.kt:999999999999999999999"

        assertTrue(TerminalFileReferenceParser.parse(line).isEmpty())
    }

    @Test
    fun `resolves existing files inside project and rejects traversal and missing files`() {
        val root = Files.createTempDirectory("codex-terminal-filter-")
        val outside = Files.createTempFile("codex-terminal-outside-", ".kt")
        try {
            val source = Files.createDirectories(root.resolve("src")).resolve("App.kt")
            Files.writeString(source, "fun main() = Unit\n")
            val resolver = ProjectFileReferenceResolver(root)

            assertEquals(source, resolver.resolve("src/App.kt"))
            assertEquals(source, resolver.resolve(source.toString()))
            assertNull(resolver.resolve("../${outside.fileName}"))
            assertNull(resolver.resolve("src/Missing.kt"))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `rejects a symlink that escapes the project`() {
        val root = Files.createTempDirectory("codex-terminal-filter-")
        val outside = Files.createTempFile("codex-terminal-outside-", ".kt")
        try {
            val link = root.resolve("outside.kt")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (error: Exception) {
                assumeNoException(error)
            }

            assertNull(ProjectFileReferenceResolver(root).resolve("outside.kt"))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }
}
