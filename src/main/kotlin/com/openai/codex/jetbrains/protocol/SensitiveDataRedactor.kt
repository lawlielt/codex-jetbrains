package com.openai.codex.jetbrains.protocol

object SensitiveDataRedactor {
    private val apiKey = Regex("(?i)(sk-[a-z0-9_-]{8,})")
    private val bearer = Regex("(?i)(bearer\\s+)[a-z0-9._~+/-]{8,}=*")
    private val jsonKey = Regex("(?i)(\"(?:apiKey|api_key|accessToken|refreshToken)\"\\s*:\\s*\")[^\"]*(\")")

    fun redact(text: String): String = text
        .replace(jsonKey, "$1[REDACTED]$2")
        .replace(bearer, "$1[REDACTED]")
        .replace(apiKey, "[REDACTED]")
}
