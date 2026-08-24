package com.openai.codex.jetbrains.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedDiffApplierTest {
    @Test
    fun `applies unified hunk for diff preview`() {
        val before = "one\ntwo\nthree"
        val diff = """--- a/file
+++ b/file
@@ -1,3 +1,3 @@
 one
-two
+TWO
 three"""
        assertEquals("one\nTWO\nthree", UnifiedDiffApplier.apply(before, diff))
    }
}
