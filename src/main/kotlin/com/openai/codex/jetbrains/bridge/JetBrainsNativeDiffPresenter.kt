package com.openai.codex.jetbrains.bridge

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent

/** A read-only native diff dialog. Its Apply/Reject buttons only answer Codex. */
internal class JetBrainsNativeDiffPresenter(
    private val project: Project,
) : NativeDiffPresenter {
    override fun present(proposal: PreparedProposal, complete: (ApprovalDecision) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                complete(ApprovalDecision.DECLINE)
                return@invokeLater
            }
            ApprovalDiffDialog(project, proposal, complete).show()
        }
    }
}

private class ApprovalDiffDialog(
    private val project: Project,
    proposal: PreparedProposal,
    private val complete: (ApprovalDecision) -> Unit,
) : DialogWrapper(project, true) {
    private val request = previewRequest(project, proposal)
    private var decided = false
    private lateinit var panel: DiffRequestPanel

    init {
        title = "Codex proposed file changes"
        setOKButtonText("Apply")
        setCancelButtonText("Reject")
        init()
    }

    override fun createCenterPanel(): JComponent {
        panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        panel.setRequest(request)
        return panel.component
    }

    override fun doOKAction() {
        decide(ApprovalDecision.ACCEPT)
        super.doOKAction()
    }

    override fun doCancelAction() {
        decide(ApprovalDecision.DECLINE)
        super.doCancelAction()
    }

    override fun dispose() {
        decide(ApprovalDecision.DECLINE)
        super.dispose()
    }

    private fun decide(decision: ApprovalDecision) {
        if (decided) return
        decided = true
        complete(decision)
    }
}

internal fun previewRequest(project: Project, proposal: PreparedProposal): SimpleDiffRequest {
    val (before, after) = proposalPreview(proposal)
    val factory = DiffContentFactory.getInstance()
    return SimpleDiffRequest(
        "Codex proposed ${proposal.files.size} file change(s)",
        factory.create(project, before),
        factory.create(project, after),
        "Current project", "Codex proposal",
    )
}

internal fun proposalPreview(proposal: PreparedProposal): Pair<String, String> =
    proposal.files.joinToString("\n") { file -> "===== ${file.path} =====\n${file.before}" } to
        proposal.files.joinToString("\n") { file -> "===== ${file.movePath ?: file.path} =====\n${file.after}" }
