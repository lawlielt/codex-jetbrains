package com.openai.codex.jetbrains.bridge

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.FlowLayout
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JPanel

/** Opens a built-in Diff editor tab rather than embedding a diff panel in a dialog. */
internal class JetBrainsOpenDiffPresenter(
    private val project: Project,
) : OpenDiffPresenter {
    private val reviews = ConcurrentHashMap.newKeySet<EditorTabOpenDiffReview>()

    override fun present(proposal: PreparedOpenDiff, complete: (OpenDiffCompletion) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                complete(OpenDiffCompletion.Reject)
                return@invokeLater
            }
            lateinit var review: EditorTabOpenDiffReview
            review = EditorTabOpenDiffReview(project, proposal, complete) { reviews.remove(review) }
            reviews += review
            review.show()
        }
    }

    override fun close() {
        reviews.toList().forEach(EditorTabOpenDiffReview::reject)
    }
}

/** Small pure state seam: closing/reopening transitions cannot produce two decisions. */
internal class EditorTabReviewLifecycle {
    private var decided = false

    fun editorOpened(): Boolean = !decided

    fun editorClosed(): Boolean = !decided

    fun decide(): Boolean {
        if (decided) return false
        decided = true
        return true
    }
}

internal fun codexDiffEditorTabTitle(proposal: PreparedOpenDiff): String =
    "[Codex] ${proposal.request.newPath.substringAfterLast('/')}"

/** Deliberately excludes the generic DiffManager/dialog route. */
internal enum class OpenDiffPresentationRoute { BUILTIN_EDITOR_TAB }

internal fun openDiffPresentationRoute(): OpenDiffPresentationRoute = OpenDiffPresentationRoute.BUILTIN_EDITOR_TAB

private class EditorTabOpenDiffReview(
    private val project: Project,
    private val proposal: PreparedOpenDiff,
    private val complete: (OpenDiffCompletion) -> Unit,
    private val finished: () -> Unit,
) {
    private val lifecycle = EditorTabReviewLifecycle()
    private val disposable: Disposable = Disposer.newDisposable("Codex openDiff editor tab")
    private val sourceFile = LocalFileSystem.getInstance().findFileByNioFile(proposal.source)
    private val proposedContent = DiffContentFactory.getInstance().createEditable(
        project,
        proposal.request.content,
        FileTypeManager.getInstance().getFileTypeByFileName(proposal.request.newPath),
    )
    private val request = openDiffRequest(project, proposal, sourceFile, proposedContent.document)
    private val chain = SimpleDiffRequestChain(request)
    private val reviewFile = ChainDiffVirtualFile(chain, codexDiffEditorTabTitle(proposal))
    private var closeFuture: ScheduledFuture<*>? = null

    init {
        installActions()
        project.messageBus.connect(disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val opened = file as? ChainDiffVirtualFile ?: return
                    if (opened.chain !== chain || !lifecycle.editorOpened()) return
                    closeFuture?.cancel(false)
                    closeFuture = null
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val closed = file as? ChainDiffVirtualFile ?: return
                    if (closed.chain !== chain || !lifecycle.editorClosed()) return
                    scheduleCloseAsReject()
                }
            },
        )
    }

    fun show() {
        runCatching {
            when (openDiffPresentationRoute()) {
                OpenDiffPresentationRoute.BUILTIN_EDITOR_TAB ->
                    DiffEditorTabFilesManager.getInstance(project).showDiffFile(reviewFile, true)
            }
        }
            .onFailure { decide(OpenDiffCompletion.Reject) }
    }

    fun reject() {
        ApplicationManager.getApplication().invokeLater { decide(OpenDiffCompletion.Reject) }
    }

    private fun installActions() {
        val reject = object : AnAction("Reject") {
            override fun actionPerformed(event: AnActionEvent) = decide(OpenDiffCompletion.Reject)
        }
        val apply = object : AnAction("Apply") {
            override fun actionPerformed(event: AnActionEvent) = decide(OpenDiffCompletion.Apply(proposedContent.document.text))
        }
        request.putUserData(DiffUserDataKeysEx.CONTEXT_ACTIONS, listOf(reject, apply))

        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(JButton("Reject").apply { addActionListener { decide(OpenDiffCompletion.Reject) } })
            add(JButton("Apply").apply { addActionListener { decide(OpenDiffCompletion.Apply(proposedContent.document.text)) } })
        }
        chain.putUserData(DiffUserDataKeysEx.BOTTOM_PANEL, panel)
    }

    private fun scheduleCloseAsReject() {
        closeFuture?.cancel(false)
        closeFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater { decide(OpenDiffCompletion.Reject) }
        }, CLOSE_REJECT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun decide(completion: OpenDiffCompletion) {
        if (!lifecycle.decide()) return
        closeFuture?.cancel(false)
        Disposer.dispose(disposable)
        try {
            complete(completion)
        } finally {
            closeExactReviewAndRestoreSource()
            finished()
        }
    }

    private fun closeExactReviewAndRestoreSource() {
        if (project.isDisposed) return
        val manager = FileEditorManager.getInstance(project)
        reviewFile.takeIf(manager::isFileOpen)?.let(manager::closeFile)
        val origin = sourceFile?.takeIf(VirtualFile::isValid)
            ?: LocalFileSystem.getInstance().findFileByNioFile(proposal.target)?.takeIf(VirtualFile::isValid)
        origin?.let { manager.openFile(it, true) }
    }

    private companion object {
        const val CLOSE_REJECT_DELAY_MS = 600L
    }
}

/** The left content uses the actual project file; the right is an editable light document. */
internal fun openDiffRequest(
    project: Project,
    proposal: PreparedOpenDiff,
    sourceFile: VirtualFile?,
    proposedDocument: Document,
): SimpleDiffRequest {
    val factory = DiffContentFactory.getInstance()
    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(proposal.request.newPath)
    val original = sourceFile?.let { factory.create(project, it) }
        ?: factory.create(project, "", fileType)
    val request = SimpleDiffRequest(
        codexDiffEditorTabTitle(proposal),
        original,
        factory.create(project, proposedDocument, fileType),
        if (sourceFile == null) "New" else "Original: ${proposal.request.oldPath}",
        "Proposed",
    )
    request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, false))
    request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
    request.putUserData(DiffUserDataKeysEx.FILE_NAME, codexDiffEditorTabTitle(proposal))
    return request
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
