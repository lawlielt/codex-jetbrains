package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

object AppServerHandshake {
    fun initialize(
        client: JsonlRpcClient,
        name: String,
        title: String,
        version: String,
    ): CompletableFuture<JsonElement> {
        val params = JsonObject().apply {
            add("clientInfo", JsonObject().apply {
                addProperty("name", name)
                addProperty("title", title)
                addProperty("version", version)
            })
        }
        return client.request("initialize", params).thenApply { result ->
            client.notify("initialized", JsonObject())
            result
        }
    }
}

object ThreadIdentity {
    fun resumeParams(threadId: String?): JsonObject? = threadId?.takeIf { it.isNotBlank() }?.let {
        JsonObject().apply { addProperty("threadId", it) }
    }

    fun extractThreadId(result: JsonElement): String? =
        result.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("thread")?.string("id")
}
