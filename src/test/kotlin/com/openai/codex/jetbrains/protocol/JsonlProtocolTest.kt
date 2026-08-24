package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JsonlProtocolTest {
    @Test(timeout = 3_000)
    fun `close promptly unblocks a waiting input read`() {
        val input = CloseAwareBlockingInputStream()
        val client = JsonlRpcClient(input, ByteArrayOutputStream(), object : JsonlRpcListener {})
        assertTrue("reader did not start", input.readStarted.await(1, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        client.close()
        val elapsedNanos = System.nanoTime() - startedAt

        assertTrue("close took ${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)} ms", elapsedNanos < TimeUnit.SECONDS.toNanos(1))
    }

    @Test
    fun `frames one request per line without jsonrpc header and correlates response`() {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val client = JsonlRpcClient(clientInput, clientToServer, object : JsonlRpcListener {})
        val future = client.request("model/list", JsonObject().apply { addProperty("limit", 20) })

        val line = BufferedReader(InputStreamReader(serverInput, StandardCharsets.UTF_8)).readLine()
        val request = JsonParser.parseString(line).asJsonObject
        assertEquals("model/list", request.get("method").asString)
        assertEquals(0, request.get("id").asInt)
        assertFalse(request.has("jsonrpc"))

        serverToClient.write("{\"id\":0,\"result\":{\"data\":[]}}\n".toByteArray())
        serverToClient.flush()
        assertEquals(0, future.get(2, TimeUnit.SECONDS).asJsonObject.getAsJsonArray("data").size())
        client.close()
    }

    @Test
    fun `malformed lines are isolated and later notifications still arrive`() {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val malformed = CountDownLatch(1)
        val notified = CountDownLatch(1)
        val client = JsonlRpcClient(clientInput, PipedOutputStream(PipedInputStream()), object : JsonlRpcListener {
            override fun onMalformedMessage(rawLine: String, error: MalformedMessageException) {
                malformed.countDown()
            }

            override fun onNotification(message: InboundMessage.Notification) {
                if (message.method == "turn/started") notified.countDown()
            }
        })
        serverToClient.write("not-json\n{\"method\":\"turn/started\",\"params\":{}}\n".toByteArray())
        serverToClient.flush()
        assertTrue(malformed.await(2, TimeUnit.SECONDS))
        assertTrue(notified.await(2, TimeUnit.SECONDS))
        client.close()
    }

    @Test(expected = MalformedMessageException::class)
    fun `rejects object with neither method nor id`() {
        JsonlProtocol.parse("{\"params\":{}}")
    }

    private class CloseAwareBlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            closed.await()
            return -1
        }

        override fun close() {
            closed.countDown()
        }
    }
}
