package com.openai.codex.jetbrains.actions

import java.nio.file.Path

internal object CodexEditorReference {
    fun create(
        projectRoot: Path,
        file: Path,
        documentText: CharSequence,
        selectionStart: Int? = null,
        selectionEnd: Int? = null,
    ): String? {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        if (normalizedFile == normalizedRoot || !normalizedFile.startsWith(normalizedRoot)) return null

        val relativePath = normalizedRoot.relativize(normalizedFile)
            .joinToString("/") { it.toString() }
        if (relativePath.isEmpty()) return null

        if (selectionStart == null && selectionEnd == null) return "@$relativePath"
        if (selectionStart == null || selectionEnd == null) return null
        if (selectionStart !in 0..documentText.length || selectionEnd !in 0..documentText.length) return null
        if (selectionStart >= selectionEnd) return "@$relativePath"

        val startLine = lineNumber(documentText, selectionStart)
        val endLine = lineNumber(documentText, selectionEnd - 1)
        val suffix = if (startLine == endLine) "#L$startLine" else "#L$startLine-$endLine"
        return "@$relativePath$suffix"
    }

    private fun lineNumber(text: CharSequence, offset: Int): Int {
        var line = 1
        for (index in 0 until offset) {
            if (text[index] == '\n') line++
        }
        return line
    }
}
