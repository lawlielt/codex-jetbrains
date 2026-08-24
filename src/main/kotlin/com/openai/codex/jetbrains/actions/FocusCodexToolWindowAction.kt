package com.openai.codex.jetbrains.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class FocusCodexToolWindowAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let(::focusCodexToolWindow)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
}

internal fun focusCodexToolWindow(project: com.intellij.openapi.project.Project) {
    ToolWindowManager.getInstance(project).getToolWindow("Codex")?.show()
}
