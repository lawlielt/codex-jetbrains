package com.openai.codex.jetbrains.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputReferenceNavigatorTest {
    @Test
    fun `parses project relative codex reference`() {
        assertEquals(
            ProjectFileReference("src/main/App.kt", 12, 18),
            OutputReferenceNavigator.parse("@src/main/App.kt#L12-L18"),
        )
        assertNull(OutputReferenceNavigator.parse("/tmp/App.kt:12"))
    }
}
