package com.openai.codex.jetbrains.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/** The small, session-scoped dynamic-tool contract injected into Codex. */
internal object OpenDiffToolProtocol {
    const val TOOL_NAME = "openDiff"
    private const val OPERATION = "operation"
    private const val OLD_PATH = "oldPath"
    private const val NEW_PATH = "newPath"
    private const val CONTENT = "content"
    private const val PREIMAGE = "preimage"
    private val requiredKeys = setOf(OPERATION, OLD_PATH, NEW_PATH, CONTENT, PREIMAGE)

    val toolDefinition: JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("function"),
            "name" to JsonPrimitive(TOOL_NAME),
            "description" to JsonPrimitive("Propose one full-content project edit for JetBrains review. The IDE may reject it."),
            "inputSchema" to JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "additionalProperties" to JsonPrimitive(false),
                    "properties" to JsonObject(
                        mapOf(
                            OPERATION to enumSchema("update", "add", "delete", "move"),
                            OLD_PATH to stringSchema(1),
                            NEW_PATH to stringSchema(1),
                            CONTENT to stringSchema(),
                            PREIMAGE to stringSchema(),
                        ),
                    ),
                    "required" to kotlinx.serialization.json.JsonArray(requiredKeys.sorted().map(::JsonPrimitive)),
                ),
            ),
        ),
    )

    fun parse(params: JsonObject): OpenDiffRequest? {
        if (params.string("tool") != TOOL_NAME) return null
        val arguments = params["arguments"] as? JsonObject ?: return null
        if (arguments.keys != requiredKeys) return null
        val operation = arguments.string(OPERATION) ?: return null
        if (operation !in OpenDiffOperation.entries.map(OpenDiffOperation::wireName)) return null
        val oldPath = arguments.string(OLD_PATH)?.takeIf(String::isNotBlank) ?: return null
        val newPath = arguments.string(NEW_PATH)?.takeIf(String::isNotBlank) ?: return null
        val content = arguments.string(CONTENT) ?: return null
        val preimage = arguments.string(PREIMAGE) ?: return null
        val callId = params.string("callId") ?: return null
        val threadId = params.string("threadId") ?: return null
        val turnId = params.string("turnId") ?: return null
        return OpenDiffRequest(
            callId = callId,
            threadId = threadId,
            turnId = turnId,
            operation = OpenDiffOperation.entries.first { it.wireName == operation },
            oldPath = oldPath,
            newPath = newPath,
            content = content,
            preimage = preimage,
        )
    }

    private fun enumSchema(vararg values: String): JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("string"), "enum" to kotlinx.serialization.json.JsonArray(values.map(::JsonPrimitive))),
    )

    private fun stringSchema(minLength: Int? = null): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("string"))
            minLength?.let { put("minLength", JsonPrimitive(it)) }
        },
    )

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
}

internal enum class OpenDiffOperation(val wireName: String) {
    UPDATE("update"),
    ADD("add"),
    DELETE("delete"),
    MOVE("move"),
}

internal data class OpenDiffRequest(
    val callId: String,
    val threadId: String,
    val turnId: String,
    val operation: OpenDiffOperation,
    val oldPath: String,
    val newPath: String,
    val content: String,
    val preimage: String,
)

internal data class PreparedOpenDiff(
    val request: OpenDiffRequest,
    val source: Path,
    val target: Path,
    val currentContent: String,
)

internal interface OpenDiffSnapshotStore {
    fun read(path: Path): String?
    fun hasUnsavedDocument(path: Path): Boolean
}

/** Rejects path escapes, stale preimages, dirty documents, and impossible operations before UI. */
internal class OpenDiffValidator(
    private val projectRoot: Path,
    private val snapshots: OpenDiffSnapshotStore,
) {
    fun prepare(request: OpenDiffRequest): PreparedOpenDiff? {
        val source = resolve(request.oldPath) ?: return null
        val target = resolve(request.newPath) ?: return null
        if (snapshots.hasUnsavedDocument(source) || snapshots.hasUnsavedDocument(target)) return null
        return when (request.operation) {
            OpenDiffOperation.ADD -> {
                if (source != target || snapshots.read(source) != null || request.preimage.isNotEmpty()) return null
                if (!parentIsSafeDirectory(target)) return null
                PreparedOpenDiff(request, source, target, "")
            }
            OpenDiffOperation.UPDATE -> {
                if (source != target) return null
                val current = regularFileContent(source) ?: return null
                if (current != request.preimage) return null
                PreparedOpenDiff(request, source, target, current)
            }
            OpenDiffOperation.DELETE -> {
                if (source != target || request.content.isNotEmpty()) return null
                val current = regularFileContent(source) ?: return null
                if (current != request.preimage) return null
                PreparedOpenDiff(request, source, target, current)
            }
            OpenDiffOperation.MOVE -> {
                if (source == target || snapshots.read(target) != null || !parentIsSafeDirectory(target)) return null
                val current = regularFileContent(source) ?: return null
                if (current != request.preimage) return null
                PreparedOpenDiff(request, source, target, current)
            }
        }
    }

    fun isStillFresh(prepared: PreparedOpenDiff): Boolean {
        val (request, source, target, before) = prepared
        if (snapshots.hasUnsavedDocument(source) || snapshots.hasUnsavedDocument(target)) return false
        return when (request.operation) {
            OpenDiffOperation.ADD -> source == target && snapshots.read(source) == null && parentIsSafeDirectory(target)
            OpenDiffOperation.UPDATE, OpenDiffOperation.DELETE -> regularFileContent(source) == before
            OpenDiffOperation.MOVE -> regularFileContent(source) == before && snapshots.read(target) == null && parentIsSafeDirectory(target)
        }
    }

    private fun regularFileContent(path: Path): String? {
        if (Files.isSymbolicLink(path)) return null
        return snapshots.read(path)
    }

    private fun parentIsSafeDirectory(path: Path): Boolean = path.parent?.let { parent ->
        Files.isDirectory(parent) && !Files.isSymbolicLink(parent) && isWithinRealRoot(parent)
    } ?: false

    private fun resolve(value: String): Path? = runCatching {
        val relative = Path.of(value)
        if (relative.isAbsolute || relative.any { it.toString() == ".." }) return null
        val root = projectRoot.toAbsolutePath().normalize()
        val candidate = root.resolve(relative).normalize().takeIf { it.startsWith(root) } ?: return null
        if (!Files.exists(root)) return null
        val ancestor = generateSequence(candidate) { it.parent }.firstOrNull(Files::exists) ?: return null
        if (!isWithinRealRoot(ancestor)) return null
        candidate
    }.getOrNull()

    private fun isWithinRealRoot(path: Path): Boolean = runCatching {
        path.toRealPath().startsWith(projectRoot.toAbsolutePath().normalize().toRealPath())
    }.getOrDefault(false)
}

internal sealed interface OpenDiffCompletion {
    data class Apply(val reviewerContent: String) : OpenDiffCompletion
    data object Reject : OpenDiffCompletion
}

internal fun interface OpenDiffPresenter {
    /** Close must complete as [OpenDiffCompletion.Reject]; the callback is single-use. */
    fun present(proposal: PreparedOpenDiff, complete: (OpenDiffCompletion) -> Unit)

    /** Releases outstanding native review surfaces during project/session disposal. */
    fun close() {}
}

internal fun interface OpenDiffWriter {
    /** Commits the reviewer-edited after-content through JetBrains APIs before returning. */
    fun apply(proposal: PreparedOpenDiff, reviewerContent: String): Boolean
}

/** Correlates dynamic calls and guarantees that each upstream request receives one result. */
internal class OpenDiffCoordinator(
    private val validator: OpenDiffValidator,
    private val presenter: OpenDiffPresenter,
    private val writer: OpenDiffWriter,
) {
    private val active = mutableMapOf<String, ActiveRequest>()
    private val replied = mutableSetOf<String>()
    private val seenCallIds = mutableSetOf<String>()
    private var closed = false

    fun toolRequested(requestId: JsonElement, params: JsonObject, respond: (Boolean, String) -> Unit) {
        val key = requestId.toString()
        val request = OpenDiffToolProtocol.parse(params)
        val pending = synchronized(this) {
            if (!replied.add(key)) return
            if (closed || request == null) return@synchronized null
            if (!seenCallIds.add(request.callId)) return@synchronized null
            validator.prepare(request)?.let { proposal -> ActiveRequest(proposal, respond) }
        }
        if (pending == null) {
            respond(false, "IDE_DIFF_REJECTED: malformed, stale, or unavailable file proposal.")
            return
        }
        synchronized(this) {
            if (closed) {
                respond(false, "IDE_DIFF_REJECTED: project session is closing.")
                return
            }
            active[key] = pending
        }
        runCatching { presenter.present(pending.proposal) { completion -> complete(key, completion) } }
            .onFailure { complete(key, OpenDiffCompletion.Reject) }
    }

    fun close() {
        val pending = synchronized(this) {
            closed = true
            active.values.toList().also { active.clear() }
        }
        presenter.close()
        pending.forEach { it.respond(false, "IDE_DIFF_REJECTED: project session closed before review.") }
    }

    private fun complete(key: String, completion: OpenDiffCompletion) {
        val request = synchronized(this) { active.remove(key) } ?: return
        val response = when (completion) {
            OpenDiffCompletion.Reject -> false to "IDE_DIFF_REJECTED: reviewer rejected the source edit."
            is OpenDiffCompletion.Apply -> {
                if (!validator.isStillFresh(request.proposal)) {
                    false to "IDE_DIFF_REJECTED: source changed or has unsaved edits during review."
                } else if (writer.apply(request.proposal, completion.reviewerContent)) {
                    true to "IDE_DIFF_APPLIED: reviewer-approved content was committed."
                } else {
                    false to "IDE_DIFF_REJECTED: IDE could not commit the reviewed content."
                }
            }
        }
        request.respond(response.first, response.second)
    }

    private data class ActiveRequest(
        val proposal: PreparedOpenDiff,
        val respond: (Boolean, String) -> Unit,
    )
}
