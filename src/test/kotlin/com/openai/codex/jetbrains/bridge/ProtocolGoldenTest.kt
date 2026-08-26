package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolGoldenTest {
    @Test
    fun `approval response preserves the upstream request id and uses accept`() {
        val response = approvalResponse(Json.parseToJsonElement("42"), ApprovalDecision.ACCEPT)

        assertEquals("42", response["id"]?.jsonPrimitive?.content)
        assertEquals("accept", response["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content)
    }

    @Test
    fun `approval response preserves a string request id and uses decline`() {
        val response = approvalResponse(Json.parseToJsonElement("\"request-7\""), ApprovalDecision.DECLINE)

        assertEquals("request-7", response["id"]?.jsonPrimitive?.content)
        assertEquals("decline", response["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content)
    }
}
