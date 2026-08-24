package com.openai.codex.jetbrains.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContextFormatterTest {
    @Test
    fun `selection diagnostics and total payload are bounded`() {
        val snapshot = EditorContextSnapshot(
            relativePath = "src/Main.kt",
            startLine = 3,
            endLine = 9,
            selectedText = "x".repeat(500),
            diagnostics = (1..10).map { EditorDiagnostic(it, "ERROR", "problem ".repeat(30)) },
        )
        val bounds = ContextBounds(maxSelectionChars = 100, maxDiagnostics = 2, maxDiagnosticChars = 25, maxTotalChars = 300)
        val formatted = EditorContextFormatter.format(snapshot, bounds)
        assertTrue(formatted.startsWith("IDE context (bounded):\n@src/Main.kt#L3-L9"))
        assertTrue(formatted.contains("selection truncated"))
        assertTrue(formatted.endsWith("- … diagnostics truncated …\n"))
        assertEquals(bounds.maxTotalChars, formatted.length)
        assertFalse(formatted.contains("problem ".repeat(10)))
    }

    @Test
    fun `no snapshot adds no unrelated project context`() {
        assertTrue(EditorContextFormatter.format(null).isEmpty())
    }
}
