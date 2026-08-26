package com.openai.codex.jetbrains.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.concurrency.AppExecutorUtil
import com.openai.codex.jetbrains.bridge.BridgeSessionBundle
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Loaded only through the optional Terminal plugin descriptor. */
class JetBrainsCodexTerminalLauncher(project: Project) : CodexTerminalLauncher, Disposable {
    private var bridge: BridgeSessionBundle? = null
    private val controller = CodexTerminalController(
        JetBrainsTerminalSessionFactory(project),
        launchCommand = { root -> prepareBridge(project, root) },
    )

    override fun open(projectRoot: Path) {
        controller.open(projectRoot)
    }

    override fun hasLiveSession(projectRoot: Path): Boolean = controller.hasLiveSession(projectRoot)

    override fun stage(projectRoot: Path, text: String): Boolean = controller.stage(projectRoot, text)

    @Synchronized
    private fun prepareBridge(project: Project, root: Path): String {
        bridge?.dispose()
        bridge = runCatching { BridgeSessionBundle.create(project, root) }.getOrNull()
        return bridge?.launchCommand() ?: CodexTerminalController.CODEX_COMMAND
    }

    override fun dispose() {
        bridge?.dispose()
        bridge = null
    }
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
    private val commandState = TerminalCommandStateCache()
    private val commandStatePoll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
        {
            if (terminated.get()) {
                commandState.markUnknown()
            } else {
                commandState.refresh {
                    val shellWidget = ShellTerminalWidget.asShellJediTermWidget(widget)
                        ?: return@refresh TerminalCommandState.UNKNOWN
                    if (shellWidget.hasRunningCommands()) TerminalCommandState.RUNNING else TerminalCommandState.IDLE
                }
            }
        },
        0,
        COMMAND_STATE_POLL_MILLIS,
        TimeUnit.MILLISECONDS,
    )

    init {
        widget.addTerminationCallback(
            {
                terminated.set(true)
                commandState.markUnknown()
                commandStatePoll.cancel(false)
            },
            parentDisposable,
        )
        Disposer.register(parentDisposable) { commandStatePoll.cancel(true) }
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

    /** Action update/perform paths only read this cache; the Terminal probe runs outside their read actions. */
    override fun commandState(): TerminalCommandState = commandState.current()

    override fun sendCommand(command: String) {
        widget.sendCommandToExecute(command)
        commandState.markRunning()
    }

    override fun stageText(text: String): Boolean {
        val connector = widget.ttyConnector?.takeIf { it.isConnected } ?: return false
        return runCatching {
            connector.write(text)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val COMMAND_STATE_POLL_MILLIS = 250L
    }
}

/**
 * Separates action-system reads from Terminal's background/no-read-action probe.
 * A failed probe is deliberately UNKNOWN so editor context is never staged into an idle shell.
 */
internal class TerminalCommandStateCache(initial: TerminalCommandState = TerminalCommandState.UNKNOWN) {
    private val value = AtomicReference(initial)

    fun current(): TerminalCommandState = value.get()

    fun refresh(probe: () -> TerminalCommandState) {
        value.set(runCatching(probe).getOrDefault(TerminalCommandState.UNKNOWN))
    }

    fun markRunning() {
        value.set(TerminalCommandState.RUNNING)
    }

    fun markUnknown() {
        value.set(TerminalCommandState.UNKNOWN)
    }
}
