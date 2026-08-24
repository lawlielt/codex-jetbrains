package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ApprovalRouterTest {
    @Test
    fun `routes command network and file requests by exact scope`() {
        val router = ApprovalRouter()
        val command = router.route(request(1, "item/commandExecution/requestApproval"))
        assertEquals(ApprovalKind.COMMAND, command?.kind)
        assertEquals(ApprovalScope("thread-1", "turn-1", "item-1"), command?.scope)

        val networkParams = params().apply { add("networkApprovalContext", JsonObject().apply { addProperty("host", "example.com") }) }
        val network = router.route(InboundMessage.Request(JsonPrimitive(2), "item/commandExecution/requestApproval", networkParams))
        assertEquals(ApprovalKind.NETWORK, network?.kind)

        val changes = listOf(ProposedFileChange("src/App.kt", "update", "@@ -1 +1 @@"))
        val file = router.route(request(3, "item/fileChange/requestApproval"), mapOf("item-1" to changes))
        assertNotNull(file)
        assertEquals(changes, file!!.proposedChanges)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses response whose thread turn item scope was altered`() {
        val router = ApprovalRouter()
        val original = router.route(request(4, "item/fileChange/requestApproval"))!!
        val tampered = original.copy(scope = original.scope.copy(turnId = "other-turn"))
        router.complete(tampered, ApprovalRouter.decision("accept"))
    }

    private fun request(id: Int, method: String) =
        InboundMessage.Request(JsonPrimitive(id), method, params())

    private fun params() = JsonObject().apply {
        addProperty("threadId", "thread-1")
        addProperty("turnId", "turn-1")
        addProperty("itemId", "item-1")
    }
}
