package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadIdentityTest {
    @Test
    fun `persisted thread id maps to resume and can be restored from response`() {
        val params = ThreadIdentity.resumeParams("thr-persisted")!!
        assertEquals("thr-persisted", params.get("threadId").asString)
        val result = JsonParser.parseString("{\"thread\":{\"id\":\"thr-restored\"}}")
        assertEquals("thr-restored", ThreadIdentity.extractThreadId(result))
        assertNull(ThreadIdentity.resumeParams(null))
    }
}
