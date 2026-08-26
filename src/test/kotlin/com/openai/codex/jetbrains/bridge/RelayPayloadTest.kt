package com.openai.codex.jetbrains.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class RelayPayloadTest {
    @Test
    fun `finds only a top-level method after nested values`() {
        val payload = MemoryRelayPayload(
            """{"metadata":{"method":"item/fileChange/requestApproval","values":[{"text":"ignored"}]},"id":1,"method":"item/started","params":{}}"""
                .encodeToByteArray(),
        )

        payload.use {
            assertEquals("item/started", JsonRpcMethodScanner.find(payload))
        }
    }

    @Test
    fun `does not treat a nested plugin field as a JSON RPC method`() {
        val payload = MemoryRelayPayload(
            """{"id":"plugin-list","result":{"plugins":[{"method":"item/started"}]}}""".encodeToByteArray(),
        )

        payload.use {
            assertNull(JsonRpcMethodScanner.find(payload))
        }
    }

    @Test
    fun `deletes a spilled payload after forwarding ownership closes`() {
        val directory = Files.createTempDirectory("relay-payload-test")
        try {
            val payload = RelayPayloadSpool(directory, memoryThreshold = 4).use { spool ->
                spool.write("larger than memory".encodeToByteArray())
                spool.finish()
            }
            assertEquals(1L, Files.list(directory).use { it.count() })

            payload.close()

            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
