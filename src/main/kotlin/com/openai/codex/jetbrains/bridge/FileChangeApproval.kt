package com.openai.codex.jetbrains.bridge

import java.nio.file.Files
import java.nio.file.Path

internal enum class ApprovalDecision { ACCEPT, DECLINE }

internal data class FileChange(
    val path: String,
    val kind: String,
    val diff: String,
    val movePath: String? = null,
)

internal data class FileChangeProposal(
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val changes: List<FileChange>,
)

internal data class PreparedFileChange(
    val path: String,
    val movePath: String?,
    val before: String,
    val after: String,
)

internal data class PreparedProposal(
    val proposal: FileChangeProposal,
    val files: List<PreparedFileChange>,
)

internal interface FileSnapshotStore {
    fun read(path: Path): String?
    fun hasUnsavedDocument(path: Path): Boolean
}

internal class NioFileSnapshotStore : FileSnapshotStore {
    override fun read(path: Path): String? = if (Files.isRegularFile(path)) Files.readString(path) else null
    override fun hasUnsavedDocument(path: Path): Boolean = false
}

/** Validates all files before a native approval dialog can be opened. */
internal class FileChangeValidator(
    private val projectRoot: Path,
    private val snapshots: FileSnapshotStore,
) {
    fun prepare(proposal: FileChangeProposal): PreparedProposal? {
        if (proposal.changes.isEmpty()) return null
        val files = proposal.changes.map { change -> prepare(change) ?: return null }
        return PreparedProposal(proposal, files)
    }

    private fun prepare(change: FileChange): PreparedFileChange? {
        if (change.kind !in setOf("add", "update", "delete", "move")) return null
        val source = resolve(change.path) ?: return null
        val target = change.movePath?.let(::resolve) ?: source
        if (snapshots.hasUnsavedDocument(source) || snapshots.hasUnsavedDocument(target)) return null
        val before = when (change.kind) {
            "add" -> if (snapshots.read(source) == null) "" else return null
            else -> snapshots.read(source) ?: return null
        }
        if (change.movePath != null && target != source && snapshots.read(target) != null) return null
        val patched = UnifiedPatch.parse(change.diff)?.applyTo(before) ?: return null
        val after = when (change.kind) {
            "delete" -> if (patched.isEmpty()) "" else return null
            else -> patched
        }
        return PreparedFileChange(change.path, change.movePath, before, after)
    }

    private fun resolve(value: String): Path? = runCatching {
        if (value.isBlank()) return null
        val relative = Path.of(value)
        if (relative.isAbsolute || relative.any { it.toString() == ".." }) return null
        val root = projectRoot.toAbsolutePath().normalize()
        val candidate = root.resolve(relative).normalize().takeIf { it.startsWith(root) } ?: return null
        if (!Files.exists(root)) return candidate
        val realRoot = root.toRealPath()
        val existingAncestor = generateSequence(candidate) { it.parent }
            .firstOrNull { Files.exists(it) } ?: return null
        if (!existingAncestor.toRealPath().startsWith(realRoot)) return null
        candidate
    }.getOrNull()
}
