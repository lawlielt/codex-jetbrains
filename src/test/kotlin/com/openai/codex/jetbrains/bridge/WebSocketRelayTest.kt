package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebSocketRelayTest {
    @Test
    fun `relays ordinary approvals and answers correlated file approval on upstream connection`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { appServer ->
            val upstreamDone = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val appThread = Thread {
                runCatching {
                    appServer.accept().use { socket ->
                        val request = readHeaders(socket.getInputStream())
                        assertEquals("Bearer app-token", request.headers.getValue("authorization"))
                        val key = request.headers.getValue("sec-websocket-key")
                        socket.getOutputStream().write(upgradeResponse(key).encodeToByteArray())
                        socket.getOutputStream().flush()
                        writeFrame(socket.getOutputStream(), false, """{"jsonrpc":"2.0","method":"item/started","params":{"threadId":"thread","turnId":"turn","item":{"id":"item","type":"fileChange","changes":[{"path":"file.txt","kind":"update","diff":"@@ -1,1 +1,1 @@\n-before\n+after"}]}}}""".encodeToByteArray())
                        writeFrame(socket.getOutputStream(), false, """{"jsonrpc":"2.0","id":7,"method":"item/fileChange/requestApproval","params":{"threadId":"thread","turnId":"turn","itemId":"item"}}""".encodeToByteArray())
                        val response = Json.parseToJsonElement(readFrame(socket.getInputStream()).decodeToString()).jsonObject
                        assertEquals("7", response["id"]?.jsonPrimitive?.content)
                        assertEquals("accept", response["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content)
                        writeFrame(socket.getOutputStream(), false, """{"jsonrpc":"2.0","id":9,"method":"item/commandExecution/requestApproval","params":{"command":"pwd"}}""".encodeToByteArray())
                    }
                }.onFailure { failures += it }
                upstreamDone.countDown()
            }
            appThread.start()

            val root = java.nio.file.Path.of("project").toAbsolutePath().normalize()
            val relay = WebSocketRelay(
                appServer.localPort,
                "remote-token",
                "app-token",
                FileChangeApprovalCoordinator(
                    FileChangeValidator(root, object : FileSnapshotStore {
                        override fun read(path: java.nio.file.Path): String? = if (path == root.resolve("file.txt")) "before\n" else null
                        override fun hasUnsavedDocument(path: java.nio.file.Path): Boolean = false
                    }),
                    NativeDiffPresenter { _, complete -> complete(ApprovalDecision.ACCEPT) },
                ),
                java.nio.file.Files.createTempDirectory("relay-test").resolve("relay-failed"),
                onClosed = {},
            )
            relay.start()
            Socket("127.0.0.1", relay.endpoint.substringAfterLast(':').toInt()).use { remote ->
                val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
                remote.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer remote-token\r\n\r\n".encodeToByteArray(),
                )
                remote.getOutputStream().flush()
                assertTrue(readHeaders(remote.getInputStream()).startLine.startsWith("HTTP/1.1 101"))
                val started = Json.parseToJsonElement(readFrame(remote.getInputStream()).decodeToString()).jsonObject
                assertEquals("item/started", started["method"]?.jsonPrimitive?.content)
                val forwarded = Json.parseToJsonElement(readFrame(remote.getInputStream()).decodeToString()).jsonObject
                assertEquals("item/commandExecution/requestApproval", forwarded["method"]?.jsonPrimitive?.content)
            }
            assertTrue("fake app-server did not finish", upstreamDone.await(5, TimeUnit.SECONDS))
            relay.close()
            assertTrue(failures.isEmpty())
        }
    }

    @Test
    fun `marks a pre-ready upstream fault so the shell can fall back`() {
        val unusedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val marker = java.nio.file.Files.createTempDirectory("relay-fault").resolve("relay-failed")
        val relay = WebSocketRelay(
            unusedPort,
            "remote-token",
            "app-token",
            FileChangeApprovalCoordinator(
                FileChangeValidator(java.nio.file.Path.of("project"), object : FileSnapshotStore {
                    override fun read(path: java.nio.file.Path): String? = null
                    override fun hasUnsavedDocument(path: java.nio.file.Path): Boolean = false
                }),
                NativeDiffPresenter { _, complete -> complete(ApprovalDecision.DECLINE) },
            ),
            marker,
            onClosed = {},
        )
        relay.start()
        Socket("127.0.0.1", relay.endpoint.substringAfterLast(':').toInt()).use { remote ->
            remote.getOutputStream().write(
                "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer remote-token\r\n\r\n".encodeToByteArray(),
            )
            remote.getOutputStream().flush()
        }
        repeat(50) {
            if (java.nio.file.Files.exists(marker)) return@repeat
            Thread.sleep(20)
        }
        relay.close()
        assertTrue(java.nio.file.Files.exists(marker))
    }

    private data class HeaderBlock(val startLine: String, val headers: Map<String, String>)

    private fun readHeaders(input: InputStream): HeaderBlock {
        val bytes = StringBuilder()
        while (!bytes.endsWith("\r\n\r\n")) bytes.append(input.read().toChar())
        val lines = bytes.toString().split("\r\n")
        return HeaderBlock(lines.first(), lines.drop(1).mapNotNull { line ->
            line.substringBefore(':', "").takeIf { it.isNotEmpty() }?.lowercase()?.let { it to line.substringAfter(':').trim() }
        }.toMap())
    }

    private fun upgradeResponse(key: String): String = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept(key)}\r\n\r\n"

    private fun accept(key: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".encodeToByteArray()),
    )

    private fun writeFrame(output: OutputStream, masked: Boolean, payload: ByteArray) {
        output.write(0x81)
        val maskBit = if (masked) 0x80 else 0
        if (payload.size < 126) {
            output.write(maskBit or payload.size)
        } else {
            output.write(maskBit or 126)
            output.write(payload.size ushr 8)
            output.write(payload.size)
        }
        if (masked) {
            val key = byteArrayOf(1, 2, 3, 4)
            output.write(key)
            output.write(ByteArray(payload.size) { index -> (payload[index].toInt() xor key[index % 4].toInt()).toByte() })
        } else output.write(payload)
        output.flush()
    }

    private fun readFrame(input: InputStream): ByteArray {
        check(input.read() and 0x0F == 1)
        val lengthFlag = input.read()
        val masked = lengthFlag and 0x80 != 0
        val length = when (val shortLength = lengthFlag and 0x7F) {
            126 -> (input.read() shl 8) or input.read()
            else -> shortLength
        }
        val key = if (masked) ByteArray(4).also { input.readNBytes(it, 0, 4) } else null
        return ByteArray(length).also { bytes ->
            input.readNBytes(bytes, 0, length)
            if (key != null) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor key[it % 4].toInt()).toByte() }
        }
    }
}
