package com.openai.codex.jetbrains.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class BridgeLaunchCommandTest {
    private val spec = BridgeLaunchSpec(
        relayEndpoint = "ws://127.0.0.1:4100",
        appServerEndpoint = "ws://127.0.0.1:4200",
        relayTokenFile = Path.of("/private/state", "remote.token"),
        appServerTokenFile = Path.of("/private/state", "app.token"),
        relayFailureMarker = Path.of("/private/state", "relay-failed"),
        stateDirectory = Path.of("/private/state"),
    )

    @Test
    fun `posix launcher probes capabilities uses token files and reaps server`() {
        val command = BridgeLaunchCommand.create(spec, windows = false)

        assertTrue(command.contains("codex app-server --help"))
        assertTrue(command.contains("--remote-auth-token-env CODEX_JETBRAINS_RELAY_TOKEN"))
        assertTrue(command.contains("trap cleanup EXIT INT TERM HUP"))
        assertTrue(command.contains("kill \"${'$'}server_pid\"") && command.contains("wait \"${'$'}server_pid\""))
        assertTrue(command.contains("fallback") && command.contains("exec codex"))
        assertTrue(command.contains("if [ -f '/private/state/relay-failed' ]; then fallback; fi"))
        assertTrue(command.contains("while [ \"${'$'}attempt\" -lt 100 ]"))
        assertFalse(command.contains("$(seq"))
        assertFalse(command.contains("remote-token-value"))
    }

    @Test
    fun `powershell launcher uses argument arrays and finally cleanup`() {
        val command = BridgeLaunchCommand.create(spec, windows = true)

        assertTrue(command.contains("Start-Process -FilePath codex -ArgumentList @("))
        assertTrue(command.contains("try {") && command.contains("} finally {"))
        assertTrue(command.contains("Stop-Process -Id ${'$'}server.Id -Force"))
        assertTrue(command.contains("Remove-Item Env:CODEX_JETBRAINS_RELAY_TOKEN"))
        assertTrue(command.contains("Test-Path -LiteralPath '/private/state/relay-failed'"))
    }
}
