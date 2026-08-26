package com.openai.codex.jetbrains.bridge

/** A deliberately small unified-diff reader used only to validate and preview a proposed edit. */
internal object UnifiedPatch {
    data class Hunk(
        val oldStart: Int,
        val oldLength: Int,
        val newStart: Int,
        val newLength: Int,
        val lines: List<String>,
    )

    data class Patch(val hunks: List<Hunk>) {
        fun applyTo(preimage: String): String? {
            val originalEndsWithNewline = preimage.endsWith('\n')
            val source = split(preimage)
            val result = mutableListOf<String>()
            var cursor = 0
            for (hunk in hunks) {
                val hunkStart = if (hunk.oldStart == 0) 0 else hunk.oldStart - 1
                if (hunkStart !in cursor..source.size) return null
                result += source.subList(cursor, hunkStart)
                var index = hunkStart
                var oldCount = 0
                var newCount = 0
                for (line in hunk.lines) {
                    val marker = line.firstOrNull() ?: return null
                    val text = line.drop(1)
                    when (marker) {
                        ' ' -> {
                            if (source.getOrNull(index) != text) return null
                            result += text
                            index++
                            oldCount++
                            newCount++
                        }
                        '-' -> {
                            if (source.getOrNull(index) != text) return null
                            index++
                            oldCount++
                        }
                        '+' -> {
                            result += text
                            newCount++
                        }
                        else -> return null
                    }
                }
                if (oldCount != hunk.oldLength || newCount != hunk.newLength) return null
                cursor = index
            }
            result += source.subList(cursor, source.size)
            val targetEndsWithNewline = originalEndsWithNewline || (source.isEmpty() && result.isNotEmpty())
            return result.joinToString("\n") + if (targetEndsWithNewline && result.isNotEmpty()) "\n" else ""
        }
    }

    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")

    /** Rejects anything other than an unambiguous unified patch. */
    fun parse(diff: String): Patch? {
        if (diff.isBlank()) return null
        val lines = diff.replace("\r\n", "\n").split('\n')
        val hunks = mutableListOf<Hunk>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("--- ") || line.startsWith("+++ ") || line.isEmpty()) {
                index++
                continue
            }
            val match = hunkHeader.matchEntire(line) ?: return null
            index++
            val body = mutableListOf<String>()
            while (index < lines.size && !lines[index].startsWith("@@ ")) {
                val bodyLine = lines[index]
                if (bodyLine == "\\ No newline at end of file") return null
                if (bodyLine.firstOrNull() !in setOf(' ', '+', '-')) return null
                body += bodyLine
                index++
            }
            val oldLength = match.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return null
            val newLength = match.groupValues[4].ifEmpty { "1" }.toIntOrNull() ?: return null
            val oldStart = match.groupValues[1].toIntOrNull() ?: return null
            val newStart = match.groupValues[3].toIntOrNull() ?: return null
            if (oldLength < 0 || newLength < 0 || (oldStart == 0 && oldLength != 0) || (newStart == 0 && newLength != 0)) return null
            hunks += Hunk(oldStart, oldLength, newStart, newLength, body)
        }
        return hunks.takeIf { it.isNotEmpty() }?.let(::Patch)
    }

    private fun split(text: String): List<String> = when {
        text.isEmpty() -> emptyList()
        text.endsWith('\n') -> text.dropLast(1).split('\n')
        else -> text.split('\n')
    }
}
