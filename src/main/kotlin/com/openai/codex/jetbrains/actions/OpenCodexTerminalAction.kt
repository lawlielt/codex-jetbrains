package com.openai.codex.jetbrains.actions

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.openai.codex.jetbrains.settings.CodexSettings
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Paths

class OpenCodexTerminalAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = project.basePath ?: return notify(project, "The project has no local root to open in Terminal.")
        val executable = CodexSettings.getInstance().executablePath
        if (!isExecutableAvailable(executable)) {
            notify(
                project,
                "Codex executable '$executable' was not found. Install the Codex CLI or set its path under Settings | Tools | Codex.",
            )
            return
        }
        val terminalPlugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.terminal"))
        if (terminalPlugin == null || !terminalPlugin.isEnabled) {
            notify(project, "The bundled Terminal plugin is unavailable. Enable it, then retry Open Codex CLI in Terminal.")
            return
        }
        runCatching { openTerminal(project, root, shellQuote(executable)) }
            .onFailure {
                notify(
                    project,
                    "Could not create a Terminal session automatically. Open Terminal at '$root' and run '$executable'.",
                )
            }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.basePath != null
    }

    private fun openTerminal(project: Project, root: String, command: String) {
        val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        val factory = managerClass.methods.firstOrNull {
            it.name in setOf("createShellWidget", "createLocalShellWidget") && it.parameterTypes.isNotEmpty()
        } ?: throw NoSuchMethodException("No supported Terminal widget factory")
        val widget = factory.invoke(manager, *argumentsFor(factory, root))
            ?: throw IllegalStateException("Terminal did not return a widget")
        val execute = widget.javaClass.methods.firstOrNull {
            it.name in setOf("sendCommandToExecute", "executeCommand") && it.parameterCount == 1
        } ?: throw NoSuchMethodException("No supported Terminal command method")
        execute.invoke(widget, command)
    }

    private fun argumentsFor(method: Method, root: String): Array<Any?> {
        var stringIndex = 0
        return method.parameterTypes.map { type ->
            when {
                type == String::class.java -> if (stringIndex++ == 0) root else "Codex"
                type == java.nio.file.Path::class.java -> Paths.get(root)
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> true
                type.isEnum -> type.enumConstants.firstOrNull()
                else -> null
            }
        }.toTypedArray()
    }

    private fun isExecutableAvailable(executable: String): Boolean {
        if (executable.contains(File.separator)) {
            val path = Paths.get(executable)
            return Files.isRegularFile(path) && Files.isExecutable(path)
        }
        return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { directory ->
            val path = Paths.get(directory, executable)
            Files.isRegularFile(path) && Files.isExecutable(path)
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Codex")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }
}
