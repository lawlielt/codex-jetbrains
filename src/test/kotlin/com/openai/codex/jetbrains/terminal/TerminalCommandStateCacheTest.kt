package com.openai.codex.jetbrains.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCommandStateCacheTest {
    @Test
    fun `action-path reads never invoke the terminal probe`() {
        var probes = 0
        val cache = TerminalCommandStateCache()

        assertEquals(TerminalCommandState.UNKNOWN, cache.current())
        assertEquals(TerminalCommandState.UNKNOWN, cache.current())
        assertEquals(0, probes)

        cache.refresh {
            probes++
            TerminalCommandState.RUNNING
        }

        assertEquals(TerminalCommandState.RUNNING, cache.current())
        assertEquals(1, probes)
    }

    @Test
    fun `failed background probe fails closed`() {
        val cache = TerminalCommandStateCache(TerminalCommandState.RUNNING)

        cache.refresh { error("terminal not ready") }

        assertEquals(TerminalCommandState.UNKNOWN, cache.current())
    }

    @Test
    fun `submitted command is immediately known running until the next probe`() {
        val cache = TerminalCommandStateCache(TerminalCommandState.IDLE)

        cache.markRunning()
        assertEquals(TerminalCommandState.RUNNING, cache.current())

        cache.refresh { TerminalCommandState.IDLE }
        assertEquals(TerminalCommandState.IDLE, cache.current())
    }

    @Test
    fun `termination invalidates a prior running state`() {
        val cache = TerminalCommandStateCache(TerminalCommandState.RUNNING)

        cache.markUnknown()

        assertEquals(TerminalCommandState.UNKNOWN, cache.current())
    }
}
