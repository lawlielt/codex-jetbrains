package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rewrites only the remote TUI's initialization and new-thread requests.
 * Every unrelated JSON-RPC field and message remains untouched by the relay.
 */
internal class OpenDiffSessionInjection {
    private val experimentalApiEnabled = AtomicBoolean(false)

    fun rewrite(payload: RelayPayload): RelayPayload? {
        val method = JsonRpcMethodScanner.find(payload)
        if (method !in CLIENT_METHODS) return null
        val message = payload.jsonObjectOrNull() ?: return null
        val params = message["params"] as? JsonObject ?: return null
        val rewritten = when (method) {
            "initialize" -> injectExperimentalCapability(message, params)
            "thread/start" -> injectTool(message, params)
            else -> null
        } ?: return null
        return MemoryRelayPayload(rewritten.toString().encodeToByteArray())
    }

    private fun injectExperimentalCapability(message: JsonObject, params: JsonObject): JsonObject {
        val capabilities = params["capabilities"] as? JsonObject ?: JsonObject(emptyMap())
        val updatedCapabilities = JsonObject(capabilities + ("experimentalApi" to JsonPrimitive(true)))
        experimentalApiEnabled.set(true)
        return JsonObject(message + ("params" to JsonObject(params + ("capabilities" to updatedCapabilities))))
    }

    private fun injectTool(message: JsonObject, params: JsonObject): JsonObject? {
        if (!experimentalApiEnabled.get()) return null
        val configuredTools = (params["dynamicTools"] as? JsonArray)?.toList().orEmpty()
        if (configuredTools.any { (it as? JsonObject)?.get("name") == JsonPrimitive(OpenDiffToolProtocol.TOOL_NAME) }) return null
        val existingInstructions = (params["developerInstructions"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.takeIf(String::isNotBlank)
        val instructions = listOfNotNull(existingInstructions, EDIT_INSTRUCTIONS).joinToString("\n\n")
        val updatedParams = JsonObject(
            params + mapOf(
                "dynamicTools" to JsonArray(configuredTools + OpenDiffToolProtocol.toolDefinition),
                "developerInstructions" to JsonPrimitive(instructions),
            ),
        )
        return JsonObject(message + ("params" to updatedParams))
    }

    private companion object {
        val CLIENT_METHODS = setOf("initialize", "thread/start")
        const val EDIT_INSTRUCTIONS = """
            Source changes in this project must use the openDiff dynamic tool. The workspace is read-only for model shell commands: do not use shell writes, apply_patch, or another built-in edit path for source files. For every source change, send the exact project-relative oldPath and newPath, full proposed content, and the exact full preimage. Wait for the openDiff result. If it is rejected, do not write around it; continue the same turn with an explanation or a revised proposal.
        """
    }
}
