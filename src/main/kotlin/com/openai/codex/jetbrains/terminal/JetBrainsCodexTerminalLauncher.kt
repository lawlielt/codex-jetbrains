package com.openai.codex.jetbrains.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Loaded only through the optional Terminal plugin descriptor. */
class JetBrainsCodexTerminalLauncher(project: Project) : CodexTerminalLauncher, Disposable {
    private val controller = CodexTerminalController(JetBrainsTerminalSessionFactory(project))

    override fun open(projectRoot: Path) {
        controller.open(projectRoot)
    }

    override fun dispose() = Unit
}

private class JetBrainsTerminalSessionFactory(
    private val project: Project,
) : CodexTerminalSessionFactory {
    override fun create(workingDirectory: Path, tabName: String): CodexTerminalSession {
        val manager = TerminalToolWindowManager.getInstance(project)
        val widget = manager.createShellWidget(
            workingDirectory.toString(),
            tabName,
            true,
            false,
        )
        return JetBrainsTerminalSession(manager, widget, project)
    }
}

private class JetBrainsTerminalSession(
    private val manager: TerminalToolWindowManager,
    private val widget: TerminalWidget,
    parentDisposable: Disposable,
) : CodexTerminalSession {
    private val terminated = AtomicBoolean(false)

    init {
        widget.addTerminationCallback({ terminated.set(true) }, parentDisposable)
    }

    override val isOpen: Boolean
        get() = !terminated.get() && widget.ttyConnector?.isConnected != false

    override fun focus() {
        manager.toolWindow.show {
            manager.getContainer(widget)?.content?.let { content ->
                manager.toolWindow.contentManager.setSelectedContent(content, true)
            }
            widget.requestFocus()
        }
    }

    override fun commandState(): TerminalCommandState = runCatching {
        val shellWidget = ShellTerminalWidget.asShellJediTermWidget(widget)
            ?: return@runCatching TerminalCommandState.UNKNOWN
        if (shellWidget.hasRunningCommands()) TerminalCommandState.RUNNING else TerminalCommandState.IDLE
    }.getOrDefault(TerminalCommandState.UNKNOWN)

    override fun sendCommand(command: String) {
        widget.sendCommandToExecute(command)
    }
}
