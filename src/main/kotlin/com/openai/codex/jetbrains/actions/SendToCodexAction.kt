package com.openai.codex.jetbrains.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.openai.codex.jetbrains.terminal.CodexTerminalLauncher
import java.nio.file.Path

class SendToCodexAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val context = context(event)
        val hasLiveSession = context?.launcher?.let { launcher ->
            runCatching { launcher.hasLiveSession(context.projectRoot) }.getOrDefault(false)
        } == true
        updateSendToCodexPresentation(
            presentation = event.presentation,
            hasEditorReference = context != null,
            hasLiveSession = hasLiveSession,
        )
        if (event.presentation.isEnabled) {
            event.presentation.description = "Stage the current file or selection in the running Codex CLI"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val context = context(event) ?: return
        val staged = runCatching {
            context.launcher?.stage(context.projectRoot, "${context.reference} ") == true
        }.getOrDefault(false)
        if (!staged) {
            notify(context.project, "Start or return to a running Codex CLI session, then try again.")
        }
    }

    private fun context(event: AnActionEvent): EditorContext? = runCatching {
        contextOrNull(event)
    }.getOrNull()

    private fun contextOrNull(event: AnActionEvent): EditorContext? {
        val project = event.project ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileDocumentManager.getInstance().getFile(editor.document)
            ?: return null
        val projectRoot = project.basePath?.let(Path::of) ?: return null
        val selection = editor.selectionModel
        val reference = CodexEditorReference.create(
            projectRoot = projectRoot,
            file = Path.of(virtualFile.path),
            documentText = editor.document.immutableCharSequence,
            selectionStart = selection.selectionStart.takeIf { selection.hasSelection() },
            selectionEnd = selection.selectionEnd.takeIf { selection.hasSelection() },
        ) ?: return null
        return EditorContext(
            project = project,
            projectRoot = projectRoot,
            reference = reference,
            launcher = project.getService(CodexTerminalLauncher::class.java),
        )
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Codex")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    private data class EditorContext(
        val project: Project,
        val projectRoot: Path,
        val reference: String,
        val launcher: CodexTerminalLauncher?,
    )
}

internal fun updateSendToCodexPresentation(
    presentation: Presentation,
    hasEditorReference: Boolean,
    hasLiveSession: Boolean,
) {
    val available = hasEditorReference && hasLiveSession
    presentation.isEnabledAndVisible = available
}
