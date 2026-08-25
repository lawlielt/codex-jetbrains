package com.openai.codex.jetbrains.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class CodexTerminalControllerTest {
    private val clock = FakeClock()
    private val factory = FakeSessionFactory()
    private val controller = CodexTerminalController(factory, clock::now)

    @Test
    fun `launches plain codex once in a project-root terminal`() {
        controller.open(Path.of(".", "project"))

        val created = factory.created.single()
        assertEquals(Path.of("project").toAbsolutePath().normalize(), created.workingDirectory)
        assertEquals("Codex", created.tabName)
        assertEquals(listOf("codex"), created.session.commands)
        assertEquals(1, created.session.focusCount)
    }

    @Test
    fun `repeated activation focuses a live session without duplicate commands`() {
        controller.open(Path.of("project"))
        val session = factory.created.single().session

        controller.open(Path.of("project"))
        clock.advancePastRelaunchGrace()
        session.state = TerminalCommandState.RUNNING
        controller.open(Path.of("project"))

        assertEquals(1, factory.created.size)
        assertEquals(listOf("codex"), session.commands)
        assertEquals(3, session.focusCount)
    }

    @Test
    fun `reuses an idle tab to start codex after the prior CLI ends`() {
        controller.open(Path.of("project"))
        val session = factory.created.single().session
        clock.advancePastRelaunchGrace()
        session.state = TerminalCommandState.IDLE

        controller.open(Path.of("project"))

        assertEquals(1, factory.created.size)
        assertEquals(listOf("codex", "codex"), session.commands)
    }

    @Test
    fun `creates a new tab after the prior terminal closes`() {
        controller.open(Path.of("project"))
        val first = factory.created.single().session
        first.open = false

        controller.open(Path.of("project"))

        assertEquals(2, factory.created.size)
        assertSame(first, factory.created.first().session)
        assertEquals(listOf("codex"), factory.created.last().session.commands)
    }

    @Test
    fun `does not guess when the terminal cannot report command state`() {
        controller.open(Path.of("project"))
        val session = factory.created.single().session
        clock.advancePastRelaunchGrace()
        session.state = TerminalCommandState.UNKNOWN

        controller.open(Path.of("project"))

        assertEquals(listOf("codex"), session.commands)
        assertEquals(2, session.focusCount)
    }

    @Test
    fun `reports and stages only into the matching live Codex session`() {
        controller.open(Path.of("project"))
        val session = factory.created.single().session
        session.state = TerminalCommandState.RUNNING

        assertTrue(controller.hasLiveSession(Path.of("project", ".")))
        assertFalse(controller.hasLiveSession(Path.of("other-project")))
        assertTrue(controller.stage(Path.of("project"), "@src/Main.kt#L4-8 "))

        assertEquals(1, factory.created.size)
        assertEquals(listOf("codex"), session.commands)
        assertEquals(listOf("@src/Main.kt#L4-8 "), session.stagedText)
        assertEquals(2, session.focusCount)
    }

    @Test
    fun `never creates or stages when Codex is not known to be running`() {
        assertFalse(controller.hasLiveSession(Path.of("project")))
        assertFalse(controller.stage(Path.of("project"), "@README.md "))
        assertEquals(0, factory.created.size)

        controller.open(Path.of("project"))
        val session = factory.created.single().session

        session.state = TerminalCommandState.IDLE
        assertFalse(controller.hasLiveSession(Path.of("project")))
        assertFalse(controller.stage(Path.of("project"), "@README.md "))

        session.state = TerminalCommandState.UNKNOWN
        assertFalse(controller.hasLiveSession(Path.of("project")))
        assertFalse(controller.stage(Path.of("project"), "@README.md "))

        session.state = TerminalCommandState.RUNNING
        session.open = false
        assertFalse(controller.hasLiveSession(Path.of("project")))
        assertFalse(controller.stage(Path.of("project"), "@README.md "))

        assertEquals(emptyList<String>(), session.stagedText)
        assertEquals(1, session.focusCount)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `literal staging rejects empty text and line breaks without writing or submitting`() {
        controller.open(Path.of("project"))
        val session = factory.created.single().session
        session.state = TerminalCommandState.RUNNING

        assertFalse(controller.stage(Path.of("project"), ""))
        assertFalse(controller.stage(Path.of("project"), "@README.md\n"))
        assertFalse(controller.stage(Path.of("project"), "@README.md\r"))

        assertEquals(listOf("codex"), session.commands)
        assertEquals(emptyList<String>(), session.stagedText)
        assertEquals(1, session.focusCount)
    }

    @Test
    fun `literal staging fails safely when the terminal cannot accept input`() {
        controller.open(Path.of("project"))
        val session = factory.created.single().session
        session.state = TerminalCommandState.RUNNING
        session.acceptsStaging = false

        assertFalse(controller.stage(Path.of("project"), "@README.md "))

        assertEquals(listOf("@README.md "), session.stageAttempts)
        assertEquals(emptyList<String>(), session.stagedText)
        assertEquals(listOf("codex"), session.commands)
        assertEquals(1, factory.created.size)
    }

    /**
     * Regression boundary for the old `env node` exit-127 failure: these tests
     * expose only terminal creation and terminal command submission. There is
     * intentionally no JVM executable path, validation, or ProcessBuilder seam.
     */
    private class FakeSessionFactory : CodexTerminalSessionFactory {
        val created = mutableListOf<CreatedSession>()

        override fun create(workingDirectory: Path, tabName: String): CodexTerminalSession =
            FakeSession().also { created += CreatedSession(workingDirectory, tabName, it) }
    }

    private data class CreatedSession(
        val workingDirectory: Path,
        val tabName: String,
        val session: FakeSession,
    )

    private class FakeSession : CodexTerminalSession {
        var open = true
        var state = TerminalCommandState.IDLE
        var focusCount = 0
        var acceptsStaging = true
        val commands = mutableListOf<String>()
        val stageAttempts = mutableListOf<String>()
        val stagedText = mutableListOf<String>()

        override val isOpen: Boolean
            get() = open

        override fun focus() {
            focusCount++
        }

        override fun commandState(): TerminalCommandState = state

        override fun sendCommand(command: String) {
            commands += command
        }

        override fun stageText(text: String): Boolean {
            stageAttempts += text
            if (!acceptsStaging) return false
            stagedText += text
            return true
        }
    }

    private class FakeClock {
        private var value = 0L

        fun now(): Long = value

        fun advancePastRelaunchGrace() {
            value += CodexTerminalController.RELAUNCH_GRACE_NANOS + 1
        }
    }
}
