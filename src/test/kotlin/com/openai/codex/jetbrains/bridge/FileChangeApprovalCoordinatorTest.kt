package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class FileChangeApprovalCoordinatorTest {
    @Test
    fun `accepts exactly once only after the native decision`() {
        val presenter = CapturingPresenter()
        val coordinator = coordinator(presenter)
        coordinator.itemStarted(started())
        val responses = mutableListOf<ApprovalDecision>()

        coordinator.approvalRequested(Json.parseToJsonElement("7"), approval()) { responses += it }
        coordinator.approvalRequested(Json.parseToJsonElement("7"), approval()) { responses += it }
        assertTrue(responses.isEmpty())

        presenter.complete(ApprovalDecision.ACCEPT)
        presenter.complete(ApprovalDecision.DECLINE)
        assertEquals(listOf(ApprovalDecision.ACCEPT), responses)
    }

    @Test
    fun `declines unknown malformed and pending requests on close`() {
        val presenter = CapturingPresenter()
        val coordinator = coordinator(presenter)
        val responses = mutableListOf<ApprovalDecision>()
        coordinator.approvalRequested(Json.parseToJsonElement("1"), approval()) { responses += it }
        assertEquals(listOf(ApprovalDecision.DECLINE), responses)

        coordinator.itemStarted(started())
        coordinator.approvalRequested(Json.parseToJsonElement("2"), approval()) { responses += it }
        coordinator.close()
        presenter.complete(ApprovalDecision.ACCEPT)
        assertEquals(listOf(ApprovalDecision.DECLINE, ApprovalDecision.DECLINE), responses)
    }

    private fun coordinator(presenter: CapturingPresenter): FileChangeApprovalCoordinator = FileChangeApprovalCoordinator(
        FileChangeValidator(Path.of("project").toAbsolutePath().normalize(), object : FileSnapshotStore {
            override fun read(path: Path): String? = if (path == Path.of("project").toAbsolutePath().normalize().resolve("file.txt")) "before\n" else null
            override fun hasUnsavedDocument(path: Path): Boolean = false
        }),
        presenter,
    )

    private fun started(): JsonObject = Json.parseToJsonElement(
        """{"threadId":"thread","turnId":"turn","item":{"id":"item","type":"fileChange","changes":[{"path":"file.txt","kind":"update","diff":"@@ -1,1 +1,1 @@\n-before\n+after"}]}}""",
    ).jsonObject

    private fun approval(): JsonObject = Json.parseToJsonElement(
        """{"threadId":"thread","turnId":"turn","itemId":"item"}""",
    ).jsonObject

    private class CapturingPresenter : NativeDiffPresenter {
        private var callback: ((ApprovalDecision) -> Unit)? = null
        override fun present(proposal: PreparedProposal, complete: (ApprovalDecision) -> Unit) { callback = complete }
        fun complete(decision: ApprovalDecision) { callback?.invoke(decision) }
    }
}
