package com.openai.codex.jetbrains.terminal

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** A conservative project-file reference recognized in the dedicated Codex terminal. */
internal data class TerminalFileReference(
    val path: String,
    val line: Int,
    val column: Int?,
    val endLine: Int?,
    val startOffset: Int,
    val endOffset: Int,
)

internal object TerminalFileReferenceParser {
    private val backtickReference = Regex("`([^`\\r\\n]+)`")
    private val doubleQuotedReference = Regex("\"([^\"\\r\\n]+)\"")
    private val plainReference = Regex(
        """(?<![A-Za-z0-9_@+./\\:-])((?:[A-Za-z]:[\\/])?[A-Za-z0-9_@+./\\-]+(?:#L\d+(?:-L?\d+)?|:\d+(?::\d+|-\d+)?))(?=$|[\s,;.!?)\]}])""",
    )
    private val hashLocation = Regex("""#L(\d+)(?:-L?(\d+))?$""")
    private val colonLocation = Regex(""":(\d+)(?::(\d+)|-(\d+))?$""")

    fun parse(line: String): List<TerminalFileReference> {
        val references = mutableListOf<TerminalFileReference>()
        val delimitedRanges = mutableListOf<IntRange>()

        fun collectDelimited(pattern: Regex) {
            pattern.findAll(line).forEach { match ->
                val body = match.groups[1] ?: return@forEach
                parseBody(body.value, body.range.first)?.let(references::add)
                delimitedRanges += match.range
            }
        }

        collectDelimited(backtickReference)
        collectDelimited(doubleQuotedReference)
        plainReference.findAll(line).forEach { match ->
            if (delimitedRanges.any { occupied -> match.range.overlaps(occupied) }) return@forEach
            val body = match.groups[1] ?: return@forEach
            parseBody(body.value, body.range.first)?.let(references::add)
        }

        return references.distinctBy { it.startOffset to it.endOffset }.sortedBy(TerminalFileReference::startOffset)
    }

    private fun parseBody(body: String, startOffset: Int): TerminalFileReference? {
        val hash = hashLocation.find(body)
        val colon = if (hash == null) colonLocation.find(body) else null
        val location = hash ?: colon ?: return null
        val path = body.substring(0, location.range.first)
        if (!isPlausiblePath(path)) return null

        val line = location.groupValues[1].toPositiveInt() ?: return null
        val column: Int?
        val endLine: Int?
        if (hash != null) {
            column = null
            endLine = location.groupValues[2].toPositiveInt()
        } else {
            column = location.groupValues[2].toPositiveInt()
            endLine = location.groupValues[3].toPositiveInt()
        }
        if (endLine != null && endLine < line) return null

        return TerminalFileReference(
            path = path,
            line = line,
            column = column,
            endLine = endLine,
            startOffset = startOffset,
            endOffset = startOffset + body.length,
        )
    }

    private fun isPlausiblePath(path: String): Boolean =
        path.isNotEmpty() &&
            path == path.trim() &&
            !path.contains("://") &&
            path.none { it == '\u0000' || it == '\r' || it == '\n' }

    private fun String.toPositiveInt(): Int? =
        takeIf(String::isNotEmpty)?.toIntOrNull()?.takeIf { it > 0 }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}

/** Resolves only existing regular files whose real path remains below the project root. */
internal class ProjectFileReferenceResolver(projectRoot: Path) {
    private val normalizedRoot = projectRoot.toAbsolutePath().normalize()
    private val realRoot = runCatching { normalizedRoot.toRealPath() }.getOrNull()

    fun resolve(pathText: String): Path? {
        if (pathText.isEmpty() || pathText.any { it == '\u0000' || it == '\r' || it == '\n' }) return null
        if (File.separatorChar != '\\' && WINDOWS_ABSOLUTE.matches(pathText)) return null

        val platformPath = if (File.separatorChar == '\\') pathText.replace('/', '\\') else pathText
        val parsed = runCatching { Path.of(platformPath) }.getOrNull() ?: return null
        val candidate = (if (parsed.isAbsolute) parsed else normalizedRoot.resolve(parsed)).normalize()
        if (!candidate.startsWith(normalizedRoot)) return null

        val canonicalRoot = realRoot ?: return null
        val realCandidate = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        return candidate.takeIf { realCandidate.startsWith(canonicalRoot) && Files.isRegularFile(realCandidate) }
    }

    private companion object {
        val WINDOWS_ABSOLUTE = Regex("""[A-Za-z]:[\\/].*""")
    }
}

/** Installed directly on one classic build-242 terminal widget; it is never a global console filter. */
internal class CodexTerminalFileFilter(
    private val project: Project,
    projectRoot: Path,
) : Filter {
    private val resolver = ProjectFileReferenceResolver(projectRoot)

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = (entireLength - line.length).coerceAtLeast(0)
        val items = TerminalFileReferenceParser.parse(line).mapNotNull { reference ->
            val path = resolver.resolve(reference.path) ?: return@mapNotNull null
            val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@mapNotNull null
            Filter.ResultItem(
                lineStart + reference.startOffset,
                lineStart + reference.endOffset,
                CodexFileHyperlinkInfo(file, reference),
            )
        }
        return items.takeIf(List<*>::isNotEmpty)?.let(Filter::Result)
    }
}

private class CodexFileHyperlinkInfo(
    private val file: VirtualFile,
    private val reference: TerminalFileReference,
) : HyperlinkInfo {
    override fun navigate(project: Project) {
        val line = reference.line - 1
        val column = (reference.column ?: 1) - 1
        val editor = FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, file, line, column),
            true,
        ) ?: return

        val endLine = reference.endLine ?: return
        val document = editor.document
        if (line !in 0 until document.lineCount) return
        val boundedEndLine = (endLine - 1).coerceAtMost(document.lineCount - 1)
        editor.selectionModel.setSelection(
            document.getLineStartOffset(line),
            document.getLineEndOffset(boundedEndLine),
        )
    }
}
