package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexEventReducerTest {
    @Test
    fun `assistant deltas stream in order and are accumulated by item`() {
        val reducer = CodexEventReducer()
        val first = notification("Hello ")
        val second = notification("world")

        assertEquals("Hello ", reducer.consume(first)?.assistantDelta)
        assertEquals("world", reducer.consume(second)?.assistantDelta)
        assertEquals("Hello world", reducer.accumulatedText("item-1"))
    }

    @Test
    fun `turn completion is surfaced`() {
        val turn = JsonObject().apply {
            addProperty("id", "turn-1")
            addProperty("status", "interrupted")
        }
        val update = CodexEventReducer().consume(InboundMessage.Notification(
            "turn/completed",
            JsonObject().apply { add("turn", turn) },
        ))
        assertTrue(update!!.completed)
        assertEquals("Turn interrupted", update.activity)
    }

    private fun notification(delta: String) = InboundMessage.Notification(
        "item/agentMessage/delta",
        JsonObject().apply {
            addProperty("threadId", "thread-1")
            addProperty("turnId", "turn-1")
            addProperty("itemId", "item-1")
            addProperty("delta", delta)
        },
    )
}
