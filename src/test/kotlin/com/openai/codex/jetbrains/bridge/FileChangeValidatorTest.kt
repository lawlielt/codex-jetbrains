package com.openai.codex.jetbrains.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class FileChangeValidatorTest {
    private val root = Path.of("project").toAbsolutePath().normalize()
    private val snapshots = FakeSnapshots(mutableMapOf(root.resolve("file.txt") to "before\n"))
    private val validator = FileChangeValidator(root, snapshots)

    @Test
    fun `prepares all files atomically`() {
        val proposal = proposal(
            FileChange("file.txt", "update", "@@ -1,1 +1,1 @@\n-before\n+after"),
            FileChange("new.txt", "add", "@@ -0,0 +1,1 @@\n+new"),
        )

        val prepared = validator.prepare(proposal) ?: error("prepared")
        assertEquals(listOf("after\n", "new\n"), prepared.files.map { it.after })
        assertEquals("before\n", snapshots.files.getValue(root.resolve("file.txt")))
    }

    @Test
    fun `fails closed for dirty stale and outside-root changes`() {
        snapshots.dirty.add(root.resolve("file.txt"))
        assertNull(validator.prepare(proposal(FileChange("file.txt", "update", "@@ -1 +1 @@\n-before\n+after"))))
        snapshots.dirty.clear()
        assertNull(validator.prepare(proposal(FileChange("file.txt", "update", "@@ -1 +1 @@\n-stale\n+after"))))
        assertNull(validator.prepare(proposal(FileChange("../outside.txt", "add", "@@ -0,0 +1 @@\n+x"))))
    }

    @Test
    fun `fails the entire proposal when one file is malformed`() {
        assertNull(validator.prepare(proposal(
            FileChange("file.txt", "update", "@@ -1 +1 @@\n-before\n+after"),
            FileChange("bad.txt", "add", "malformed"),
        )))
    }

    @Test
    fun `accepts the protocol move path on an update without writing it`() {
        val prepared = validator.prepare(proposal(FileChange(
            "file.txt", "update", "@@ -1 +1 @@\n-before\n+after", movePath = "renamed.txt",
        ))) ?: error("move")

        assertEquals("renamed.txt", prepared.files.single().movePath)
        assertEquals("before\n", snapshots.files.getValue(root.resolve("file.txt")))
    }

    @Test
    fun `validates delete hunks instead of trusting the kind`() {
        val valid = validator.prepare(proposal(FileChange("file.txt", "delete", "@@ -1,1 +0,0 @@\n-before")))
        val invalid = validator.prepare(proposal(FileChange("file.txt", "delete", "@@ -1,1 +1,1 @@\n-before\n+after")))

        assertEquals("", valid?.files?.single()?.after)
        assertNull(invalid)
    }

    private fun proposal(vararg changes: FileChange) = FileChangeProposal("thread", "turn", "item", changes.toList())

    private class FakeSnapshots(val files: MutableMap<Path, String>) : FileSnapshotStore {
        val dirty = mutableSetOf<Path>()
        override fun read(path: Path): String? = files[path]
        override fun hasUnsavedDocument(path: Path): Boolean = path in dirty
    }
}
