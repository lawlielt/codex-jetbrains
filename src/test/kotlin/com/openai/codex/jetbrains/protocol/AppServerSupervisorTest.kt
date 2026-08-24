package com.openai.codex.jetbrains.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppServerSupervisorTest {
    @Test
    fun `reports child process termination and schedules restart`() {
        val process = ControllableProcess()
        val started = CountDownLatch(1)
        val exited = CountDownLatch(1)
        var exitCode = -1
        var restartScheduled = false
        val supervisor = AppServerSupervisor(
            executableProvider = { "codex" },
            workingDirectory = Paths.get("."),
            listener = object : AppServerSupervisorListener {
                override fun rpcListener(): JsonlRpcListener = object : JsonlRpcListener {}
                override fun onProcessStarted(client: JsonlRpcClient) = started.countDown()
                override fun onProcessExited(exitCodeValue: Int, restart: Boolean) {
                    exitCode = exitCodeValue
                    restartScheduled = restart
                    exited.countDown()
                }
            },
            processFactory = AppServerProcessFactory { _, _ -> process },
        )
        supervisor.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        process.complete(17)
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        assertEquals(17, exitCode)
        assertTrue(restartScheduled)
        supervisor.close()
    }

    private class ControllableProcess : Process() {
        private val finished = CountDownLatch(1)
        private var code = 0
        private val stdout = java.io.PipedInputStream()
        private val stdoutWriter = java.io.PipedOutputStream(stdout)

        fun complete(exitCode: Int) {
            code = exitCode
            stdoutWriter.close()
            finished.countDown()
        }

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { finished.await(); return code }
        override fun exitValue(): Int { check(finished.count == 0L); return code }
        override fun destroy() { complete(code) }
    }
}
