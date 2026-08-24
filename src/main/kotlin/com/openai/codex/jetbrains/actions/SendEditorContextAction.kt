package com.openai.codex.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.openai.codex.jetbrains.context.EditorContextCollector
import com.openai.codex.jetbrains.service.CodexProjectService

class SendEditorContextAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val context = EditorContextCollector.collect(project) ?: return
        CodexProjectService.getInstance(project).stageContext(context)
        focusCodexToolWindow(project)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null && event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
