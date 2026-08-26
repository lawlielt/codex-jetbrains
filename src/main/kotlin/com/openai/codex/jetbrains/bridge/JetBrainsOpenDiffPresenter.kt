package com.openai.codex.jetbrains.bridge

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import javax.swing.JComponent

/** Editable native Diff viewer; its right-hand document is the only applied content. */
internal class JetBrainsOpenDiffPresenter(
    private val project: Project,
) : OpenDiffPresenter {
    override fun present(proposal: PreparedOpenDiff, complete: (OpenDiffCompletion) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                complete(OpenDiffCompletion.Reject)
                return@invokeLater
            }
            OpenDiffDialog(project, proposal, complete).show()
        }
    }
}

private class OpenDiffDialog(
    private val project: Project,
    proposal: PreparedOpenDiff,
    private val complete: (OpenDiffCompletion) -> Unit,
) : DialogWrapper(project, true) {
    private val proposedDocument = EditorFactory.getInstance().createDocument(proposal.request.content)
    private val request = openDiffRequest(project, proposal, proposedDocument)
    private var decided = false
    private lateinit var panel: DiffRequestPanel

    init {
        title = "Codex proposed source edit"
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
        decide(OpenDiffCompletion.Apply(proposedDocument.text))
        super.doOKAction()
    }

    override fun doCancelAction() {
        decide(OpenDiffCompletion.Reject)
        super.doCancelAction()
    }

    override fun dispose() {
        decide(OpenDiffCompletion.Reject)
        super.dispose()
    }

    private fun decide(decision: OpenDiffCompletion) {
        if (decided) return
        decided = true
        complete(decision)
    }
}

internal fun openDiffRequest(project: Project, proposal: PreparedOpenDiff, proposedDocument: Document): SimpleDiffRequest {
    val factory = DiffContentFactory.getInstance()
    return SimpleDiffRequest(
        "Codex ${proposal.request.operation.wireName} ${proposal.request.newPath}",
        factory.create(project, proposal.currentContent),
        factory.create(project, proposedDocument),
        "Current project content",
        "Proposed content (editable)",
    )
}

/** Uses write commands and VFS/document APIs so Local History and open editors stay consistent. */
internal class JetBrainsOpenDiffWriter(
    private val project: Project,
) : OpenDiffWriter {
    override fun apply(proposal: PreparedOpenDiff, reviewerContent: String): Boolean = runCatching {
        if (project.isDisposed) return false
        var committed = false
        WriteCommandAction.runWriteCommandAction(project, Runnable run@{
            val source = find(proposal.source)
            when (proposal.request.operation) {
                OpenDiffOperation.UPDATE -> {
                    val file = source ?: return@run
                    write(file, reviewerContent)
                    committed = true
                }
                OpenDiffOperation.ADD -> {
                    val parent = find(proposal.target.parent) ?: return@run
                    val file = parent.createChildData(this, proposal.target.fileName.toString())
                    write(file, reviewerContent)
                    committed = true
                }
                OpenDiffOperation.DELETE -> {
                    if (reviewerContent.isNotEmpty()) return@run
                    source?.delete(this)
                    committed = source != null
                }
                OpenDiffOperation.MOVE -> {
                    val file = source ?: return@run
                    val targetParent = find(proposal.target.parent) ?: return@run
                    file.move(this, targetParent)
                    if (file.name != proposal.target.fileName.toString()) file.rename(this, proposal.target.fileName.toString())
                    write(file, reviewerContent)
                    committed = true
                }
            }
        })
        committed
    }.getOrDefault(false)

    private fun find(path: Path?): VirtualFile? = path?.let(LocalFileSystem.getInstance()::findFileByNioFile)

    private fun write(file: VirtualFile, content: String) {
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(file)
        if (document != null) {
            document.setText(content)
            manager.saveDocument(document)
        } else {
            VfsUtil.saveText(file, content)
        }
    }
}
