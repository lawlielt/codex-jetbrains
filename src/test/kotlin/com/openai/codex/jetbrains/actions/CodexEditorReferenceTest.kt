package com.openai.codex.jetbrains.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class CodexEditorReferenceTest {
    private val projectRoot = Path.of("project").toAbsolutePath().normalize()

    @Test
    fun `creates a file-only reference when the editor has no selection`() {
        assertEquals(
            "@src/main/My file.kt",
            reference("src", "main", "My file.kt"),
        )
    }

    @Test
    fun `uses an idiomatic suffix for a selection within one line`() {
        assertEquals(
            "@src/Main.kt#L2",
            reference(
                "src",
                "Main.kt",
                text = "first\nsecond\nthird",
                selectionStart = 6,
                selectionEnd = 12,
            ),
        )
    }

    @Test
    fun `uses one-based inclusive line ranges`() {
        assertEquals(
            "@src/Main.kt#L2-3",
            reference(
                "src",
                "Main.kt",
                text = "first\nsecond\nthird\nfourth",
                selectionStart = 8,
                selectionEnd = 17,
            ),
        )
    }

    @Test
    fun `selection ending at the next line boundary excludes that line`() {
        val text = "first\nsecond\nthird"
        assertEquals(
            "@src/Main.kt#L2",
            reference(
                "src",
                "Main.kt",
                text = text,
                selectionStart = 6,
                selectionEnd = 13,
            ),
        )
    }

    @Test
    fun `selection accepts document boundary offsets`() {
        val text = "first\nsecond\nthird"
        assertEquals(
            "@src/Main.kt#L1-3",
            reference(
                "src",
                "Main.kt",
                text = text,
                selectionStart = 0,
                selectionEnd = text.length,
            ),
        )
    }

    @Test
    fun `normalizes project-relative path separators to forward slashes`() {
        assertEquals(
            "@src/generated/api/Client.kt",
            reference("src", "generated", "api", "Client.kt"),
        )
    }

    @Test
    fun `rejects files outside the project after normalization`() {
        assertNull(
            CodexEditorReference.create(
                projectRoot = projectRoot,
                file = projectRoot.resolve("..").resolve("outside.kt"),
                documentText = "",
            ),
        )
    }

    private fun reference(
        vararg path: String,
        text: String = "",
        selectionStart: Int? = null,
        selectionEnd: Int? = null,
    ): String? = CodexEditorReference.create(
        projectRoot = projectRoot,
        file = path.fold(projectRoot, Path::resolve),
        documentText = text,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
    )
}
