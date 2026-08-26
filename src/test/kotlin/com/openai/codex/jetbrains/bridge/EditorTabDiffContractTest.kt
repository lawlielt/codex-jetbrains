package com.openai.codex.jetbrains.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class EditorTabDiffContractTest {
    @Test
    fun `routes openDiff only through the built in editor tab path`() {
        assertEquals(OpenDiffPresentationRoute.BUILTIN_EDITOR_TAB, openDiffPresentationRoute())
    }

    @Test
    fun `uses the required Codex editor tab title`() {
        val proposal = PreparedOpenDiff(
            OpenDiffRequest("call", "thread", "turn", OpenDiffOperation.UPDATE, "pkg/auth.go", "pkg/auth.go", "after", "before"),
            Path.of("/project/pkg/auth.go"),
            Path.of("/project/pkg/auth.go"),
            "before",
        )

        assertEquals("[Codex] auth.go", codexDiffEditorTabTitle(proposal))
    }

    @Test
    fun `transient close and reopen cannot turn into a late duplicate rejection`() {
        val lifecycle = EditorTabReviewLifecycle()

        assertTrue(lifecycle.editorClosed())
        assertTrue(lifecycle.editorOpened())
        assertTrue(lifecycle.decide())
        assertFalse(lifecycle.editorClosed())
        assertFalse(lifecycle.editorOpened())
        assertFalse(lifecycle.decide())
    }
}
