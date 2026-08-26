package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class OpenDiffCoordinatorTest {
    @Test
    fun `parses only the strict stable openDiff schema`() {
        val request = OpenDiffToolProtocol.parse(toolParams()) ?: error("openDiff request")

        assertNotNull(request)
        assertEquals(OpenDiffOperation.UPDATE, request.operation)
        assertNull(OpenDiffToolProtocol.parse(toolParams(extra = "unexpected")))
        assertNull(OpenDiffToolProtocol.parse(toolParams(operation = "unknown")))
        assertNull(OpenDiffToolProtocol.parse(toolParams(content = null)))
    }

    @Test
    fun `validates update add delete and move without accepting stale or dirty state`() {
        val root = createTempDirectory("open-diff-validator")
        val old = root.resolve("old.txt")
        Files.writeString(old, "before\n")
        val snapshots = Snapshots(root)
        val validator = OpenDiffValidator(root, snapshots)

        assertEquals("before\n", validator.prepare(request(OpenDiffOperation.UPDATE, "old.txt", "old.txt", "after\n", "before\n"))?.currentContent)
        assertEquals("", validator.prepare(request(OpenDiffOperation.ADD, "new.txt", "new.txt", "created\n", ""))?.currentContent)
        assertEquals("before\n", validator.prepare(request(OpenDiffOperation.DELETE, "old.txt", "old.txt", "", "before\n"))?.currentContent)
        assertEquals("before\n", validator.prepare(request(OpenDiffOperation.MOVE, "old.txt", "moved.txt", "moved\n", "before\n"))?.currentContent)
        assertNull(validator.prepare(request(OpenDiffOperation.UPDATE, "old.txt", "old.txt", "after\n", "stale\n")))
        snapshots.dirty.add(old)
        assertNull(validator.prepare(request(OpenDiffOperation.UPDATE, "old.txt", "old.txt", "after\n", "before\n")))
    }

    @Test
    fun `fails closed for traversal and symlink escape`() {
        val root = createTempDirectory("open-diff-paths")
        val outside = createTempDirectory("open-diff-outside")
        val linked = root.resolve("linked")
        Files.createSymbolicLink(linked, outside)
        val validator = OpenDiffValidator(root, Snapshots(root))

        assertNull(validator.prepare(request(OpenDiffOperation.ADD, "../outside.txt", "../outside.txt", "x", "")))
        assertNull(validator.prepare(request(OpenDiffOperation.ADD, "linked/escape.txt", "linked/escape.txt", "x", "")))
    }

    @Test
    fun `applies exactly reviewer edited content once after the IDE callback`() {
        val root = createTempDirectory("open-diff-apply")
        val file = root.resolve("source.txt")
        Files.writeString(file, "before\n")
        val snapshots = Snapshots(root)
        val presenter = DeferredPresenter()
        var writeCount = 0
        var written: String? = null
        val coordinator = OpenDiffCoordinator(
            OpenDiffValidator(root, snapshots),
            presenter,
            OpenDiffWriter { _, content -> writeCount += 1; written = content; true },
        )
        val responses = mutableListOf<Pair<Boolean, String>>()

        coordinator.toolRequested(JsonPrimitive(7), toolParams(), { success, detail -> responses += success to detail })
        assertEquals(0, writeCount)
        presenter.complete(OpenDiffCompletion.Apply("reviewer-edited\n"))
        presenter.complete(OpenDiffCompletion.Reject)

        assertEquals(1, writeCount)
        assertEquals("reviewer-edited\n", written)
        assertEquals(1, responses.size)
        assertTrue(responses.single().first)
    }

    @Test
    fun `reject close stale disposal and duplicate ids never write or reply twice`() {
        val root = createTempDirectory("open-diff-reject")
        val file = root.resolve("source.txt")
        Files.writeString(file, "before\n")
        val snapshots = Snapshots(root)
        val presenter = DeferredPresenter()
        var writes = 0
        val coordinator = OpenDiffCoordinator(
            OpenDiffValidator(root, snapshots),
            presenter,
            OpenDiffWriter { _, _ -> writes += 1; true },
        )
        val responses = mutableListOf<Boolean>()

        coordinator.toolRequested(JsonPrimitive("same"), toolParams(), { success, _ -> responses += success })
        coordinator.toolRequested(JsonPrimitive("same"), toolParams(), { success, _ -> responses += success })
        coordinator.toolRequested(JsonPrimitive("same-call-new-request"), toolParams(), { success, _ -> responses += success })
        presenter.complete(OpenDiffCompletion.Reject)
        presenter.complete(OpenDiffCompletion.Apply("ignored"))
        assertEquals(listOf(false, false), responses)
        assertEquals(0, writes)

        val stalePresenter = DeferredPresenter()
        val stale = OpenDiffCoordinator(OpenDiffValidator(root, snapshots), stalePresenter, OpenDiffWriter { _, _ -> writes += 1; true })
        stale.toolRequested(JsonPrimitive("stale"), toolParams(), { success, _ -> responses += success })
        Files.writeString(file, "changed\n")
        stalePresenter.complete(OpenDiffCompletion.Apply("reviewer"))
        assertFalse(responses.last())
        assertEquals(0, writes)

        val closingPresenter = DeferredPresenter()
        val closing = OpenDiffCoordinator(OpenDiffValidator(root, snapshots), closingPresenter, OpenDiffWriter { _, _ -> writes += 1; true })
        Files.writeString(file, "before\n")
        closing.toolRequested(JsonPrimitive("close"), toolParams(), { success, _ -> responses += success })
        closing.close()
        closingPresenter.complete(OpenDiffCompletion.Apply("ignored"))
        assertFalse(responses.last())
        assertEquals(0, writes)
    }

    private fun request(
        operation: OpenDiffOperation,
        oldPath: String,
        newPath: String,
        content: String,
        preimage: String,
    ) = OpenDiffRequest("call", "thread", "turn", operation, oldPath, newPath, content, preimage)

    private fun toolParams(
        operation: String = "update",
        content: String? = "after\n",
        extra: String? = null,
    ): JsonObject {
        val arguments = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "operation" to JsonPrimitive(operation),
            "oldPath" to JsonPrimitive("source.txt"),
            "newPath" to JsonPrimitive("source.txt"),
            "preimage" to JsonPrimitive("before\n"),
        )
        content?.let { arguments["content"] = JsonPrimitive(it) }
        extra?.let { arguments[it] = JsonPrimitive(true) }
        return JsonObject(
            mapOf(
                "tool" to JsonPrimitive("openDiff"),
                "callId" to JsonPrimitive("call"),
                "threadId" to JsonPrimitive("thread"),
                "turnId" to JsonPrimitive("turn"),
                "arguments" to JsonObject(arguments),
            ),
        )
    }

    private class Snapshots(private val root: Path) : OpenDiffSnapshotStore {
        val dirty = mutableSetOf<Path>()

        override fun read(path: Path): String? = path.takeIf(Files::isRegularFile)?.let(Files::readString)

        override fun hasUnsavedDocument(path: Path): Boolean = path in dirty
    }

    private class DeferredPresenter : OpenDiffPresenter {
        private var callback: ((OpenDiffCompletion) -> Unit)? = null

        override fun present(proposal: PreparedOpenDiff, complete: (OpenDiffCompletion) -> Unit) {
            callback = complete
        }

        fun complete(completion: OpenDiffCompletion) = callback?.invoke(completion)
    }
}
