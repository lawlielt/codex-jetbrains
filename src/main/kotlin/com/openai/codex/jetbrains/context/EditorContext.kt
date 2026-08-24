package com.openai.codex.jetbrains.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import kotlin.math.max
import kotlin.math.min

data class EditorDiagnostic(
    val line: Int,
    val severity: String,
    val message: String,
)

data class EditorContextSnapshot(
    val relativePath: String,
    val startLine: Int,
    val endLine: Int,
    val selectedText: String?,
    val diagnostics: List<EditorDiagnostic>,
) {
    val reference: String get() = "@$relativePath#L$startLine-L$endLine"
}

data class ContextBounds(
    val maxSelectionChars: Int = 12_000,
    val maxDiagnostics: Int = 20,
    val maxDiagnosticChars: Int = 500,
    val maxTotalChars: Int = 16_000,
)

object EditorContextFormatter {
    fun format(snapshot: EditorContextSnapshot?, bounds: ContextBounds = ContextBounds()): String {
        if (snapshot == null) return ""
        val text = StringBuilder("IDE context (bounded):\n${snapshot.reference}\n")
        snapshot.selectedText?.takeIf { it.isNotBlank() }?.let { selected ->
            text.append("Selected text:\n")
            text.append(selected.take(bounds.maxSelectionChars))
            if (selected.length > bounds.maxSelectionChars) text.append("\n… selection truncated …")
            text.append('\n')
        }
        val diagnostics = snapshot.diagnostics.take(bounds.maxDiagnostics)
        if (diagnostics.isNotEmpty()) {
            text.append("Editor diagnostics:\n")
            diagnostics.forEach { diagnostic ->
                val message = diagnostic.message.replace(Regex("\\s+"), " ").take(bounds.maxDiagnosticChars)
                text.append("- L${diagnostic.line} [${diagnostic.severity}]: $message\n")
            }
            if (snapshot.diagnostics.size > bounds.maxDiagnostics) {
                appendSuffixWithinLimit(text, "- … diagnostics truncated …\n", bounds.maxTotalChars)
            }
        }
        return text.toString().take(bounds.maxTotalChars)
    }

    private fun appendSuffixWithinLimit(text: StringBuilder, suffix: String, limit: Int) {
        if (limit <= 0) {
            text.clear()
            return
        }
        if (suffix.length >= limit) {
            text.clear()
            text.append(suffix.takeLast(limit))
            return
        }

        val prefixLimit = limit - suffix.length
        if (text.length > prefixLimit) {
            text.setLength(prefixLimit)
            if (text.isNotEmpty() && text.last() != '\n') text.setCharAt(text.lastIndex, '\n')
        }
        text.append(suffix)
    }
}

object EditorContextCollector {
    fun collect(project: Project): EditorContextSnapshot? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val basePath = project.basePath ?: return null
        val base = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
        val relative = VfsUtilCore.getRelativePath(file, base, '/') ?: return null
        if (relative.startsWith("../") || relative == "..") return null

        val selection = editor.selectionModel
        val hasSelection = selection.hasSelection()
        val startOffset = if (hasSelection) selection.selectionStart else editor.caretModel.offset
        val rawEnd = if (hasSelection) selection.selectionEnd else startOffset
        val endOffset = if (hasSelection && rawEnd > startOffset && rawEnd <= editor.document.textLength &&
            editor.document.getLineStartOffset(editor.document.getLineNumber(rawEnd)) == rawEnd
        ) rawEnd - 1 else rawEnd
        val startLine = editor.document.getLineNumber(min(startOffset, editor.document.textLength)) + 1
        val endLine = editor.document.getLineNumber(min(max(endOffset, 0), editor.document.textLength)) + 1

        return EditorContextSnapshot(
            relativePath = relative,
            startLine = startLine,
            endLine = endLine,
            selectedText = selection.selectedText,
            diagnostics = collectDiagnostics(editor, if (hasSelection) startOffset..max(startOffset, endOffset) else null),
        )
    }

    private fun collectDiagnostics(editor: Editor, selectedRange: IntRange?): List<EditorDiagnostic> =
        editor.markupModel.allHighlighters.asSequence()
            .filter { highlighter ->
                selectedRange == null || highlighter.endOffset >= selectedRange.first && highlighter.startOffset <= selectedRange.last
            }
            .mapNotNull { highlighter ->
                val tooltip = highlighter.errorStripeTooltip?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                EditorDiagnostic(
                    line = editor.document.getLineNumber(min(highlighter.startOffset, editor.document.textLength)) + 1,
                    severity = highlighter.textAttributesKey?.externalName ?: "diagnostic",
                    message = tooltip,
                )
            }
            .distinctBy { Triple(it.line, it.severity, it.message) }
            .take(40)
            .toList()
}
