package com.openai.codex.jetbrains.actions

import com.intellij.openapi.actionSystem.Presentation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class SendToCodexActionTest {
    private val projectRoot = Path.of("project").toAbsolutePath().normalize()
    private val file = projectRoot.resolve("src/Main.kt")

    @Test
    fun `action is absent when no matching live running session exists`() {
        val presentation = Presentation()

        updateSendToCodexPresentation(
            presentation = presentation,
            hasEditorReference = true,
            hasLiveSession = false,
        )

        assertFalse(presentation.isVisible)
        assertFalse(presentation.isEnabled)
    }

    @Test
    fun `action is visible for a live session with no editor selection`() {
        val reference = CodexEditorReference.create(projectRoot, file, "first\nsecond")
        val presentation = Presentation()

        updateSendToCodexPresentation(presentation, reference != null, hasLiveSession = true)

        assertTrue(presentation.isVisible)
        assertTrue(presentation.isEnabled)
    }

    @Test
    fun `action is visible for a live session with an editor selection`() {
        val reference = CodexEditorReference.create(
            projectRoot = projectRoot,
            file = file,
            documentText = "first\nsecond",
            selectionStart = 6,
            selectionEnd = 12,
        )
        val presentation = Presentation()

        updateSendToCodexPresentation(presentation, reference != null, hasLiveSession = true)

        assertTrue(presentation.isVisible)
        assertTrue(presentation.isEnabled)
    }

    @Test
    fun `action is absent for files outside the project even with a live session`() {
        val presentation = Presentation()

        updateSendToCodexPresentation(
            presentation = presentation,
            hasEditorReference = false,
            hasLiveSession = true,
        )

        assertFalse(presentation.isVisible)
        assertFalse(presentation.isEnabled)
    }
}
