package com.openai.codex.jetbrains.ui

object UnifiedDiffApplier {
    private val hunkHeader = Regex("@@ -([0-9]+)(?:,([0-9]+))? \\+([0-9]+)(?:,([0-9]+))? @@.*")

    fun apply(original: String, unifiedDiff: String): String? {
        if (unifiedDiff.isBlank()) return null
        val originalLines = original.split('\n')
        val output = ArrayList<String>()
        var originalIndex = 0
        var sawHunk = false
        val diffLines = unifiedDiff.split('\n')
        var index = 0
        while (index < diffLines.size) {
            val header = hunkHeader.matchEntire(diffLines[index])
            if (header == null) {
                index++
                continue
            }
            sawHunk = true
            val oldStart = (header.groupValues[1].toIntOrNull() ?: return null).let { if (it == 0) 0 else it - 1 }
            if (oldStart < originalIndex || oldStart > originalLines.size) return null
            while (originalIndex < oldStart) output.add(originalLines[originalIndex++])
            index++
            while (index < diffLines.size && !diffLines[index].startsWith("@@ ")) {
                val line = diffLines[index]
                when {
                    line.startsWith(" ") -> {
                        if (originalIndex >= originalLines.size) return null
                        output.add(originalLines[originalIndex++])
                    }
                    line.startsWith("-") -> {
                        if (originalIndex >= originalLines.size) return null
                        originalIndex++
                    }
                    line.startsWith("+") -> output.add(line.substring(1))
                    line.startsWith("\\ No newline at end of file") -> Unit
                    else -> break
                }
                index++
            }
        }
        if (!sawHunk) return null
        while (originalIndex < originalLines.size) output.add(originalLines[originalIndex++])
        return output.joinToString("\n")
    }
}
