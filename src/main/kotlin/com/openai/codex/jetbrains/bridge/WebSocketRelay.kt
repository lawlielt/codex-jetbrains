package com.openai.codex.jetbrains.bridge

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
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
    private val failureObserver: (Throwable) -> Unit = {},
    private val openDiffs: OpenDiffCoordinator = disabledOpenDiffCoordinator(),
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "codex-native-approval-relay").apply { isDaemon = true }
    }
    private val sockets = mutableSetOf<Socket>()
    private val upstreamReady = AtomicBoolean(false)
    private val dynamicInjection = OpenDiffSessionInjection()

    val endpoint: String = "ws://127.0.0.1:${server.localPort}"

    fun start() {
        executor.execute {
            try {
                server.soTimeout = CONNECT_TIMEOUT_MILLIS
                val client = server.accept()
                synchronized(sockets) { sockets += client }
                runConnection(client)
            } catch (error: Exception) {
                if (!closed.get()) recordFailure(error)
            } finally {
                if (!closed.get() && !upstreamReady.get()) markFailure()
                close()
            }
        }
    }

    private fun runConnection(client: Socket) {
        client.soTimeout = 0
        val clientInput = BufferedInputStream(client.getInputStream())
        val clientOutput = BufferedOutputStream(client.getOutputStream())
        val spoolDirectory = failureMarker.parent ?: Path.of(System.getProperty("java.io.tmpdir"))
        val clientFrames = WebSocketFrames(clientInput, clientOutput, spoolDirectory)
        val clientRequest = HttpUpgrade.read(clientInput)
        if (!clientRequest.startLine.startsWith("GET ") || !constantTimeEquals(clientRequest.headers["authorization"], "Bearer $relayToken")) return
        val clientKey = clientRequest.headers["sec-websocket-key"] ?: return
        if (!clientRequest.headers["upgrade"].equals("websocket", ignoreCase = true)) return
        HttpUpgrade.accept(clientOutput, clientKey)

        val upstream = Socket(InetAddress.getLoopbackAddress(), appServerPort)
        synchronized(sockets) { sockets += upstream }
        val upstreamInput = BufferedInputStream(upstream.getInputStream())
        val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
        val upstreamFrames = WebSocketFrames(upstreamInput, upstreamOutput, spoolDirectory)
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
            runCatching { clientPump.get(CLOSE_HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        } finally {
            clientPump.cancel(true)
            upstream.close()
            client.close()
        }
    }

    private fun pumpClient(client: WebSocketFrames, upstream: WebSocketFrames) {
        val fragments = FragmentedMessage(client.spoolDirectory)
        try {
            while (!closed.get()) {
                val frame = client.read() ?: throw EOFException("remote TUI closed without a WebSocket close frame")
                when (frame.opcode) {
                    0x9 -> frame.use {
                        WebSocketFrame(true, 0xA, frame.payload.copy(125)).use { response ->
                            client.write(response, masked = false)
                        }
                    }
                    0x8 -> frame.use {
                        upstream.write(frame, masked = true)
                        return
                    }
                    0x0, 0x1, 0x2 -> fragments.accept(frame)?.use { message ->
                        if (message.opcode == 0x1) handleClientText(message, upstream) else upstream.write(message, masked = true)
                    }
                    0xA -> frame.use { upstream.write(frame, masked = true) }
                    else -> frame.close().also {
                        throw WebSocketProtocolException("unsupported remote TUI WebSocket opcode")
                    }
                }
            }
        } finally {
            fragments.close()
        }
    }

    private fun handleClientText(frame: WebSocketFrame, upstream: WebSocketFrames) {
        val rewritten = dynamicInjection.rewrite(frame.payload)
        if (rewritten == null) {
            upstream.write(frame, masked = true)
        } else {
            rewritten.use { replacement ->
                WebSocketFrame(true, 0x1, replacement).use { upstream.write(it, masked = true) }
            }
        }
    }

    private fun pumpUpstream(upstream: WebSocketFrames, client: WebSocketFrames) {
        val fragments = FragmentedMessage(upstream.spoolDirectory)
        var nativeInterceptionEnabled = true
        try {
            while (!closed.get()) {
                val frame = upstream.read() ?: throw EOFException("app-server closed without a WebSocket close frame")
                when (frame.opcode) {
                    0x9 -> frame.use {
                        WebSocketFrame(true, 0xA, frame.payload.copy(125)).use { response ->
                            upstream.write(response, masked = true)
                        }
                    }
                    0x8 -> frame.use {
                        client.write(frame, masked = false)
                        return
                    }
                    0x0, 0x1, 0x2 -> fragments.accept(frame)?.use { message ->
                        if (message.opcode == 0x1 && nativeInterceptionEnabled) {
                            nativeInterceptionEnabled = handleUpstreamText(message, upstream, client)
                        } else {
                            client.write(message, masked = false)
                        }
                    }
                    0xA -> frame.use { client.write(frame, masked = false) }
                    else -> frame.close().also {
                        throw WebSocketProtocolException("unsupported app-server WebSocket opcode")
                    }
                }
            }
        } finally {
            fragments.close()
        }
    }

    /** Returns whether native interception remains safe for later messages. */
    private fun handleUpstreamText(
        frame: WebSocketFrame,
        upstream: WebSocketFrames,
        client: WebSocketFrames,
    ): Boolean {
        val method = JsonRpcMethodScanner.find(frame.payload)
        if (method !in INTERCEPTED_METHODS) {
            client.write(frame, masked = false)
            return true
        }
        val message = if (method == "item/tool/call") {
            frame.payload.jsonObjectOrNull()
        } else {
            frame.payload.readBytes(MAX_FILE_CHANGE_MESSAGE_BYTES)?.let { bytes ->
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
            }
        }
        if (message == null) {
            // Preserve the interactive CLI instead of disconnecting when a future
            // approval payload grows beyond the IDE-native preview budget.
            client.write(frame, masked = false)
            return method == "item/tool/call"
        }
        when ((message["method"] as? JsonPrimitive)?.contentOrNull) {
            "item/started" -> (message["params"] as? JsonObject)?.let(approvals::itemStarted)
            "item/completed" -> (message["params"] as? JsonObject)?.let(approvals::itemCompleted)
            "item/fileChange/requestApproval" -> {
                val id = message["id"] ?: return true
                val params = message["params"] as? JsonObject
                if (params == null) {
                    answer(upstream, id, ApprovalDecision.DECLINE)
                } else {
                    approvals.approvalRequested(id, params) { decision -> answer(upstream, id, decision) }
                }
                return true
            }
            "item/tool/call" -> {
                val id = message["id"] ?: return true
                val params = message["params"] as? JsonObject ?: run {
                    client.write(frame, masked = false)
                    return true
                }
                if ((params["tool"] as? JsonPrimitive)?.contentOrNull != OpenDiffToolProtocol.TOOL_NAME) {
                    client.write(frame, masked = false)
                    return true
                }
                openDiffs.toolRequested(id, params) { success, detail -> answerDynamic(upstream, id, success, detail) }
                return true
            }
        }
        client.write(frame, masked = false)
        return true
    }

    private fun answer(upstream: WebSocketFrames, id: kotlinx.serialization.json.JsonElement, decision: ApprovalDecision) {
        val response = approvalResponse(id, decision)
        runCatching {
            WebSocketFrame(true, 0x1, MemoryRelayPayload(response.toString().encodeToByteArray())).use {
                upstream.write(it, masked = true)
            }
        }
    }

    private fun answerDynamic(
        upstream: WebSocketFrames,
        id: kotlinx.serialization.json.JsonElement,
        success: Boolean,
        detail: String,
    ) {
        val response = dynamicToolResponse(id, success, detail)
        runCatching {
            WebSocketFrame(true, 0x1, MemoryRelayPayload(response.toString().encodeToByteArray())).use {
                upstream.write(it, masked = true)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        approvals.close()
        openDiffs.close()
        runCatching { server.close() }
        val toClose = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        toClose.forEach { runCatching { it.close() } }
        if (Thread.currentThread().name.startsWith(RELAY_THREAD_NAME)) {
            // The connection owner closes itself on a normal close frame or protocol fault.
            // Interrupting and awaiting this same worker produced the misleading 0.4.1
            // InterruptedException and can never complete until this method returns.
            executor.shutdown()
        } else {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        onClosed()
    }

    private fun recordFailure(error: Throwable) {
        // Do not attach the throwable: parser exception messages may embed JSON-RPC content.
        logger.warn("Codex native approval relay stopped before a clean close (${error.javaClass.simpleName})")
        runCatching { failureObserver(error) }
        markFailure()
    }

    private fun markFailure() {
        runCatching { Files.writeString(failureMarker, "native approval relay failed\n") }
    }

    private data class WebSocketFrame(
        val fin: Boolean,
        val opcode: Int,
        val payload: RelayPayload,
    ) : AutoCloseable {
        override fun close() = payload.close()
    }

    private class FragmentedMessage(private val spoolDirectory: Path) : AutoCloseable {
        private var opcode: Int? = null
        private var payload: RelayPayloadSpool? = null

        fun accept(frame: WebSocketFrame): WebSocketFrame? {
            if (frame.opcode == 0x0) {
                val messageOpcode = opcode ?: throw WebSocketProtocolException("unexpected continuation frame")
                appendAndClose(frame)
                if (!frame.fin) return null
                val complete = WebSocketFrame(true, messageOpcode, payload!!.finish())
                opcode = null
                payload = null
                return complete
            }
            if (opcode != null) throw WebSocketProtocolException("new data frame before fragmented message completed")
            if (frame.fin) return frame
            opcode = frame.opcode
            payload = RelayPayloadSpool(spoolDirectory)
            appendAndClose(frame)
            return null
        }

        override fun close() {
            payload?.close()
            payload = null
            opcode = null
        }

        private fun appendAndClose(frame: WebSocketFrame) {
            frame.use {
                val output = payload ?: throw WebSocketProtocolException("missing fragmented message buffer")
                output.append(frame.payload)
            }
        }
    }

    private class WebSocketProtocolException(message: String) : IllegalStateException(message)

    /** Spool-backed RFC 6455 reader; payload size no longer determines JVM heap use. */
    private class WebSocketFrames(
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
        val spoolDirectory: Path,
    ) {
        private val writeLock = Any()

        fun read(): WebSocketFrame? {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) throw EOFException("closed WebSocket frame header")
            if (first and 0x70 != 0) throw WebSocketProtocolException("unsupported WebSocket extension bits")
            val fin = first and 0x80 != 0
            val opcode = first and 0x0F
            if (opcode !in setOf(0x0, 0x1, 0x2, 0x8, 0x9, 0xA)) {
                throw WebSocketProtocolException("unsupported WebSocket opcode")
            }
            val length = when (val shortLength = second and 0x7F) {
                126 -> readUnsignedShort().toLong()
                127 -> readLong()
                else -> shortLength.toLong()
            }
            if (length < 0) throw WebSocketProtocolException("invalid WebSocket frame length")
            if (opcode >= 0x8 && (!fin || length > 125)) {
                throw WebSocketProtocolException("invalid WebSocket control frame")
            }
            val masked = second and 0x80 != 0
            val key = if (masked) readExact(4) else null
            val spool = RelayPayloadSpool(spoolDirectory)
            return try {
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var remaining = length
                var payloadOffset = 0L
                while (remaining > 0) {
                    val requested = minOf(remaining, buffer.size.toLong()).toInt()
                    val count = input.read(buffer, 0, requested)
                    if (count < 0) throw EOFException("closed WebSocket frame payload")
                    if (key != null) {
                        repeat(count) { index ->
                            buffer[index] = (buffer[index].toInt() xor key[((payloadOffset + index) % 4).toInt()].toInt()).toByte()
                        }
                    }
                    spool.write(buffer, 0, count)
                    payloadOffset += count
                    remaining -= count
                }
                WebSocketFrame(fin, opcode, spool.finish())
            } catch (error: Exception) {
                spool.close()
                throw error
            }
        }

        fun write(frame: WebSocketFrame, masked: Boolean) = synchronized(writeLock) {
            val payload = frame.payload
            output.write((if (frame.fin) 0x80 else 0) or frame.opcode)
            when {
                payload.size < 126 -> output.write((if (masked) 0x80 else 0) or payload.size.toInt())
                payload.size <= 0xFFFF -> {
                    output.write((if (masked) 0x80 else 0) or 126)
                    output.write((payload.size ushr 8).toInt())
                    output.write(payload.size.toInt())
                }
                else -> {
                    output.write((if (masked) 0x80 else 0) or 127)
                    for (shift in 56 downTo 0 step 8) output.write((payload.size ushr shift).toInt())
                }
            }
            val key = if (masked) ByteArray(4).also(SecureRandom()::nextBytes).also(output::write) else null
            payload.openStream().use { stream ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var written = 0L
                while (written < payload.size) {
                    val requested = minOf(payload.size - written, buffer.size.toLong()).toInt()
                    val count = stream.read(buffer, 0, requested)
                    if (count < 0) throw EOFException("payload ended before its declared size")
                    if (key != null) {
                        repeat(count) { index ->
                            buffer[index] = (buffer[index].toInt() xor key[((written + index) % 4).toInt()].toInt()).toByte()
                        }
                    }
                    output.write(buffer, 0, count)
                    written += count
                }
            }
            output.flush()
        }

        private fun readUnsignedShort(): Int {
            val high = input.read()
            val low = input.read()
            if (high < 0 || low < 0) throw EOFException("closed extended WebSocket frame length")
            return (high shl 8) or low
        }

        private fun readLong(): Long {
            val first = input.read()
            if (first < 0) throw EOFException("closed extended WebSocket frame length")
            if (first and 0x80 != 0) throw WebSocketProtocolException("invalid WebSocket frame length")
            return (1 until 8).fold(first.toLong()) { value, _ ->
                val next = input.read()
                if (next < 0) throw EOFException("closed extended WebSocket frame length")
                (value shl 8) or next.toLong()
            }
        }
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
        val logger: Logger = Logger.getInstance(WebSocketRelay::class.java)
        const val RELAY_THREAD_NAME = "codex-native-approval-relay"
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val CLOSE_HANDSHAKE_TIMEOUT_MILLIS = 1_000L
        const val MAX_HEADERS_BYTES = 32 * 1024
        const val MAX_FILE_CHANGE_MESSAGE_BYTES = 4 * 1024 * 1024
        const val STREAM_BUFFER_BYTES = 64 * 1024
        val INTERCEPTED_METHODS = setOf(
            "item/started",
            "item/completed",
            "item/fileChange/requestApproval",
            "item/tool/call",
        )

        fun acceptKey(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".encodeToByteArray()),
        )

        fun constantTimeEquals(actual: String?, expected: String): Boolean = actual != null &&
            MessageDigest.isEqual(actual.encodeToByteArray(), expected.encodeToByteArray())

        private fun disabledOpenDiffCoordinator(): OpenDiffCoordinator = OpenDiffCoordinator(
            OpenDiffValidator(Path.of(".").toAbsolutePath(), object : OpenDiffSnapshotStore {
                override fun read(path: Path): String? = null
                override fun hasUnsavedDocument(path: Path): Boolean = false
            }),
            OpenDiffPresenter { _, complete -> complete(OpenDiffCompletion.Reject) },
            OpenDiffWriter { _, _ -> false },
        )
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

internal fun dynamicToolResponse(
    id: kotlinx.serialization.json.JsonElement,
    success: Boolean,
    detail: String,
): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put(
        "result",
        buildJsonObject {
            put("success", success)
            put(
                "contentItems",
                kotlinx.serialization.json.JsonArray(
                    listOf(buildJsonObject {
                        put("type", "inputText")
                        put("text", detail)
                    }),
                ),
            )
        },
    )
}
