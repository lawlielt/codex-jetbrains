package com.openai.codex.jetbrains.service

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.codex.jetbrains.context.EditorContextFormatter
import com.openai.codex.jetbrains.context.EditorContextSnapshot
import com.openai.codex.jetbrains.protocol.AppServerSupervisor
import com.openai.codex.jetbrains.protocol.AppServerSupervisorListener
import com.openai.codex.jetbrains.protocol.AppServerHandshake
import com.openai.codex.jetbrains.protocol.ApprovalKind
import com.openai.codex.jetbrains.protocol.ApprovalRequest
import com.openai.codex.jetbrains.protocol.ApprovalRouter
import com.openai.codex.jetbrains.protocol.CodexEventReducer
import com.openai.codex.jetbrains.protocol.InboundMessage
import com.openai.codex.jetbrains.protocol.JsonlRpcClient
import com.openai.codex.jetbrains.protocol.JsonlRpcListener
import com.openai.codex.jetbrains.protocol.MalformedMessageException
import com.openai.codex.jetbrains.protocol.ModelCatalog
import com.openai.codex.jetbrains.protocol.ModelDescriptor
import com.openai.codex.jetbrains.protocol.ProposedFileChange
import com.openai.codex.jetbrains.protocol.SensitiveDataRedactor
import com.openai.codex.jetbrains.protocol.StreamUpdate
import com.openai.codex.jetbrains.protocol.ThreadIdentity
import com.openai.codex.jetbrains.protocol.string
import com.openai.codex.jetbrains.settings.CodexSettings
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

enum class ConnectionState { STOPPED, STARTING, INITIALIZING, READY, RESTARTING, FAILED }

enum class ApprovalMode(val wireValue: String, val label: String) {
    UNTRUSTED("untrusted", "Ask for untrusted commands"),
    ON_REQUEST("on-request", "Ask when Codex requests access"),
    NEVER("never", "Never ask (dangerous)"),
}

enum class SandboxMode(val threadWireValue: String, val policyType: String, val label: String) {
    READ_ONLY("read-only", "readOnly", "Read only"),
    WORKSPACE_WRITE("workspace-write", "workspaceWrite", "Workspace write"),
    DANGER_FULL_ACCESS("danger-full-access", "dangerFullAccess", "Full access (dangerous)"),
}

data class AccountSummary(
    val signedIn: Boolean,
    val label: String,
    val authMode: String? = null,
)

sealed class LoginInstruction {
    data class Browser(val url: String) : LoginInstruction()
    data class DeviceCode(val verificationUrl: String, val userCode: String) : LoginInstruction()
}

interface CodexUiListener {
    fun onConnectionState(state: ConnectionState, detail: String) = Unit
    fun onModels(models: List<ModelDescriptor>, selectedModel: String?, selectedEffort: String?) = Unit
    fun onAccount(account: AccountSummary) = Unit
    fun onStream(update: StreamUpdate) = Unit
    fun onApproval(request: ApprovalRequest) = Unit
    fun onLoginInstruction(instruction: LoginInstruction) = Unit
    fun onStagedContext(context: EditorContextSnapshot?) = Unit
    fun onUserMessage(text: String) = Unit
    fun onError(message: String) = Unit
}

@Service(Service.Level.PROJECT)
@State(name = "CodexProjectState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CodexProjectService(private val project: Project) :
    PersistentStateComponent<CodexProjectService.PersistentState>, Disposable {

    data class PersistentState(
        var threadId: String? = null,
        var selectedModel: String? = null,
        var selectedEffort: String? = null,
        var approvalMode: String = ApprovalMode.UNTRUSTED.name,
        var sandboxMode: String = SandboxMode.WORKSPACE_WRITE.name,
    )

    private val log = Logger.getInstance(CodexProjectService::class.java)
    private val listeners = CopyOnWriteArrayList<CodexUiListener>()
    private val reducer = CodexEventReducer()
    private val approvals = ApprovalRouter()
    private val fileChanges = HashMap<String, List<ProposedFileChange>>()
    private val activeTurn = AtomicReference<String?>(null)
    private val lock = Any()
    private var persistentState = PersistentState()
    private var connectionState = ConnectionState.STOPPED
    private var connectionDetail = "Not connected"
    private var models: List<ModelDescriptor> = emptyList()
    private var account = AccountSummary(false, "Not signed in")
    private var stagedContext: EditorContextSnapshot? = null
    private var threadReady: CompletableFuture<String>? = null

    private val rpcListener = object : JsonlRpcListener {
        override fun onNotification(message: InboundMessage.Notification) = handleNotification(message)

        override fun onRequest(message: InboundMessage.Request) = handleServerRequest(message)

        override fun onMalformedMessage(rawLine: String, error: MalformedMessageException) {
            log.warn("Ignored a malformed Codex app-server JSONL message: ${error.message}")
            emitError("Codex app-server sent a malformed message. The connection remains active; see IDE logs for details.")
        }

        override fun onTransportClosed(error: Throwable?) {
            if (error != null) log.warn("Codex app-server transport closed", error)
        }
    }

    private val supervisor = AppServerSupervisor(
        executableProvider = { CodexSettings.getInstance().executablePath },
        workingDirectory = Paths.get(project.basePath ?: System.getProperty("user.dir")),
        listener = object : AppServerSupervisorListener {
            override fun rpcListener(): JsonlRpcListener = rpcListener

            override fun onProcessStarted(client: JsonlRpcClient) {
                setConnectionState(ConnectionState.INITIALIZING, "Initializing Codex app-server…")
                initialize(client)
            }

            override fun onProcessError(message: String, error: Throwable?) {
                val safe = SensitiveDataRedactor.redact(message + error?.message?.let { " $it" }.orEmpty())
                log.warn(safe, error)
                setConnectionState(ConnectionState.FAILED, safe)
                emitError(safe)
            }

            override fun onProcessExited(exitCode: Int, restartScheduled: Boolean) {
                activeTurn.set(null)
                threadReady = null
                val detail = if (restartScheduled) {
                    "Codex app-server exited ($exitCode); restarting…"
                } else {
                    "Codex app-server exited ($exitCode). Use Restart after checking the executable and IDE logs."
                }
                setConnectionState(if (restartScheduled) ConnectionState.RESTARTING else ConnectionState.FAILED, detail)
            }

            override fun onStderr(line: String) {
                val safe = SensitiveDataRedactor.redact(line)
                log.debug("codex app-server: $safe")
            }
        },
    )

    override fun getState(): PersistentState = persistentState

    override fun loadState(state: PersistentState) {
        persistentState = state
    }

    fun addListener(listener: CodexUiListener) {
        listeners.add(listener)
        listener.onConnectionState(connectionState, connectionDetail)
        listener.onModels(models, persistentState.selectedModel, persistentState.selectedEffort)
        listener.onAccount(account)
        listener.onStagedContext(stagedContext)
    }

    fun removeListener(listener: CodexUiListener) {
        listeners.remove(listener)
    }

    fun start() {
        setConnectionState(ConnectionState.STARTING, "Starting Codex app-server…")
        supervisor.start()
    }

    fun restart() {
        setConnectionState(ConnectionState.RESTARTING, "Restarting Codex app-server…")
        supervisor.restart()
    }

    fun stageContext(context: EditorContextSnapshot?) {
        stagedContext = context
        listeners.forEach { it.onStagedContext(context) }
    }

    fun sendTurn(prompt: String, currentContext: EditorContextSnapshot?): CompletableFuture<JsonElement> {
        val trimmed = prompt.trim()
        require(trimmed.isNotEmpty()) { "Prompt must not be empty" }
        val client = connectedClient() ?: return connectionFailure()
        val context = stagedContext ?: currentContext
        stagedContext = null
        listeners.forEach { it.onStagedContext(null) }
        val contextText = EditorContextFormatter.format(context)
        val message = if (contextText.isBlank()) trimmed else "$trimmed\n\n$contextText"
        listeners.forEach { it.onUserMessage(trimmed) }

        return ensureThread(client).thenCompose { threadId ->
            val params = JsonObject().apply {
                addProperty("threadId", threadId)
                add("input", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", message)
                    })
                })
                addProperty("cwd", project.basePath)
                addProperty("approvalPolicy", approvalMode().wireValue)
                add("sandboxPolicy", sandboxPolicy())
                persistentState.selectedModel?.let { addProperty("model", it) }
                persistentState.selectedEffort?.let { addProperty("effort", it) }
            }
            client.request("turn/start", params)
        }.whenComplete { result, error ->
            if (error != null) reportFailure("Could not start Codex turn", error)
            else result?.asJsonObject?.getAsJsonObject("turn")?.string("id")?.let { activeTurn.set(it) }
        }
    }

    fun interrupt(): CompletableFuture<JsonElement> {
        val turnId = activeTurn.get() ?: return failedFuture("There is no active Codex turn to interrupt.")
        val threadId = persistentState.threadId ?: return failedFuture("There is no active Codex thread.")
        val client = connectedClient() ?: return connectionFailure()
        return client.request("turn/interrupt", JsonObject().apply {
            addProperty("threadId", threadId)
            addProperty("turnId", turnId)
        }).whenComplete { _, error -> if (error != null) reportFailure("Could not interrupt Codex", error) }
    }

    fun loginWithBrowser(): CompletableFuture<JsonElement> = login(JsonObject().apply {
        addProperty("type", "chatgpt")
        addProperty("useHostedLoginSuccessPage", true)
        addProperty("appBrand", "codex")
    }) { result ->
        result.asJsonObject.string("authUrl")?.let { url ->
            listeners.forEach { it.onLoginInstruction(LoginInstruction.Browser(url)) }
        } ?: emitError("Codex did not return a browser login URL.")
    }

    fun loginWithDeviceCode(): CompletableFuture<JsonElement> = login(JsonObject().apply {
        addProperty("type", "chatgptDeviceCode")
    }) { result ->
        val obj = result.asJsonObject
        val url = obj.string("verificationUrl")
        val code = obj.string("userCode")
        if (url != null && code != null) listeners.forEach {
            it.onLoginInstruction(LoginInstruction.DeviceCode(url, code))
        } else emitError("Codex did not return a device-code login URL and code.")
    }

    fun loginWithApiKey(apiKey: CharArray): CompletableFuture<JsonElement> {
        val key = String(apiKey)
        apiKey.fill('\u0000')
        val params = JsonObject().apply {
            addProperty("type", "apiKey")
            addProperty("apiKey", key)
        }
        return login(params) { refreshAccount() }
    }

    fun logout(): CompletableFuture<JsonElement> {
        val client = connectedClient() ?: return connectionFailure()
        return client.request("account/logout", JsonObject()).whenComplete { _, error ->
            if (error != null) reportFailure("Codex logout failed", error) else refreshAccount()
        }
    }

    fun resolveApproval(request: ApprovalRequest, approved: Boolean, forSession: Boolean = false) {
        val result = when (request.kind) {
            ApprovalKind.COMMAND, ApprovalKind.NETWORK, ApprovalKind.FILE_CHANGE ->
                ApprovalRouter.decision(if (approved) if (forSession) "acceptForSession" else "accept" else "decline")
            ApprovalKind.PERMISSIONS -> ApprovalRouter.permissionDecision(request.params.get("permissions"), approved)
        }
        try {
            val (id, scopedResult) = approvals.complete(request, result)
            requireClient().respond(id, scopedResult)
        } catch (error: Throwable) {
            reportFailure("Could not resolve approval", error)
        }
    }

    fun cancelApproval(request: ApprovalRequest) {
        if (request.kind == ApprovalKind.PERMISSIONS) {
            resolveApproval(request, approved = false)
            return
        }
        try {
            val (id, result) = approvals.complete(request, ApprovalRouter.decision("cancel"))
            requireClient().respond(id, result)
        } catch (error: Throwable) {
            reportFailure("Could not cancel approval", error)
        }
    }

    fun updateModel(model: String?, effort: String?) {
        persistentState.selectedModel = model
        persistentState.selectedEffort = effort
    }

    fun updateApprovalMode(mode: ApprovalMode) {
        persistentState.approvalMode = mode.name
    }

    fun updateSandboxMode(mode: SandboxMode) {
        persistentState.sandboxMode = mode.name
    }

    fun approvalMode(): ApprovalMode = enumValueOrDefault(persistentState.approvalMode, ApprovalMode.UNTRUSTED)

    fun sandboxMode(): SandboxMode = enumValueOrDefault(persistentState.sandboxMode, SandboxMode.WORKSPACE_WRITE)

    private fun initialize(client: JsonlRpcClient) {
        AppServerHandshake.initialize(client, "codex_jetbrains", "Codex for JetBrains", "0.1.0").whenComplete { _, error ->
            if (error != null) {
                reportFailure("Codex app-server initialization failed", error)
                setConnectionState(ConnectionState.FAILED, "Initialization failed. Use Restart after checking setup.")
            } else if (supervisor.currentClient() === client) {
                supervisor.markHealthy()
                afterInitialized(client)
            }
        }
    }

    private fun afterInitialized(client: JsonlRpcClient) {
        loadModels(client)
        refreshAccount(client)
        val storedThread = persistentState.threadId
        if (storedThread == null) {
            threadReady = null
            setConnectionState(ConnectionState.READY, "Connected")
            return
        }
        val resume = client.request("thread/resume", ThreadIdentity.resumeParams(storedThread)!!)
            .thenApply { result -> ThreadIdentity.extractThreadId(result) ?: storedThread }
        threadReady = resume
        resume.whenComplete { _, error ->
            if (error != null) {
                persistentState.threadId = null
                threadReady = null
                reportFailure("Saved Codex thread could not be resumed; the next turn will start a new thread", error)
            }
            setConnectionState(ConnectionState.READY, "Connected")
        }
    }

    private fun ensureThread(client: JsonlRpcClient): CompletableFuture<String> {
        synchronized(lock) {
            threadReady?.let { return it }
            persistentState.threadId?.let { return CompletableFuture.completedFuture(it) }
            val params = JsonObject().apply {
                addProperty("cwd", project.basePath)
                addProperty("approvalPolicy", approvalMode().wireValue)
                addProperty("sandbox", sandboxMode().threadWireValue)
                addProperty("serviceName", "codex_jetbrains")
                persistentState.selectedModel?.let { addProperty("model", it) }
            }
            val created = client.request("thread/start", params).thenApply { result ->
                val id = ThreadIdentity.extractThreadId(result)
                    ?: throw IllegalStateException("thread/start returned no thread id")
                persistentState.threadId = id
                id
            }
            threadReady = created
            created.whenComplete { _, error ->
                if (error != null) synchronized(lock) { if (threadReady === created) threadReady = null }
            }
            return created
        }
    }

    private fun sandboxPolicy(): JsonObject = JsonObject().apply {
        val mode = sandboxMode()
        addProperty("type", mode.policyType)
        when (mode) {
            SandboxMode.READ_ONLY -> addProperty("networkAccess", false)
            SandboxMode.WORKSPACE_WRITE -> {
                add("writableRoots", JsonArray().apply { project.basePath?.let(::add) })
                addProperty("networkAccess", false)
            }
            SandboxMode.DANGER_FULL_ACCESS -> Unit
        }
    }

    private fun loadModels(client: JsonlRpcClient) {
        client.request("model/list", JsonObject().apply {
            addProperty("limit", 100)
            addProperty("includeHidden", false)
        }).whenComplete { result, error ->
            if (error != null) reportFailure("Could not discover Codex models", error)
            else {
                models = ModelCatalog.parse(result)
                val selected = models.firstOrNull { it.id == persistentState.selectedModel }
                    ?: models.firstOrNull { it.isDefault }
                    ?: models.firstOrNull()
                if (selected != null) {
                    persistentState.selectedModel = selected.id
                    if (selected.efforts.none { it.id == persistentState.selectedEffort }) {
                        persistentState.selectedEffort = selected.defaultEffort ?: selected.efforts.firstOrNull()?.id
                    }
                }
                listeners.forEach { it.onModels(models, persistentState.selectedModel, persistentState.selectedEffort) }
            }
        }
    }

    private fun refreshAccount() {
        val client = supervisor.currentClient() ?: return
        refreshAccount(client)
    }

    private fun refreshAccount(client: JsonlRpcClient) {
        client.request("account/read", JsonObject().apply { addProperty("refreshToken", false) })
            .whenComplete { result, error ->
                if (error != null) reportFailure("Could not read Codex account state", error)
                else updateAccount(result.asJsonObject)
            }
    }

    private fun updateAccount(result: JsonObject) {
        val accountObject = result.getAsJsonObject("account")
        account = if (accountObject == null) {
            AccountSummary(false, if (result.get("requiresOpenaiAuth")?.asBoolean == false) "No OpenAI sign-in required" else "Not signed in")
        } else {
            val type = accountObject.string("type") ?: "account"
            val email = accountObject.string("email")
            val plan = accountObject.string("planType")
            AccountSummary(true, listOfNotNull(email ?: type, plan).joinToString(" · "), type)
        }
        listeners.forEach { it.onAccount(account) }
    }

    private fun login(params: JsonObject, onResult: (JsonElement) -> Unit): CompletableFuture<JsonElement> {
        val client = connectedClient() ?: return connectionFailure()
        return client.request("account/login/start", params).whenComplete { result, error ->
            if (error != null) reportFailure("Codex login failed", error) else onResult(result)
        }
    }

    private fun handleNotification(message: InboundMessage.Notification) {
        cacheFileChanges(message)
        reducer.consume(message)?.let { update ->
            if (update.completed) activeTurn.set(null)
            listeners.forEach { it.onStream(update) }
        }
        when (message.method) {
            "account/updated" -> refreshAccount()
            "account/login/completed" -> {
                val params = message.params.asJsonObject
                if (params.get("success")?.asBoolean == true) refreshAccount()
                else emitError("Codex login failed: ${SensitiveDataRedactor.redact(params.string("error").orEmpty())}")
            }
            "turn/started" -> message.params.asJsonObject.getAsJsonObject("turn")?.string("id")?.let(activeTurn::set)
            "turn/completed" -> activeTurn.set(null)
            "serverRequest/resolved" -> message.params.asJsonObject.get("requestId")?.let(approvals::resolved)
        }
    }

    private fun cacheFileChanges(message: InboundMessage.Notification) {
        if (message.method != "item/started" && message.method != "item/completed") return
        val item = message.params.asJsonObject.getAsJsonObject("item") ?: return
        if (item.string("type") != "fileChange") return
        val itemId = item.string("id") ?: return
        val changes = item.getAsJsonArray("changes")?.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            ProposedFileChange(
                path = obj.string("path") ?: return@mapNotNull null,
                kind = obj.string("kind") ?: "update",
                diff = obj.string("diff").orEmpty(),
            )
        }.orEmpty()
        fileChanges[itemId] = changes
    }

    private fun handleServerRequest(message: InboundMessage.Request) {
        val request = approvals.route(message, fileChanges)
        if (request == null) {
            supervisor.currentClient()?.respondError(
                message.id,
                -32601,
                "Codex for JetBrains does not support server request method '${message.method}'",
            )
            emitError("Codex requested an unsupported interaction: ${message.method}. It was declined safely.")
        } else {
            listeners.forEach { it.onApproval(request) }
        }
    }

    private fun requireClient(): JsonlRpcClient = supervisor.currentClient()
        ?: throw IllegalStateException("Codex app-server is not connected. Open the Codex tool window and use Restart.")

    private fun connectedClient(): JsonlRpcClient? = supervisor.currentClient()

    private fun connectionFailure(): CompletableFuture<JsonElement> {
        val message = "Codex app-server is not connected. Open the Codex tool window and use Restart."
        emitError(message)
        return failedFuture(message)
    }

    private fun setConnectionState(state: ConnectionState, detail: String) {
        connectionState = state
        connectionDetail = detail
        listeners.forEach { it.onConnectionState(state, detail) }
    }

    private fun reportFailure(prefix: String, error: Throwable) {
        val root = generateSequence(error) { it.cause }.last()
        val safe = SensitiveDataRedactor.redact(root.message.orEmpty()).ifBlank { root.javaClass.simpleName }
        log.warn("$prefix: $safe")
        emitError("$prefix: $safe")
    }

    private fun emitError(message: String) {
        val safe = SensitiveDataRedactor.redact(message)
        listeners.forEach { it.onError(safe) }
    }

    override fun dispose() {
        supervisor.close()
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): CodexProjectService = project.service()

        private fun <T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
            default.declaringJavaClass.enumConstants.firstOrNull { it.name == value } ?: default

        private fun failedFuture(message: String): CompletableFuture<JsonElement> =
            CompletableFuture<JsonElement>().also { it.completeExceptionally(IllegalStateException(message)) }
    }
}
