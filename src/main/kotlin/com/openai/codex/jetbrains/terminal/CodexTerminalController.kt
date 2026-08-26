package com.openai.codex.jetbrains.terminal

import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface CodexTerminalLauncher {
    fun open(projectRoot: Path)

    fun hasLiveSession(projectRoot: Path): Boolean

    fun stage(projectRoot: Path, text: String): Boolean
}

internal fun interface CodexTerminalSessionFactory {
    fun create(workingDirectory: Path, tabName: String): CodexTerminalSession
}

internal interface CodexTerminalSession {
    val isOpen: Boolean

    fun focus()

    fun commandState(): TerminalCommandState

    fun sendCommand(command: String)

    fun stageText(text: String): Boolean
}

internal enum class TerminalCommandState {
    RUNNING,
    IDLE,
    UNKNOWN,
}

/**
 * Project-scoped state for a dedicated Codex terminal tab.
 *
 * Command construction remains terminal-owned. The optional native bridge
 * supplies a shell command that probes the installed CLI and falls back to
 * literal `codex`; executable resolution and interaction never move to the JVM.
 */
internal class CodexTerminalController(
    private val sessionFactory: CodexTerminalSessionFactory,
    private val nanoTime: () -> Long = System::nanoTime,
    private val launchCommand: (Path) -> String = { CODEX_COMMAND },
) {
    private var current: SessionRecord? = null

    @Synchronized
    fun open(projectRoot: Path) {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val existing = current?.takeIf { it.projectRoot == normalizedRoot && it.session.isOpen }
        val record = existing ?: SessionRecord(
            projectRoot = normalizedRoot,
            session = sessionFactory.create(normalizedRoot, CODEX_TAB_NAME),
        ).also { current = it }

        record.session.focus()
        if (shouldSubmit(record)) submit(record)
    }

    @Synchronized
    fun hasLiveSession(projectRoot: Path): Boolean = liveSession(projectRoot) != null

    /**
     * Focuses the existing running Codex session and writes literal composer text.
     * This path never creates a terminal and rejects line breaks defensively.
     */
    @Synchronized
    fun stage(projectRoot: Path, text: String): Boolean {
        if (text.isEmpty() || text.any { it == '\n' || it == '\r' }) return false
        val session = liveSession(projectRoot) ?: return false

        session.focus()
        return session.stageText(text)
    }

    private fun liveSession(projectRoot: Path): CodexTerminalSession? {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        return current
            ?.takeIf { it.projectRoot == normalizedRoot }
            ?.session
            ?.takeIf { it.isOpen && it.commandState() == TerminalCommandState.RUNNING }
    }

    private fun shouldSubmit(record: SessionRecord): Boolean {
        val submittedAt = record.commandSubmittedAt ?: return true
        if (nanoTime() - submittedAt < RELAUNCH_GRACE_NANOS) return false
        return record.session.commandState() == TerminalCommandState.IDLE
    }

    private fun submit(record: SessionRecord) {
        record.session.sendCommand(launchCommand(record.projectRoot))
        record.commandSubmittedAt = nanoTime()
    }

    private data class SessionRecord(
        val projectRoot: Path,
        val session: CodexTerminalSession,
        var commandSubmittedAt: Long? = null,
    )

    internal companion object {
        const val CODEX_COMMAND = "codex"
        const val CODEX_TAB_NAME = "Codex"
        val RELAUNCH_GRACE_NANOS: Long = TimeUnit.SECONDS.toNanos(3)
    }
}
