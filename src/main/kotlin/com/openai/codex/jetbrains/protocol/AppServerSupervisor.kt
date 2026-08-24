package com.openai.codex.jetbrains.protocol

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

fun interface AppServerProcessFactory {
    fun start(executable: String, workingDirectory: Path): Process
}

class DefaultAppServerProcessFactory : AppServerProcessFactory {
    override fun start(executable: String, workingDirectory: Path): Process =
        ProcessBuilder(executable, "app-server")
            .directory(workingDirectory.toFile())
            .redirectErrorStream(false)
            .start()
}

interface AppServerSupervisorListener {
    fun onProcessStarted(client: JsonlRpcClient) = Unit
    fun onProcessError(message: String, error: Throwable?) = Unit
    fun onProcessExited(exitCode: Int, restartScheduled: Boolean) = Unit
    fun onStderr(line: String) = Unit
    fun rpcListener(): JsonlRpcListener
}

class AppServerSupervisor(
    private val executableProvider: () -> String,
    private val workingDirectory: Path,
    private val listener: AppServerSupervisorListener,
    private val processFactory: AppServerProcessFactory = DefaultAppServerProcessFactory(),
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "codex-app-server-supervisor").apply { isDaemon = true }
    },
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "codex-app-server-restarter").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val generation = AtomicLong(0)
    private val lock = Any()
    @Volatile private var stopped = true
    @Volatile private var process: Process? = null
    @Volatile private var client: JsonlRpcClient? = null
    private val restartAttempt = AtomicInteger(0)

    fun start() {
        synchronized(lock) {
            if (!stopped || process != null) return
            stopped = false
            restartAttempt.set(0)
            launchAsync(generation.incrementAndGet())
        }
    }

    fun restart() {
        val oldProcess: Process?
        val oldClient: JsonlRpcClient?
        val token: Long
        synchronized(lock) {
            stopped = false
            restartAttempt.set(0)
            token = generation.incrementAndGet()
            oldProcess = process
            oldClient = client
            process = null
            client = null
        }
        ioExecutor.execute {
            oldClient?.close()
            terminate(oldProcess)
            launchAsync(token)
        }
    }

    fun currentClient(): JsonlRpcClient? = client

    fun markHealthy() {
        restartAttempt.set(0)
    }

    private fun launchAsync(token: Long) {
        ioExecutor.execute {
            try {
                val started = processFactory.start(executableProvider().ifBlank { "codex" }, workingDirectory)
                if (stopped || token != generation.get()) {
                    terminate(started)
                    return@execute
                }
                val rpc = JsonlRpcClient(started.inputStream, started.outputStream, listener.rpcListener())
                synchronized(lock) {
                    if (stopped || token != generation.get()) {
                        rpc.close()
                        terminate(started)
                        return@execute
                    }
                    process = started
                    client = rpc
                }
                listener.onProcessStarted(rpc)
                readStderr(started, token)
                watchExit(started, rpc, token)
            } catch (error: Throwable) {
                if (!stopped && token == generation.get()) {
                    listener.onProcessError(actionableLaunchMessage(executableProvider()), error)
                    scheduleRestart(token)
                }
            }
        }
    }

    private fun readStderr(started: Process, token: Long) {
        ioExecutor.execute {
            BufferedReader(InputStreamReader(started.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (!stopped && token == generation.get()) listener.onStderr(line)
                }
            }
        }
    }

    private fun watchExit(started: Process, rpc: JsonlRpcClient, token: Long) {
        ioExecutor.execute {
            val exitCode = started.waitFor()
            rpc.close()
            if (token != generation.get()) return@execute
            synchronized(lock) {
                if (process === started) process = null
                if (client === rpc) client = null
            }
            val restarting = !stopped && restartAttempt.get() < MAX_RESTARTS
            listener.onProcessExited(exitCode, restarting)
            if (restarting) scheduleRestart(token)
        }
    }

    private fun scheduleRestart(token: Long) {
        if (stopped || token != generation.get()) return
        val attempt = restartAttempt.incrementAndGet()
        if (attempt > MAX_RESTARTS) return
        val delay = min(30L, 1L shl (attempt - 1))
        scheduler.schedule({
            if (!stopped && token == generation.get()) launchAsync(token)
        }, delay, TimeUnit.SECONDS)
    }

    override fun close() {
        val oldProcess: Process?
        val oldClient: JsonlRpcClient?
        synchronized(lock) {
            if (stopped && process == null) return
            stopped = true
            generation.incrementAndGet()
            oldProcess = process
            oldClient = client
            process = null
            client = null
        }
        scheduler.shutdownNow()
        ioExecutor.execute {
            oldClient?.close()
            terminate(oldProcess)
        }
        ioExecutor.shutdown()
    }

    private fun terminate(target: Process?) {
        if (target == null || !target.isAlive) return
        target.destroy()
        if (!target.waitFor(2, TimeUnit.SECONDS)) target.destroyForcibly()
    }

    companion object {
        private const val MAX_RESTARTS = 3

        fun actionableLaunchMessage(executable: String): String =
            "Could not launch '${executable.ifBlank { "codex" }} app-server'. Install the Codex CLI, " +
                "sign in if required, or set the executable path under Settings | Tools | Codex."
    }
}
