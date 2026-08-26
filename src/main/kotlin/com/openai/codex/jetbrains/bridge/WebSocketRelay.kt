package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A loopback-only WebSocket relay. It is intentionally protocol-transparent
 * except for item/fileChange/requestApproval, which never reaches the TUI.
 */
internal class WebSocketRelay(
    private val appServerPort: Int,
    private val relayToken: String,
    private val appServerToken: String,
    private val approvals: FileChangeApprovalCoordinator,
    private val failureMarker: Path,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "codex-native-approval-relay").apply { isDaemon = true }
    }
    private val sockets = mutableSetOf<Socket>()
    private val upstreamReady = AtomicBoolean(false)

    val endpoint: String = "ws://127.0.0.1:${server.localPort}"

    fun start() {
        executor.execute {
            try {
                server.soTimeout = CONNECT_TIMEOUT_MILLIS
                val client = server.accept()
                synchronized(sockets) { sockets += client }
                runConnection(client)
            } catch (_: Exception) {
                if (!closed.get() && !upstreamReady.get()) markStartupFailure()
            } finally {
                if (!closed.get() && !upstreamReady.get()) markStartupFailure()
                close()
            }
        }
    }

    private fun runConnection(client: Socket) {
        client.soTimeout = 0
        val clientInput = BufferedInputStream(client.getInputStream())
        val clientOutput = BufferedOutputStream(client.getOutputStream())
        val clientFrames = WebSocketFrames(clientInput, clientOutput)
        val clientRequest = HttpUpgrade.read(clientInput)
        if (!clientRequest.startLine.startsWith("GET ") || !constantTimeEquals(clientRequest.headers["authorization"], "Bearer $relayToken")) return
        val clientKey = clientRequest.headers["sec-websocket-key"] ?: return
        if (!clientRequest.headers["upgrade"].equals("websocket", ignoreCase = true)) return
        HttpUpgrade.accept(clientOutput, clientKey)

        val upstream = Socket(InetAddress.getLoopbackAddress(), appServerPort)
        synchronized(sockets) { sockets += upstream }
        val upstreamInput = BufferedInputStream(upstream.getInputStream())
        val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
        val upstreamFrames = WebSocketFrames(upstreamInput, upstreamOutput)
        val upstreamKey = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        HttpUpgrade.request(upstreamOutput, appServerPort, upstreamKey, appServerToken)
        val upstreamResponse = HttpUpgrade.read(upstreamInput)
        if (!upstreamResponse.startLine.startsWith("HTTP/1.1 101") || upstreamResponse.headers["sec-websocket-accept"] != acceptKey(upstreamKey)) return
        upstreamReady.set(true)

        val clientPump = executor.submit {
            pumpClient(clientFrames, upstreamFrames)
        }
        try {
            pumpUpstream(upstreamFrames, clientFrames)
        } finally {
            clientPump.cancel(true)
            upstream.close()
            client.close()
        }
    }

    private fun pumpClient(client: WebSocketFrames, upstream: WebSocketFrames) {
        while (!closed.get()) {
            val frame = client.read() ?: return
            when (frame.opcode) {
                0x9 -> client.write(WebSocketFrame(0xA, frame.payload), masked = false)
                0x8 -> {
                    upstream.write(frame, masked = true)
                    return
                }
                else -> upstream.write(frame, masked = true)
            }
        }
    }

    private fun pumpUpstream(upstream: WebSocketFrames, client: WebSocketFrames) {
        while (!closed.get()) {
            val frame = upstream.read() ?: return
            when (frame.opcode) {
                0x9 -> upstream.write(WebSocketFrame(0xA, frame.payload), masked = true)
                0x8 -> {
                    client.write(frame, masked = false)
                    return
                }
                0x1 -> handleUpstreamText(frame, upstream, client)
                else -> client.write(frame, masked = false)
            }
        }
    }

    private fun handleUpstreamText(frame: WebSocketFrame, upstream: WebSocketFrames, client: WebSocketFrames) {
        val message = runCatching { Json.parseToJsonElement(frame.payload.decodeToString()) as? JsonObject }.getOrNull()
        if (message == null) {
            client.write(frame, masked = false)
            return
        }
        when ((message["method"] as? JsonPrimitive)?.contentOrNull) {
            "item/started" -> (message["params"] as? JsonObject)?.let(approvals::itemStarted)
            "item/completed" -> (message["params"] as? JsonObject)?.let(approvals::itemCompleted)
            "item/fileChange/requestApproval" -> {
                val id = message["id"] ?: return
                val params = message["params"] as? JsonObject
                if (params == null) {
                    answer(upstream, id, ApprovalDecision.DECLINE)
                } else {
                    approvals.approvalRequested(id, params) { decision -> answer(upstream, id, decision) }
                }
                return
            }
        }
        client.write(frame, masked = false)
    }

    private fun answer(upstream: WebSocketFrames, id: kotlinx.serialization.json.JsonElement, decision: ApprovalDecision) {
        val response = approvalResponse(id, decision)
        runCatching { upstream.write(WebSocketFrame(0x1, response.toString().encodeToByteArray()), masked = true) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        approvals.close()
        runCatching { server.close() }
        val toClose = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        toClose.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        executor.awaitTermination(1, TimeUnit.SECONDS)
        onClosed()
    }

    private fun markStartupFailure() {
        runCatching { Files.writeString(failureMarker, "native approval relay startup failed\n") }
    }

    private data class WebSocketFrame(val opcode: Int, val payload: ByteArray)

    /** Limited, unfragmented RFC 6455 reader; unsupported frames close the bridge fail-closed. */
    private class WebSocketFrames(
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
    ) {
        private val writeLock = Any()

        fun read(): WebSocketFrame? {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0 || first and 0x80 == 0) return null
            val opcode = first and 0x0F
            var length = second and 0x7F
            if (length == 126) length = readUnsignedShort()
            if (length == 127) {
                val longLength = readLong()
                if (longLength !in 0..MAX_FRAME_BYTES.toLong()) return null
                length = longLength.toInt()
            }
            if (length !in 0..MAX_FRAME_BYTES) return null
            val masked = second and 0x80 != 0
            val key = if (masked) readExact(4) else null
            val payload = readExact(length)
            if (key != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor key[it % 4].toInt()).toByte() }
            return WebSocketFrame(opcode, payload)
        }

        fun write(frame: WebSocketFrame, masked: Boolean) = synchronized(writeLock) {
            val payload = frame.payload
            output.write(0x80 or frame.opcode)
            when {
                payload.size < 126 -> output.write((if (masked) 0x80 else 0) or payload.size)
                payload.size <= 0xFFFF -> {
                    output.write((if (masked) 0x80 else 0) or 126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
                else -> {
                    output.write((if (masked) 0x80 else 0) or 127)
                    for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() ushr shift).toInt())
                }
            }
            if (masked) {
                val key = ByteArray(4).also(SecureRandom()::nextBytes)
                output.write(key)
                output.write(ByteArray(payload.size) { index -> (payload[index].toInt() xor key[index % 4].toInt()).toByte() })
            } else output.write(payload)
            output.flush()
        }

        private fun readUnsignedShort(): Int = (input.read() shl 8) or input.read()
        private fun readLong(): Long = (0 until 8).fold(0L) { value, _ -> (value shl 8) or input.read().toLong() }
        private fun readExact(length: Int): ByteArray {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(result, offset, length - offset)
                if (count < 0) throw IllegalStateException("closed WebSocket frame")
                offset += count
            }
            return result
        }
    }

    private data class Headers(val startLine: String, val headers: Map<String, String>)

    private object HttpUpgrade {
        fun read(input: BufferedInputStream): Headers {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            while (bytes.size() < MAX_HEADERS_BYTES) {
                val value = input.read()
                if (value < 0) throw IllegalStateException("closed HTTP upgrade")
                bytes.write(value)
                matched = when {
                    matched == 0 && value == '\r'.code -> 1
                    matched == 1 && value == '\n'.code -> 2
                    matched == 2 && value == '\r'.code -> 3
                    matched == 3 && value == '\n'.code -> 4
                    value == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) break
            }
            val lines = bytes.toString(Charsets.ISO_8859_1).split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                line.substringBefore(':', "").takeIf { it.isNotBlank() }?.lowercase()?.let { key -> key to line.substringAfter(':').trim() }
            }.toMap()
            return Headers(lines.firstOrNull().orEmpty(), headers)
        }

        fun accept(output: BufferedOutputStream, key: String) {
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n".encodeToByteArray())
            output.flush()
        }

        fun request(output: BufferedOutputStream, port: Int, key: String, token: String) {
            output.write("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer $token\r\n\r\n".encodeToByteArray())
            output.flush()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val MAX_HEADERS_BYTES = 32 * 1024
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024

        fun acceptKey(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".encodeToByteArray()),
        )

        fun constantTimeEquals(actual: String?, expected: String): Boolean = actual != null &&
            MessageDigest.isEqual(actual.encodeToByteArray(), expected.encodeToByteArray())
    }
}

internal fun approvalResponse(
    id: kotlinx.serialization.json.JsonElement,
    decision: ApprovalDecision,
): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", buildJsonObject { put("decision", if (decision == ApprovalDecision.ACCEPT) "accept" else "decline") })
}
