package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
    fun `injects openDiff and correlates its dynamic result without consuming ordinary approvals`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { appServer ->
            val upstreamDone = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val root = java.nio.file.Files.createTempDirectory("relay-open-diff")
            val source = root.resolve("source.txt")
            java.nio.file.Files.writeString(source, "before\n")
            val appThread = Thread {
                runCatching {
                    appServer.accept().use { socket ->
                        val request = readHeaders(socket.getInputStream())
                        val key = request.headers.getValue("sec-websocket-key")
                        socket.getOutputStream().write(upgradeResponse(key).encodeToByteArray())
                        socket.getOutputStream().flush()
                        val initialize = Json.parseToJsonElement(readFrame(socket.getInputStream()).decodeToString()).jsonObject
                        assertEquals("true", initialize["params"]?.jsonObject?.get("capabilities")?.jsonObject?.get("experimentalApi")?.jsonPrimitive?.content)
                        val start = Json.parseToJsonElement(readFrame(socket.getInputStream()).decodeToString()).jsonObject
                        assertEquals("openDiff", start["params"]?.jsonObject?.get("dynamicTools")?.jsonArray?.single()?.jsonObject?.get("name")?.jsonPrimitive?.content)
                        val ordinaryClientMessage = readFrame(socket.getInputStream())
                        assertTrue(ordinaryClientMessage.contentEquals("""{"jsonrpc":"2.0","method":"model/list","params":{}}""".encodeToByteArray()))
                        writeFrame(socket.getOutputStream(), false, """{"jsonrpc":"2.0","method":"item/started","params":{"threadId":"thread","turnId":"turn","item":{"id":"dynamic-item","type":"dynamicToolCall","tool":"openDiff","arguments":{}}}}""".encodeToByteArray())
                        writeFrame(socket.getOutputStream(), false, """{"jsonrpc":"2.0","id":"tool-request","method":"item/tool/call","params":{"callId":"call","threadId":"thread","turnId":"turn","tool":"openDiff","arguments":{"operation":"update","oldPath":"source.txt","newPath":"source.txt","content":"proposed\n","preimage":"before\n"}}}""".encodeToByteArray())
                        val dynamicResponse = Json.parseToJsonElement(readFrame(socket.getInputStream()).decodeToString()).jsonObject
                        assertEquals("tool-request", dynamicResponse["id"]?.jsonPrimitive?.content)
                        assertEquals("true", dynamicResponse["result"]?.jsonObject?.get("success")?.jsonPrimitive?.content)
                        writeFrame(socket.getOutputStream(), false, """{"jsonrpc":"2.0","id":9,"method":"item/commandExecution/requestApproval","params":{"command":"pwd"}}""".encodeToByteArray())
                        writeFrame(socket.getOutputStream(), false, byteArrayOf(), opcode = 0x8)
                        assertEquals(0x8, readTestFrame(socket.getInputStream()).opcode)
                    }
                }.onFailure { failures += it }
                upstreamDone.countDown()
            }
            appThread.start()

            var committed: String? = null
            val coordinator = OpenDiffCoordinator(
                OpenDiffValidator(root, object : OpenDiffSnapshotStore {
                    override fun read(path: java.nio.file.Path): String? = if (path == source) java.nio.file.Files.readString(path) else null
                    override fun hasUnsavedDocument(path: java.nio.file.Path): Boolean = false
                }),
                OpenDiffPresenter { _, complete -> complete(OpenDiffCompletion.Apply("reviewer-edited\n")) },
                OpenDiffWriter { _, content -> committed = content; true },
            )
            val relay = WebSocketRelay(
                appServer.localPort,
                "remote-token",
                "app-token",
                FileChangeApprovalCoordinator(
                    FileChangeValidator(root, object : FileSnapshotStore {
                        override fun read(path: java.nio.file.Path): String? = null
                        override fun hasUnsavedDocument(path: java.nio.file.Path): Boolean = false
                    }),
                    NativeDiffPresenter { _, complete -> complete(ApprovalDecision.DECLINE) },
                ),
                java.nio.file.Files.createTempDirectory("relay-dynamic-test").resolve("relay-failed"),
                onClosed = {},
                openDiffs = coordinator,
            )
            relay.start()
            Socket("127.0.0.1", relay.endpoint.substringAfterLast(':').toInt()).use { remote ->
                val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
                remote.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer remote-token\r\n\r\n".encodeToByteArray(),
                )
                remote.getOutputStream().flush()
                assertTrue(readHeaders(remote.getInputStream()).startLine.startsWith("HTTP/1.1 101"))
                writeFrame(remote.getOutputStream(), true, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""".encodeToByteArray())
                writeFrame(remote.getOutputStream(), true, """{"jsonrpc":"2.0","id":2,"method":"thread/start","params":{"developerInstructions":"existing"}}""".encodeToByteArray())
                writeFrame(remote.getOutputStream(), true, """{"jsonrpc":"2.0","method":"model/list","params":{}}""".encodeToByteArray())
                val dynamicStarted = Json.parseToJsonElement(readFrame(remote.getInputStream()).decodeToString()).jsonObject
                assertEquals("item/started", dynamicStarted["method"]?.jsonPrimitive?.content)
                val forwardedApproval = Json.parseToJsonElement(readFrame(remote.getInputStream()).decodeToString()).jsonObject
                assertEquals("item/commandExecution/requestApproval", forwardedApproval["method"]?.jsonPrimitive?.content)
                assertEquals(0x8, readTestFrame(remote.getInputStream()).opcode)
                writeFrame(remote.getOutputStream(), true, byteArrayOf(), opcode = 0x8)
            }
            assertTrue("fake app-server did not finish", upstreamDone.await(5, TimeUnit.SECONDS))
            relay.close()
            assertEquals("reviewer-edited\n", committed)
            assertTrue(failures.isEmpty())
        }
    }

    @Test
    fun `falls back to terminal approvals instead of disconnecting on oversized native message`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { appServer ->
            val upstreamDone = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val relayFailures = mutableListOf<Throwable>()
            val prefix = """{"jsonrpc":"2.0","method":"item/started","params":{},"padding":""".encodeToByteArray()
            val suffix = """"}""".encodeToByteArray()
            val oversized = prefix + ByteArray(4 * 1024 * 1024 + 1 - prefix.size - suffix.size) { 'x'.code.toByte() } + suffix
            val approval = """{"jsonrpc":"2.0","id":17,"method":"item/fileChange/requestApproval","params":{"itemId":"item"}}""".encodeToByteArray()
            val appThread = Thread {
                runCatching {
                    appServer.accept().use { socket ->
                        val request = readHeaders(socket.getInputStream())
                        val key = request.headers.getValue("sec-websocket-key")
                        socket.getOutputStream().write(upgradeResponse(key).encodeToByteArray())
                        socket.getOutputStream().flush()
                        writeFrame(socket.getOutputStream(), false, oversized)
                        writeFrame(socket.getOutputStream(), false, approval)
                        writeFrame(socket.getOutputStream(), false, byteArrayOf(), opcode = 0x8)
                        assertEquals(0x8, readTestFrame(socket.getInputStream()).opcode)
                    }
                }.onFailure { failures += it }
                upstreamDone.countDown()
            }
            appThread.start()

            val marker = java.nio.file.Files.createTempDirectory("relay-oversized-native-test").resolve("relay-failed")
            val relay = WebSocketRelay(
                appServer.localPort,
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
                failureObserver = { relayFailures += it },
            )
            relay.start()
            Socket("127.0.0.1", relay.endpoint.substringAfterLast(':').toInt()).use { remote ->
                val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
                remote.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer remote-token\r\n\r\n".encodeToByteArray(),
                )
                remote.getOutputStream().flush()
                assertTrue(readHeaders(remote.getInputStream()).startLine.startsWith("HTTP/1.1 101"))
                assertTrue(oversized.contentEquals(readFrame(remote.getInputStream())))
                assertTrue(approval.contentEquals(readFrame(remote.getInputStream())))
                assertEquals(0x8, readTestFrame(remote.getInputStream()).opcode)
                writeFrame(remote.getOutputStream(), true, byteArrayOf(), opcode = 0x8)
            }
            assertTrue("fake app-server did not finish", upstreamDone.await(10, TimeUnit.SECONDS))
            relay.close()
            assertTrue(failures.isEmpty())
            assertTrue(relayFailures.isEmpty())
            assertTrue(java.nio.file.Files.notExists(marker))
        }
    }

    @Test
    fun `streams plugin catalog response larger than the legacy frame limit`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { appServer ->
            val upstreamDone = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val relayFailures = mutableListOf<Throwable>()
            val prefix = """{"jsonrpc":"2.0","id":"plugin-list","result":{"marketplaces":[{"plugins":[{"icon":""".encodeToByteArray()
            val suffix = """"}]}],"nextCursor":null}}""".encodeToByteArray()
            val payload = prefix + ByteArray(6_577_006 - prefix.size - suffix.size) { 'x'.code.toByte() } + suffix
            val appThread = Thread {
                runCatching {
                    appServer.accept().use { socket ->
                        val request = readHeaders(socket.getInputStream())
                        val key = request.headers.getValue("sec-websocket-key")
                        socket.getOutputStream().write(upgradeResponse(key).encodeToByteArray())
                        socket.getOutputStream().flush()
                        writeFrame(socket.getOutputStream(), false, payload)
                        writeFrame(socket.getOutputStream(), false, byteArrayOf(), opcode = 0x8)
                        assertEquals(0x8, readTestFrame(socket.getInputStream()).opcode)
                    }
                }.onFailure { failures += it }
                upstreamDone.countDown()
            }
            appThread.start()

            val root = java.nio.file.Path.of("project").toAbsolutePath().normalize()
            val marker = java.nio.file.Files.createTempDirectory("relay-large-catalog-test").resolve("relay-failed")
            val relay = WebSocketRelay(
                appServer.localPort,
                "remote-token",
                "app-token",
                FileChangeApprovalCoordinator(
                    FileChangeValidator(root, object : FileSnapshotStore {
                        override fun read(path: java.nio.file.Path): String? = null
                        override fun hasUnsavedDocument(path: java.nio.file.Path): Boolean = false
                    }),
                    NativeDiffPresenter { _, complete -> complete(ApprovalDecision.DECLINE) },
                ),
                marker,
                onClosed = {},
                failureObserver = { relayFailures += it },
            )
            relay.start()
            Socket("127.0.0.1", relay.endpoint.substringAfterLast(':').toInt()).use { remote ->
                val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
                remote.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer remote-token\r\n\r\n".encodeToByteArray(),
                )
                remote.getOutputStream().flush()
                assertTrue(readHeaders(remote.getInputStream()).startLine.startsWith("HTTP/1.1 101"))
                assertTrue(payload.contentEquals(readFrame(remote.getInputStream())))
                assertEquals(0x8, readTestFrame(remote.getInputStream()).opcode)
                writeFrame(remote.getOutputStream(), true, byteArrayOf(), opcode = 0x8)
            }
            assertTrue("fake app-server did not finish", upstreamDone.await(10, TimeUnit.SECONDS))
            relay.close()
            assertTrue(failures.isEmpty())
            assertTrue(relayFailures.isEmpty())
            assertTrue(java.nio.file.Files.notExists(marker))
        }
    }

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
                        writeFrame(socket.getOutputStream(), false, byteArrayOf(), opcode = 0x8)
                        assertEquals(0x8, readTestFrame(socket.getInputStream()).opcode)
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
                assertEquals(0x8, readTestFrame(remote.getInputStream()).opcode)
                writeFrame(remote.getOutputStream(), true, byteArrayOf(), opcode = 0x8)
            }
            assertTrue("fake app-server did not finish", upstreamDone.await(5, TimeUnit.SECONDS))
            relay.close()
            assertTrue(failures.isEmpty())
        }
    }

    @Test
    fun `reassembles fragmented app-server text before intercepting approval`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { appServer ->
            val upstreamDone = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val relayFailures = mutableListOf<Throwable>()
            val appThread = Thread {
                runCatching {
                    appServer.accept().use { socket ->
                        val request = readHeaders(socket.getInputStream())
                        val key = request.headers.getValue("sec-websocket-key")
                        socket.getOutputStream().write(upgradeResponse(key).encodeToByteArray())
                        socket.getOutputStream().flush()

                        val started = """{"jsonrpc":"2.0","method":"item/started","params":{"threadId":"thread","turnId":"turn","item":{"id":"item","type":"fileChange","changes":[{"path":"file.txt","kind":"update","diff":"@@ -1,1 +1,1 @@\n-before\n+after"}]}}}""".encodeToByteArray()
                        val startedSplit = started.size / 2
                        writeFrame(socket.getOutputStream(), false, started.copyOfRange(0, startedSplit), fin = false)
                        writeFrame(socket.getOutputStream(), false, started.copyOfRange(startedSplit, started.size), opcode = 0x0)

                        val approval = """{"jsonrpc":"2.0","id":11,"method":"item/fileChange/requestApproval","params":{"threadId":"thread","turnId":"turn","itemId":"item"}}""".encodeToByteArray()
                        val approvalSplit = approval.size / 2
                        writeFrame(socket.getOutputStream(), false, approval.copyOfRange(0, approvalSplit), fin = false)
                        writeFrame(socket.getOutputStream(), false, approval.copyOfRange(approvalSplit, approval.size), opcode = 0x0)

                        val response = Json.parseToJsonElement(readFrame(socket.getInputStream()).decodeToString()).jsonObject
                        assertEquals("11", response["id"]?.jsonPrimitive?.content)
                        assertEquals("accept", response["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content)
                        writeFrame(socket.getOutputStream(), false, """{"jsonrpc":"2.0","method":"thread/status/changed","params":{}}""".encodeToByteArray())
                        writeFrame(socket.getOutputStream(), false, byteArrayOf(), opcode = 0x8)
                        assertEquals(0x8, readTestFrame(socket.getInputStream()).opcode)
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
                java.nio.file.Files.createTempDirectory("relay-fragment-test").resolve("relay-failed"),
                onClosed = {},
                failureObserver = { relayFailures += it },
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
                val status = Json.parseToJsonElement(readFrame(remote.getInputStream()).decodeToString()).jsonObject
                assertEquals("thread/status/changed", status["method"]?.jsonPrimitive?.content)
                assertEquals(0x8, readTestFrame(remote.getInputStream()).opcode)
                writeFrame(remote.getOutputStream(), true, byteArrayOf(), opcode = 0x8)
            }
            assertTrue("fake app-server did not finish", upstreamDone.await(5, TimeUnit.SECONDS))
            relay.close()
            assertTrue(failures.isEmpty())
            assertTrue(relayFailures.isEmpty())
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

    private fun writeFrame(
        output: OutputStream,
        masked: Boolean,
        payload: ByteArray,
        fin: Boolean = true,
        opcode: Int = 0x1,
    ) {
        output.write((if (fin) 0x80 else 0) or opcode)
        val maskBit = if (masked) 0x80 else 0
        if (payload.size < 126) {
            output.write(maskBit or payload.size)
        } else if (payload.size <= 0xFFFF) {
            output.write(maskBit or 126)
            output.write(payload.size ushr 8)
            output.write(payload.size)
        } else {
            output.write(maskBit or 127)
            for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() ushr shift).toInt())
        }
        if (masked) {
            val key = byteArrayOf(1, 2, 3, 4)
            output.write(key)
            output.write(ByteArray(payload.size) { index -> (payload[index].toInt() xor key[index % 4].toInt()).toByte() })
        } else output.write(payload)
        output.flush()
    }

    private data class TestFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: InputStream): ByteArray = readTestFrame(input).payload

    private fun readTestFrame(input: InputStream): TestFrame {
        val first = input.read()
        check(first >= 0)
        val lengthFlag = input.read()
        val masked = lengthFlag and 0x80 != 0
        val longLength = when (val shortLength = lengthFlag and 0x7F) {
            126 -> ((input.read() shl 8) or input.read()).toLong()
            127 -> (0 until 8).fold(0L) { value, _ -> (value shl 8) or input.read().toLong() }
            else -> shortLength.toLong()
        }
        check(longLength <= Int.MAX_VALUE)
        val length = longLength.toInt()
        val key = if (masked) ByteArray(4).also { input.readNBytes(it, 0, 4) } else null
        val payload = ByteArray(length).also { bytes ->
            input.readNBytes(bytes, 0, length)
            if (key != null) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor key[it % 4].toInt()).toByte() }
        }
        return TestFrame(first and 0x80 != 0, first and 0x0F, payload)
    }
}
