package com.openai.codex.jetbrains.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.openai.codex.jetbrains.terminal.CodexTerminalLauncher
import java.nio.file.Paths

class OpenCodexTerminalAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val projectRoot = project.basePath?.let(Paths::get)
        if (projectRoot == null) {
            notify(project, "Open a local project, then try Codex again.")
            return
        }

        val launcher = project.getService(CodexTerminalLauncher::class.java)
        if (launcher == null) {
            notify(project, "JetBrains Terminal is unavailable. Enable the bundled Terminal plugin, then try Codex again.")
            return
        }

        runCatching { launcher.open(projectRoot) }
            .onFailure {
                notify(project, "Codex could not start in JetBrains Terminal. Open the Codex tab and run 'codex'.")
            }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Codex")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }
}
