package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

internal fun interface NativeDiffPresenter {
    /** The callback is single-use; closing the presentation must invoke DECLINE. */
    fun present(proposal: PreparedProposal, complete: (ApprovalDecision) -> Unit)
}

/**
 * Owns request correlation and the single response rule. It never applies a
 * patch: accepting only returns the app-server decision to Codex.
 */
internal class FileChangeApprovalCoordinator(
    private val validator: FileChangeValidator,
    private val presenter: NativeDiffPresenter,
) {
    private val pending = mutableMapOf<String, FileChangeProposal>()
    private val answeredRequestIds = mutableSetOf<String>()
    private val activeResponses = mutableMapOf<String, (ApprovalDecision) -> Unit>()
    private var closed = false

    @Synchronized
    fun itemStarted(params: JsonObject) {
        val item = params["item"] as? JsonObject ?: return
        if ((item["type"] as? JsonPrimitive)?.content != "fileChange") return
        val threadId = params.string("threadId") ?: return
        val turnId = params.string("turnId") ?: return
        val itemId = item.string("id") ?: return
        val changes = item["changes"]?.jsonArray?.map { element ->
            val change = element as? JsonObject ?: return
            FileChange(
                path = change.string("path") ?: return,
                kind = change.string("kind") ?: return,
                diff = change.string("diff") ?: return,
                movePath = change.string("movePath") ?: change.string("move_path"),
            )
        } ?: return
        pending[itemId] = FileChangeProposal(threadId, turnId, itemId, changes)
    }

    /**
     * Intercepts an upstream request and deliberately never forwards that one
     * request to the TUI. All other JSON-RPC methods still pass through relay.
     */
    fun approvalRequested(requestId: JsonElement, params: JsonObject, respond: (ApprovalDecision) -> Unit) {
        val requestKey = requestId.toString()
        val proposal = synchronized(this) {
            if (!answeredRequestIds.add(requestKey)) return
            if (closed) return@synchronized null
            val itemId = params.string("itemId")
            val stored = itemId?.let(pending::get)
            if (stored == null || stored.threadId != params.string("threadId") || stored.turnId != params.string("turnId")) {
                null
            } else {
                pending.remove(itemId)
                stored
            }
        }
        if (proposal == null) {
            respond(ApprovalDecision.DECLINE)
            return
        }
        val prepared = runCatching { validator.prepare(proposal) }.getOrNull()
        if (prepared == null) {
            respond(ApprovalDecision.DECLINE)
            return
        }
        synchronized(this) {
            if (closed) {
                respond(ApprovalDecision.DECLINE)
                return
            }
            activeResponses[requestKey] = respond
        }
        runCatching {
            presenter.present(prepared) { decision -> complete(requestKey, decision) }
        }.onFailure {
            complete(requestKey, ApprovalDecision.DECLINE)
        }
    }

    @Synchronized
    fun itemCompleted(params: JsonObject) {
        val item = params["item"] as? JsonObject ?: return
        val itemId = item.string("id") ?: return
        pending.remove(itemId)
    }

    fun close() {
        val toDecline = synchronized(this) {
            closed = true
            pending.clear()
            activeResponses.values.toList().also { activeResponses.clear() }
        }
        toDecline.forEach { it(ApprovalDecision.DECLINE) }
    }

    private fun complete(requestKey: String, decision: ApprovalDecision) {
        val response = synchronized(this) { activeResponses.remove(requestKey) } ?: return
        response(if (closed) ApprovalDecision.DECLINE else decision)
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
