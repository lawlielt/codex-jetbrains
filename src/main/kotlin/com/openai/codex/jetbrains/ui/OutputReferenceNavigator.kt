package com.openai.codex.jetbrains.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Paths

data class ProjectFileReference(val path: String, val line: Int, val endLine: Int = line)

object OutputReferenceNavigator {
    val pattern: Regex = Regex("@([A-Za-z0-9_./\\-]+)#L(\\d+)(?:-L?(\\d+))?")

    fun parse(text: String): ProjectFileReference? {
        val match = pattern.matchEntire(text) ?: return null
        val line = match.groupValues[2].toIntOrNull() ?: return null
        val end = match.groupValues[3].toIntOrNull() ?: line
        return ProjectFileReference(match.groupValues[1], line, end)
    }

    fun navigate(project: Project, reference: ProjectFileReference): Boolean {
        val root = project.basePath?.let(Paths::get)?.toAbsolutePath()?.normalize() ?: return false
        val resolved = root.resolve(reference.path).normalize()
        if (!resolved.startsWith(root)) return false
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved) ?: return false
        OpenFileDescriptor(project, file, (reference.line - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }
}
