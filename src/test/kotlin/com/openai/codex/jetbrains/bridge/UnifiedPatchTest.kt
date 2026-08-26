package com.openai.codex.jetbrains.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnifiedPatchTest {
    @Test
    fun `applies exact update hunks without writing files`() {
        val patch = UnifiedPatch.parse("@@ -1,2 +1,2 @@\n before\n-old\n+new") ?: error("patch")

        assertEquals("before\nnew\n", patch.applyTo("before\nold\n"))
        assertNull(patch.applyTo("before\nstale\n"))
    }

    @Test
    fun `supports add and delete hunk shapes`() {
        val add = UnifiedPatch.parse("@@ -0,0 +1,2 @@\n+one\n+two") ?: error("add")
        val delete = UnifiedPatch.parse("@@ -1,2 +0,0 @@\n-one\n-two") ?: error("delete")

        assertEquals("one\ntwo\n", add.applyTo(""))
        assertEquals("", delete.applyTo("one\ntwo"))
    }

    @Test
    fun `rejects malformed or no-newline patches`() {
        assertNull(UnifiedPatch.parse("not a patch"))
        assertNull(UnifiedPatch.parse("@@ -1 +1 @@\n\\ No newline at end of file"))
        assertNull(UnifiedPatch.parse("@@ -1,1 +1,1 @@\n? invalid"))
    }
}
