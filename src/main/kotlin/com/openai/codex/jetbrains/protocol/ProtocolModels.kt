package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

data class ReasoningEffortOption(val id: String, val description: String)

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val defaultEffort: String?,
    val efforts: List<ReasoningEffortOption>,
    val isDefault: Boolean,
)

object ModelCatalog {
    fun parse(result: JsonElement): List<ModelDescriptor> {
        val data = result.asJsonObject.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (obj.get("hidden")?.asBoolean == true) return@mapNotNull null
            val id = obj.string("model") ?: obj.string("id") ?: return@mapNotNull null
            val efforts = obj.getAsJsonArray("supportedReasoningEfforts")?.mapNotNull efforts@{ effort ->
                val effortObj = effort.takeIf { it.isJsonObject }?.asJsonObject ?: return@efforts null
                val effortId = effortObj.string("reasoningEffort") ?: return@efforts null
                ReasoningEffortOption(effortId, effortObj.string("description").orEmpty())
            }.orEmpty()
            ModelDescriptor(
                id = id,
                displayName = obj.string("displayName") ?: id,
                defaultEffort = obj.string("defaultReasoningEffort"),
                efforts = efforts,
                isDefault = obj.get("isDefault")?.asBoolean == true,
            )
        }
    }
}

data class StreamUpdate(
    val threadId: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val assistantDelta: String? = null,
    val activity: String? = null,
    val completed: Boolean = false,
    val diff: String? = null,
)

class CodexEventReducer {
    private val textByItem = ConcurrentHashMap<String, StringBuilder>()

    fun consume(message: InboundMessage.Notification): StreamUpdate? {
        val params = message.params.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return when (message.method) {
            "item/agentMessage/delta" -> {
                val itemId = params.string("itemId") ?: return null
                val delta = params.string("delta").orEmpty()
                textByItem.computeIfAbsent(itemId) { StringBuilder() }.append(delta)
                StreamUpdate(params.string("threadId"), params.string("turnId"), itemId, assistantDelta = delta)
            }
            "item/started", "item/completed" -> {
                val item = params.getAsJsonObject("item") ?: return null
                val type = item.string("type") ?: "agent activity"
                val detail = when (type) {
                    "commandExecution" -> item.string("command")?.let { "Command: $it" }
                    "fileChange" -> "Proposed file changes"
                    "reasoning" -> "Reasoning"
                    "webSearch" -> item.string("query")?.let { "Web search: $it" }
                    else -> type.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                } ?: type
                StreamUpdate(
                    threadId = params.string("threadId"),
                    turnId = params.string("turnId"),
                    itemId = item.string("id"),
                    activity = if (message.method.endsWith("completed")) "$detail completed" else detail,
                )
            }
            "turn/started" -> {
                val turn = params.getAsJsonObject("turn")
                StreamUpdate(params.string("threadId"), turn?.string("id"), activity = "Turn started")
            }
            "turn/completed" -> {
                val turn = params.getAsJsonObject("turn")
                StreamUpdate(
                    threadId = params.string("threadId"),
                    turnId = turn?.string("id"),
                    activity = "Turn ${turn?.string("status") ?: "completed"}",
                    completed = true,
                )
            }
            "turn/diff/updated" -> StreamUpdate(
                params.string("threadId"), params.string("turnId"), diff = params.string("diff"), activity = "Diff updated",
            )
            "warning" -> StreamUpdate(params.string("threadId"), activity = params.string("message") ?: "Codex warning")
            "error" -> StreamUpdate(params.string("threadId"), activity = extractError(params))
            else -> null
        }
    }

    fun accumulatedText(itemId: String): String = textByItem[itemId]?.toString().orEmpty()

    private fun extractError(params: JsonObject): String {
        val error = params.getAsJsonObject("error")
        return error?.string("message") ?: "Codex turn failed"
    }
}

internal fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
