package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDiffInjectionTest {
    @Test
    fun `injects experimental capability then one strict tool and merges instructions`() {
        val injection = OpenDiffSessionInjection()
        val initialize = rewrite(injection, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{"existing":true}}}""")
        val initialized = initialize["params"] as JsonObject
        assertEquals(JsonPrimitive(true), (initialized["capabilities"] as JsonObject)["experimentalApi"])
        assertEquals(JsonPrimitive(true), (initialized["capabilities"] as JsonObject)["existing"])

        val start = rewrite(injection, """{"jsonrpc":"2.0","id":2,"method":"thread/start","params":{"developerInstructions":"existing instruction","cwd":"/project"}}""")
        val params = start["params"] as JsonObject
        val tools = params["dynamicTools"] as kotlinx.serialization.json.JsonArray
        val tool = tools.single() as JsonObject
        val schema = tool["inputSchema"] as JsonObject
        assertEquals(JsonPrimitive("openDiff"), tool["name"])
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertTrue((params["developerInstructions"] as JsonPrimitive).content.contains("existing instruction"))
        assertTrue((params["developerInstructions"] as JsonPrimitive).content.contains("openDiff"))
        val nextThread = injection.rewrite(MemoryRelayPayload("""{"method":"thread/start","params":{}}""".encodeToByteArray()))
        assertNotNull(nextThread)
    }

    @Test
    fun `leaves unrelated and large streamed payloads untouched`() {
        val injection = OpenDiffSessionInjection()
        val payload = MemoryRelayPayload(("""{"jsonrpc":"2.0","method":"model/list","params":{"catalog":"""" + "x".repeat(5 * 1024 * 1024) + """"}}""").encodeToByteArray())

        assertNull(injection.rewrite(payload))
        assertEquals("model/list", JsonRpcMethodScanner.find(payload))
    }

    @Test
    fun `dynamic response keeps json rpc id and structured content`() {
        val response = dynamicToolResponse(JsonPrimitive("request-id"), false, "IDE_DIFF_REJECTED")
        val result = response["result"] as JsonObject
        assertEquals(JsonPrimitive("request-id"), response["id"])
        assertEquals(JsonPrimitive(false), result["success"])
        assertFalse((result["contentItems"] as kotlinx.serialization.json.JsonArray).isEmpty())
    }

    private fun rewrite(injection: OpenDiffSessionInjection, source: String): JsonObject {
        val rewritten = injection.rewrite(MemoryRelayPayload(source.encodeToByteArray())) ?: error("rewritten payload")
        return Json.parseToJsonElement(rewritten.openStream().readBytes().decodeToString()) as JsonObject
    }
}
