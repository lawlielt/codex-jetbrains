package com.openai.codex.jetbrains.terminal

import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface CodexTerminalLauncher {
    fun open(projectRoot: Path)
}

internal fun interface CodexTerminalSessionFactory {
    fun create(workingDirectory: Path, tabName: String): CodexTerminalSession
}

internal interface CodexTerminalSession {
    val isOpen: Boolean

    fun focus()

    fun commandState(): TerminalCommandState

    fun sendCommand(command: String)
}

internal enum class TerminalCommandState {
    RUNNING,
    IDLE,
    UNKNOWN,
}

/**
 * Project-scoped state for a dedicated Codex terminal tab.
 *
 * The command deliberately stays as plain `codex`. Resolution, Node startup,
 * authentication, and all interaction belong to the user's JetBrains terminal
 * shell rather than the IDE JVM.
 */
internal class CodexTerminalController(
    private val sessionFactory: CodexTerminalSessionFactory,
    private val nanoTime: () -> Long = System::nanoTime,
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

    private fun shouldSubmit(record: SessionRecord): Boolean {
        val submittedAt = record.commandSubmittedAt ?: return true
        if (nanoTime() - submittedAt < RELAUNCH_GRACE_NANOS) return false
        return record.session.commandState() == TerminalCommandState.IDLE
    }

    private fun submit(record: SessionRecord) {
        record.session.sendCommand(CODEX_COMMAND)
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
