package com.openai.codex.jetbrains.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {
    @Test
    fun `redacts api keys bearer values and token json fields`() {
        val secret = "sk-this-is-a-secret-123456"
        val raw = "login failed apiKey=$secret Bearer abcdefghijklmnop {\"accessToken\":\"token-value\"}"
        val redacted = SensitiveDataRedactor.redact(raw)
        assertFalse(redacted.contains(secret))
        assertFalse(redacted.contains("abcdefghijklmnop"))
        assertFalse(redacted.contains("token-value"))
        assertTrue(redacted.contains("[REDACTED]"))
    }
}
