package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class AppServerHandshakeTest {
    @Test
    fun `initialized notification is sent only after initialize response`() {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val reader = BufferedReader(InputStreamReader(serverInput, StandardCharsets.UTF_8))
        val client = JsonlRpcClient(clientInput, clientToServer, object : JsonlRpcListener {})

        val initialized = AppServerHandshake.initialize(client, "test_client", "Test Client", "1.0")
        val initialize = JsonParser.parseString(reader.readLine()).asJsonObject
        assertEquals("initialize", initialize.get("method").asString)
        assertEquals("test_client", initialize.getAsJsonObject("params").getAsJsonObject("clientInfo").get("name").asString)
        assertFalse(reader.ready())

        serverToClient.write("{\"id\":0,\"result\":{\"userAgent\":\"test\"}}\n".toByteArray())
        serverToClient.flush()
        initialized.get(2, TimeUnit.SECONDS)
        val notification = JsonParser.parseString(reader.readLine()).asJsonObject
        assertEquals("initialized", notification.get("method").asString)
        assertFalse(notification.has("id"))
        client.close()
    }
}
