package com.openai.codex.jetbrains.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

enum class ApprovalKind { COMMAND, NETWORK, FILE_CHANGE, PERMISSIONS }

data class ApprovalScope(val threadId: String, val turnId: String, val itemId: String)

data class ApprovalRequest(
    val requestId: JsonElement,
    val kind: ApprovalKind,
    val scope: ApprovalScope,
    val params: JsonObject,
    val proposedChanges: List<ProposedFileChange> = emptyList(),
)

data class ProposedFileChange(val path: String, val kind: String, val diff: String)

class ApprovalRouter {
    private val pending = ConcurrentHashMap<String, ApprovalRequest>()

    fun route(message: InboundMessage.Request, fileChanges: Map<String, List<ProposedFileChange>> = emptyMap()): ApprovalRequest? {
        val params = message.params.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val kind = when (message.method) {
            "item/commandExecution/requestApproval" ->
                if (params.get("networkApprovalContext")?.isJsonObject == true) ApprovalKind.NETWORK else ApprovalKind.COMMAND
            "item/fileChange/requestApproval" -> ApprovalKind.FILE_CHANGE
            "item/permissions/requestApproval" -> ApprovalKind.PERMISSIONS
            else -> return null
        }
        val scope = ApprovalScope(
            threadId = params.string("threadId") ?: return null,
            turnId = params.string("turnId") ?: return null,
            itemId = params.string("itemId") ?: return null,
        )
        val request = ApprovalRequest(
            requestId = message.id.deepCopy(),
            kind = kind,
            scope = scope,
            params = params.deepCopy(),
            proposedChanges = fileChanges[scope.itemId].orEmpty(),
        )
        pending[JsonlProtocol.idKey(message.id)] = request
        return request
    }

    fun complete(request: ApprovalRequest, result: JsonObject): Pair<JsonElement, JsonObject> {
        val key = JsonlProtocol.idKey(request.requestId)
        val current = pending[key] ?: throw IllegalStateException("Approval request is no longer pending")
        require(current.scope == request.scope && current.kind == request.kind) {
            "Approval response scope does not match the pending thread/turn/item"
        }
        check(pending.remove(key, current)) { "Approval request was already resolved" }
        return current.requestId to result
    }

    fun resolved(requestId: JsonElement) {
        pending.remove(JsonlProtocol.idKey(requestId))
    }

    fun pendingCount(): Int = pending.size

    companion object {
        fun decision(value: String): JsonObject = JsonObject().apply { addProperty("decision", value) }

        fun permissionDecision(requested: JsonElement?, granted: Boolean): JsonObject = JsonObject().apply {
            val permissions = if (granted && requested?.isJsonObject == true) requested.deepCopy() else JsonObject()
            add("permissions", permissions)
            addProperty("scope", "turn")
        }
    }
}
