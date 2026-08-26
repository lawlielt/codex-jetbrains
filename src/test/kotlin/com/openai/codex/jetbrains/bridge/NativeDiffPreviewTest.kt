package com.openai.codex.jetbrains.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDiffPreviewTest {
    @Test
    fun `combines a multi-file atomic decision into a read-only before and after preview`() {
        val proposal = PreparedProposal(
            FileChangeProposal("thread", "turn", "item", emptyList()),
            listOf(
                PreparedFileChange("old.txt", "renamed.txt", "before\n", "after\n"),
                PreparedFileChange("deleted.txt", null, "gone\n", ""),
            ),
        )

        val (before, after) = proposalPreview(proposal)

        assertEquals("===== old.txt =====\nbefore\n\n===== deleted.txt =====\ngone\n", before)
        assertEquals("===== renamed.txt =====\nafter\n\n===== deleted.txt =====\n", after)
    }
}
