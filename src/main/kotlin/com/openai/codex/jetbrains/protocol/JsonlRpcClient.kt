package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RpcException(val rpcError: RpcError) : RuntimeException(rpcError.message)

interface JsonlRpcListener {
    fun onNotification(message: InboundMessage.Notification) = Unit
    fun onRequest(message: InboundMessage.Request) = Unit
    fun onMalformedMessage(rawLine: String, error: MalformedMessageException) = Unit
    fun onTransportClosed(error: Throwable?) = Unit
}

class JsonlRpcClient(
    private val input: InputStream,
    output: OutputStream,
    private val listener: JsonlRpcListener,
    private val readerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "codex-app-server-jsonl-reader").apply { isDaemon = true }
    },
) : Closeable {
    private val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonElement>>()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    init {
        readerExecutor.execute(::readLoop)
    }

    fun request(method: String, params: JsonElement = JsonObject()): CompletableFuture<JsonElement> {
        check(!closed.get()) { "JSONL transport is closed" }
        val id = ids.getAndIncrement()
        val future = CompletableFuture<JsonElement>()
        pending[id.toString()] = future
        try {
            write(JsonlProtocol.request(id, method, params))
        } catch (error: Throwable) {
            pending.remove(id.toString())
            future.completeExceptionally(error)
        }
        return future
    }

    fun notify(method: String, params: JsonElement = JsonObject()) {
        check(!closed.get()) { "JSONL transport is closed" }
        write(JsonlProtocol.notification(method, params))
    }

    fun respond(id: JsonElement, result: JsonElement) {
        check(!closed.get()) { "JSONL transport is closed" }
        write(JsonlProtocol.response(id, result))
    }

    fun respondError(id: JsonElement, code: Int, message: String) {
        check(!closed.get()) { "JSONL transport is closed" }
        write(JsonlProtocol.errorResponse(id, code, message))
    }

    private fun write(line: String) {
        synchronized(writeLock) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    private fun readLoop() {
        var terminalError: Throwable? = null
        try {
            while (!closed.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = try {
                    JsonlProtocol.parse(line)
                } catch (error: MalformedMessageException) {
                    listener.onMalformedMessage(line, error)
                    continue
                }
                when (message) {
                    is InboundMessage.Response -> {
                        val future = pending.remove(JsonlProtocol.idKey(message.id)) ?: continue
                        if (message.error != null) future.completeExceptionally(RpcException(message.error))
                        else future.complete(message.result ?: JsonObject())
                    }
                    is InboundMessage.Request -> listener.onRequest(message)
                    is InboundMessage.Notification -> listener.onNotification(message)
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) terminalError = error
        } finally {
            if (closed.compareAndSet(false, true)) {
                val error = terminalError ?: IllegalStateException("Codex app-server closed its output stream")
                pending.values.forEach { it.completeExceptionally(error) }
                pending.clear()
                listener.onTransportClosed(terminalError)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val error = IllegalStateException("JSONL transport closed")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        runCatching { input.close() }
        readerExecutor.shutdownNow()
        runCatching { writer.close() }
    }
}
