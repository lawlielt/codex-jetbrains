package com.openai.codex.jetbrains.bridge

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

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
    fun `posix launcher installs an isolated script and submits one terminal command`() {
        val state = Files.createTempDirectory("bridge-launch-test-")
        try {
            val localSpec = spec.copy(stateDirectory = state)
            val launch = BridgeLaunchCommand.install(localSpec, windows = false)
            val script = Files.readString(launch.scriptFile)

            assertFalse(launch.terminalCommand.contains('\n'))
            assertFalse(launch.terminalCommand.contains("set -u"))
            assertEquals("/bin/sh '${launch.scriptFile}'", launch.terminalCommand)
            assertTrue(script.startsWith("#!/bin/sh\nset -u\n"))
            assertTrue(script.contains("codex app-server --help"))
            assertTrue(script.contains("--remote-auth-token-env CODEX_JETBRAINS_RELAY_TOKEN"))
            assertTrue(script.contains("trap cleanup EXIT INT TERM HUP"))
            assertTrue(script.contains("kill \"${'$'}server_pid\"") && script.contains("wait \"${'$'}server_pid\""))
            assertTrue(script.contains("fallback") && script.contains("exec codex"))
            assertTrue(script.contains("if [ -f '/private/state/relay-failed' ]; then fallback; fi"))
            assertTrue(script.contains("while [ \"${'$'}attempt\" -lt 100 ]"))
            assertTrue(script.contains("|| \\\n"))
            assertFalse(script.contains("|| \\\\\n"))
            assertFalse(script.contains("$(seq"))
            assertFalse(script.contains("remote-token-value"))
            assertTrue(Files.isExecutable(launch.scriptFile))
        } finally {
            state.toFile().deleteRecursively()
        }
    }

    @Test
    fun `powershell launcher installs a script and submits one terminal command`() {
        val state = Files.createTempDirectory("bridge-launch-test-")
        try {
            val localSpec = spec.copy(stateDirectory = state)
            val launch = BridgeLaunchCommand.install(localSpec, windows = true)
            val script = Files.readString(launch.scriptFile)

            assertFalse(launch.terminalCommand.contains('\n'))
            assertFalse(launch.terminalCommand.contains("${'$'}ErrorActionPreference"))
            assertTrue(launch.terminalCommand.startsWith("powershell.exe -NoLogo -NoProfile"))
            assertTrue(launch.terminalCommand.endsWith("-File \"${launch.scriptFile}\""))
            assertTrue(script.contains("Start-Process -FilePath codex -ArgumentList @("))
            assertTrue(script.contains("try {") && script.contains("} finally {"))
            assertTrue(script.contains("Stop-Process -Id ${'$'}server.Id -Force"))
            assertTrue(script.contains("Remove-Item Env:CODEX_JETBRAINS_RELAY_TOKEN"))
            assertTrue(script.contains("Test-Path -LiteralPath '/private/state/relay-failed'"))
        } finally {
            state.toFile().deleteRecursively()
        }
    }

    @Test
    fun `posix launcher executes through one child shell without multiline terminal parsing`() {
        assumeFalse(SystemInfo.isWindows)
        val root = Files.createTempDirectory("bridge-launch-execution-")
        try {
            val state = Files.createDirectory(root.resolve("state with ' quote"))
            val fakeBin = Files.createDirectory(root.resolve("bin"))
            val remoteMarker = root.resolve("remote-ran")
            val relayToken = state.resolve("remote.token")
            val appToken = state.resolve("app.token")
            Files.writeString(relayToken, "relay-token")
            Files.writeString(appToken, "app-token")
            writeExecutable(
                fakeBin.resolve("codex"),
                """#!/bin/sh
if [ "${'$'}1" = "app-server" ] && [ "${'$'}2" = "--help" ]; then
  printf '%s\n' '--listen --ws-auth --ws-token-file'
  exit 0
fi
if [ "${'$'}1" = "--help" ]; then
  printf '%s\n' '--remote-auth-token-env'
  exit 0
fi
if [ "${'$'}1" = "app-server" ]; then
  trap 'exit 0' INT TERM
  while :; do sleep 1; done
fi
if [ "${'$'}1" = "--remote" ]; then
  printf '%s\n' remote >"${'$'}REMOTE_MARKER"
  exit 0
fi
exit 64
""",
            )
            writeExecutable(fakeBin.resolve("curl"), "#!/bin/sh\nexit 0\n")
            val launch = BridgeLaunchCommand.install(
                spec.copy(
                    relayTokenFile = relayToken,
                    appServerTokenFile = appToken,
                    relayFailureMarker = state.resolve("relay-failed"),
                    stateDirectory = state,
                ),
                windows = false,
            )

            val process = ProcessBuilder("/bin/sh", "-c", launch.terminalCommand)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "${fakeBin}:${environment()["PATH"]}"
                    environment()["REMOTE_MARKER"] = remoteMarker.toString()
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()

            assertEquals(output, 0, process.waitFor())
            assertTrue(Files.exists(remoteMarker))
            assertFalse(Files.exists(state))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeExecutable(path: Path, content: String) {
        Files.writeString(path, content)
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }
}
