package com.openai.codex.jetbrains.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Opt-in, no-model transport smoke for the locally installed experimental CLI surface. */
class WebSocketRelayRealCliSmokeTest {
    @Test
    fun `real remote TUI stays connected through production relay`() {
        assumeTrue(System.getenv("CODEX_REAL_TRANSPORT_SMOKE") == "1")
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/script")))

        val state = Files.createTempDirectory("codex-relay-real-smoke-")
        val ambient = System.getenv("CODEX_REAL_TRANSPORT_AMBIENT") == "1"
        val project = if (ambient) {
            System.getenv("CODEX_REAL_TRANSPORT_CWD")?.let(Path::of)?.toAbsolutePath()?.normalize()
                ?: Path.of("").toAbsolutePath().normalize()
        } else {
            Files.createDirectory(state.resolve("project"))
        }
        val codexHome = if (ambient) null else Files.createDirectory(state.resolve("codex-home"))
        val tokenFile = state.resolve("app-server.token")
        Files.writeString(tokenFile, "app-token")
        val appPort = freePort()
        val appServer = ProcessBuilder(
            "codex",
            "app-server",
            "--listen",
            "ws://127.0.0.1:$appPort",
            "--ws-auth",
            "capability-token",
            "--ws-token-file",
            tokenFile.toString(),
        ).directory(project.toFile()).redirectErrorStream(true).apply {
            codexHome?.let { environment()["CODEX_HOME"] = it.toString() }
        }.start()
        var relay: WebSocketRelay? = null
        var tui: Process? = null
        try {
            assertTrue("app-server did not become ready", awaitReady(appPort, appServer))
            val failures = CopyOnWriteArrayList<Throwable>()
            relay = WebSocketRelay(
                appPort,
                "remote-token",
                "app-token",
                FileChangeApprovalCoordinator(
                    FileChangeValidator(project, NioFileSnapshotStore()),
                    NativeDiffPresenter { _, complete -> complete(ApprovalDecision.DECLINE) },
                ),
                state.resolve("relay-failed"),
                onClosed = {},
                failureObserver = failures::add,
            ).also(WebSocketRelay::start)
            tui = ProcessBuilder(
                "/usr/bin/script",
                "-q",
                "/dev/null",
                "codex",
                "--remote",
                relay.endpoint,
                "--remote-auth-token-env",
                "CODEX_JETBRAINS_RELAY_TOKEN",
                "--no-alt-screen",
            ).directory(project.toFile()).redirectErrorStream(true).apply {
                codexHome?.let { environment()["CODEX_HOME"] = it.toString() }
                environment()["CODEX_JETBRAINS_RELAY_TOKEN"] = "remote-token"
            }.start()

            Thread.sleep(5_000)
            assertTrue("remote TUI exited before the observation window", tui.isAlive)
            assertTrue("relay reported an unexpected transport fault: ${failures.firstOrNull()?.javaClass?.simpleName}", failures.isEmpty())
            assertFalse("relay wrote a failure marker while the TUI was connected", Files.exists(state.resolve("relay-failed")))
        } finally {
            relay?.close()
            stop(tui)
            stop(appServer)
            state.toFile().deleteRecursively()
        }
    }

    private fun awaitReady(port: Int, process: Process): Boolean {
        repeat(100) {
            if (!process.isAlive) return false
            val ready = runCatching {
                (URI.create("http://127.0.0.1:$port/readyz").toURL().openConnection() as HttpURLConnection).run {
                    connectTimeout = 200
                    readTimeout = 200
                    responseCode in 200..299
                }
            }.getOrDefault(false)
            if (ready) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun stop(process: Process?) {
        if (process == null || !process.isAlive) return
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
}
