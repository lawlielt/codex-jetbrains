package com.openai.codex.jetbrains.ui

import com.google.gson.GsonBuilder
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.openai.codex.jetbrains.context.EditorContextCollector
import com.openai.codex.jetbrains.context.EditorContextSnapshot
import com.openai.codex.jetbrains.protocol.ApprovalKind
import com.openai.codex.jetbrains.protocol.ApprovalRequest
import com.openai.codex.jetbrains.protocol.ModelDescriptor
import com.openai.codex.jetbrains.protocol.ReasoningEffortOption
import com.openai.codex.jetbrains.protocol.SensitiveDataRedactor
import com.openai.codex.jetbrains.protocol.StreamUpdate
import com.openai.codex.jetbrains.protocol.string
import com.openai.codex.jetbrains.service.AccountSummary
import com.openai.codex.jetbrains.service.ApprovalMode
import com.openai.codex.jetbrains.service.CodexProjectService
import com.openai.codex.jetbrains.service.CodexUiListener
import com.openai.codex.jetbrains.service.ConnectionState
import com.openai.codex.jetbrains.service.LoginInstruction
import com.openai.codex.jetbrains.service.SandboxMode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class CodexPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, CodexUiListener {
    private val service = CodexProjectService.getInstance(project)
    private val status = JBLabel("Not connected")
    private val account = JBLabel("Not signed in")
    private val transcript = JTextPane()
    private val prompt = JBTextArea(4, 40)
    private val staged = JBLabel("No staged editor context")
    private val modelCombo = JComboBox<ModelDescriptor>()
    private val effortCombo = JComboBox<ReasoningEffortOption>()
    private val approvalCombo = JComboBox(ApprovalMode.entries.toTypedArray())
    private val sandboxCombo = JComboBox(SandboxMode.entries.toTypedArray())
    private val send = JButton("Send")
    private val interrupt = JButton("Interrupt")
    private var updatingSelectors = false
    private var assistantItem: String? = null

    init {
        border = JBUI.Borders.empty(8)
        transcript.isEditable = false
        transcript.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        transcript.background = JBColor.PanelBackground
        transcript.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val offset = transcript.viewToModel2D(event.point)
                if (offset < 0) return
                val attributes = transcript.styledDocument.getCharacterElement(offset).attributes
                val value = attributes.getAttribute(FILE_REFERENCE_ATTRIBUTE) as? ProjectFileReference ?: return
                if (!OutputReferenceNavigator.navigate(project, value)) {
                    notify("Could not open ${value.path}; only existing project files are supported.", NotificationType.WARNING)
                }
            }
        })

        modelCombo.renderer = SimpleListCellRenderer.create("") { it?.displayName.orEmpty() }
        effortCombo.renderer = SimpleListCellRenderer.create("") { option ->
            option?.let { if (it.description.isBlank()) it.id else "${it.id} — ${it.description}" }.orEmpty()
        }
        approvalCombo.renderer = SimpleListCellRenderer.create("") { it?.label.orEmpty() }
        sandboxCombo.renderer = SimpleListCellRenderer.create("") { it?.label.orEmpty() }

        add(buildHeader(), BorderLayout.NORTH)
        add(buildConversation(), BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)
        wireActions()
    }

    private fun buildHeader(): JPanel {
        val root = JPanel(BorderLayout(0, 6))
        val connection = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JBLabel("Codex:"))
            add(status)
            add(JButton("Restart").apply { addActionListener { service.restart() } })
            add(JButton("Settings").apply {
                addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Codex") }
            })
        }
        val auth = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(account)
            add(JButton("ChatGPT").apply { addActionListener { service.loginWithBrowser() } })
            add(JButton("Device code").apply { addActionListener { service.loginWithDeviceCode() } })
            add(JButton("API key").apply { addActionListener { requestApiKey() } })
            add(JButton("Logout").apply { addActionListener { service.logout() } })
        }
        val selectors = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JBLabel("Model"))
            add(modelCombo)
            add(JBLabel("Effort"))
            add(effortCombo)
            add(approvalCombo)
            add(sandboxCombo)
        }
        root.add(connection, BorderLayout.NORTH)
        root.add(auth, BorderLayout.CENTER)
        root.add(selectors, BorderLayout.SOUTH)
        root.border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
        return root
    }

    private fun buildConversation(): JSplitPane {
        val transcriptScroll = JBScrollPane(transcript)
        val activityHelp = JBTextArea(
            "Agent activity, command output, approvals, and streamed replies appear in the conversation. " +
                "Project references such as @src/App.kt#L10-L20 are clickable.",
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = JBColor.PanelBackground
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(6)
        }
        return JSplitPane(JSplitPane.VERTICAL_SPLIT, transcriptScroll, activityHelp).apply {
            resizeWeight = 0.9
            dividerSize = 4
        }
    }

    private fun buildComposer(): JPanel {
        prompt.lineWrap = true
        prompt.wrapStyleWord = true
        prompt.emptyText.text = "Ask Codex to work on this project…"
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(JButton("Add editor context").apply {
                addActionListener { service.stageContext(EditorContextCollector.collect(project)) }
            })
            add(interrupt)
            add(send)
        }
        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(staged, BorderLayout.NORTH)
            add(JBScrollPane(prompt), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun wireActions() {
        send.addActionListener {
            val text = prompt.text
            if (text.isBlank()) return@addActionListener
            send.isEnabled = false
            prompt.text = ""
            service.sendTurn(text, EditorContextCollector.collect(project)).whenComplete { _, error ->
                onEdt {
                    if (error != null) {
                        send.isEnabled = true
                        onError(error.message ?: "Could not send turn")
                    }
                }
            }
        }
        interrupt.addActionListener { service.interrupt() }
        modelCombo.addActionListener {
            if (updatingSelectors) return@addActionListener
            val selected = modelCombo.selectedItem as? ModelDescriptor ?: return@addActionListener
            updateEfforts(selected, selected.defaultEffort)
            service.updateModel(selected.id, (effortCombo.selectedItem as? ReasoningEffortOption)?.id)
        }
        effortCombo.addActionListener {
            if (!updatingSelectors) service.updateModel(
                (modelCombo.selectedItem as? ModelDescriptor)?.id,
                (effortCombo.selectedItem as? ReasoningEffortOption)?.id,
            )
        }
        approvalCombo.selectedItem = service.approvalMode()
        approvalCombo.addActionListener {
            if (updatingSelectors) return@addActionListener
            val selected = approvalCombo.selectedItem as ApprovalMode
            if (selected == ApprovalMode.NEVER && Messages.showYesNoDialog(
                    project,
                    "'Never ask' can allow commands and changes without an IDE approval prompt. Continue?",
                    "Codex Autonomy",
                    Messages.getWarningIcon(),
                ) != Messages.YES
            ) {
                updatingSelectors = true
                approvalCombo.selectedItem = service.approvalMode()
                updatingSelectors = false
            } else service.updateApprovalMode(selected)
        }
        sandboxCombo.selectedItem = service.sandboxMode()
        sandboxCombo.addActionListener {
            if (updatingSelectors) return@addActionListener
            val selected = sandboxCombo.selectedItem as SandboxMode
            if (selected == SandboxMode.DANGER_FULL_ACCESS && Messages.showYesNoDialog(
                    project,
                    "Full access removes the Codex filesystem sandbox. Only use it for a trusted task and project. Continue?",
                    "Codex Sandbox",
                    Messages.getWarningIcon(),
                ) != Messages.YES
            ) {
                updatingSelectors = true
                sandboxCombo.selectedItem = service.sandboxMode()
                updatingSelectors = false
            } else service.updateSandboxMode(selected)
        }
    }

    fun connect() {
        service.addListener(this)
        service.start()
    }

    override fun onConnectionState(state: ConnectionState, detail: String) = onEdt {
        status.text = detail
        val ready = state == ConnectionState.READY
        send.isEnabled = ready
        interrupt.isEnabled = ready
    }

    override fun onModels(models: List<ModelDescriptor>, selectedModel: String?, selectedEffort: String?) = onEdt {
        updatingSelectors = true
        modelCombo.removeAllItems()
        models.forEach(modelCombo::addItem)
        val selected = models.firstOrNull { it.id == selectedModel } ?: models.firstOrNull()
        modelCombo.selectedItem = selected
        if (selected != null) updateEfforts(selected, selectedEffort)
        updatingSelectors = false
    }

    private fun updateEfforts(model: ModelDescriptor, selectedEffort: String?) {
        val wasUpdating = updatingSelectors
        updatingSelectors = true
        effortCombo.removeAllItems()
        model.efforts.forEach(effortCombo::addItem)
        effortCombo.selectedItem = model.efforts.firstOrNull { it.id == selectedEffort }
            ?: model.efforts.firstOrNull { it.id == model.defaultEffort }
            ?: model.efforts.firstOrNull()
        effortCombo.isEnabled = model.efforts.isNotEmpty()
        updatingSelectors = wasUpdating
    }

    override fun onAccount(account: AccountSummary) = onEdt { this.account.text = account.label }

    override fun onStream(update: StreamUpdate) = onEdt {
        update.assistantDelta?.let { delta ->
            if (assistantItem != update.itemId) {
                assistantItem = update.itemId
                append("\nCodex: ", JBColor(0x4B45A4, 0xA9A4FF), bold = true)
            }
            appendWithReferences(delta, transcript.foreground)
        }
        update.activity?.let { append("\n• $it", JBColor.GRAY, italic = true) }
        transcript.caretPosition = transcript.document.length
        if (update.completed) send.isEnabled = true
    }

    override fun onApproval(request: ApprovalRequest) = onEdt { showApproval(request) }

    override fun onLoginInstruction(instruction: LoginInstruction) = onEdt {
        when (instruction) {
            is LoginInstruction.Browser -> BrowserUtil.browse(instruction.url)
            is LoginInstruction.DeviceCode -> {
                BrowserUtil.browse(instruction.verificationUrl)
                Messages.showInfoMessage(
                    project,
                    "Enter this code in the opened browser:\n\n${instruction.userCode}",
                    "Codex Device Login",
                )
            }
        }
    }

    override fun onStagedContext(context: EditorContextSnapshot?) = onEdt {
        staged.text = context?.let { "Staged: ${it.reference}" } ?: "No staged editor context"
    }

    override fun onUserMessage(text: String) = onEdt {
        assistantItem = null
        append("\nYou: ", JBColor(0x1F6F5F, 0x75D8C2), bold = true)
        appendWithReferences(text, transcript.foreground)
    }

    override fun onError(message: String) = onEdt {
        val safe = SensitiveDataRedactor.redact(message)
        append("\nError: $safe", JBColor.RED, bold = true)
        notify(safe, NotificationType.ERROR)
        send.isEnabled = true
    }

    private fun showApproval(request: ApprovalRequest) {
        if (request.kind == ApprovalKind.FILE_CHANGE) showDiff(request)
        val summary = approvalSummary(request)
        val choices = if (request.kind == ApprovalKind.PERMISSIONS) {
            arrayOf("Grant for this turn", "Decline", "Cancel turn")
        } else {
            arrayOf("Allow once", "Allow for session", "Decline", "Cancel turn")
        }
        val selected = Messages.showDialog(
            project,
            summary,
            "Codex ${request.kind.name.lowercase().replace('_', ' ')} approval",
            choices,
            if (request.kind == ApprovalKind.PERMISSIONS) 1 else 2,
            Messages.getWarningIcon(),
        )
        if (request.kind == ApprovalKind.PERMISSIONS) {
            when (selected) {
                0 -> service.resolveApproval(request, approved = true)
                1 -> service.resolveApproval(request, approved = false)
                else -> service.cancelApproval(request)
            }
        } else {
            when (selected) {
                0 -> service.resolveApproval(request, approved = true)
                1 -> service.resolveApproval(request, approved = true, forSession = true)
                2 -> service.resolveApproval(request, approved = false)
                else -> service.cancelApproval(request)
            }
        }
    }

    private fun approvalSummary(request: ApprovalRequest): String {
        val scope = "Thread ${request.scope.threadId}\nTurn ${request.scope.turnId}\nItem ${request.scope.itemId}"
        val reason = request.params.string("reason")?.let { "\n\nReason: $it" }.orEmpty()
        return when (request.kind) {
            ApprovalKind.COMMAND -> "$scope$reason\n\nCommand:\n${request.params.get("command") ?: "(not provided)"}"
            ApprovalKind.NETWORK -> {
                val network = request.params.getAsJsonObject("networkApprovalContext")
                val target = listOfNotNull(network?.string("protocol"), network?.string("host"), network?.get("port")?.asString)
                    .joinToString(" ")
                "$scope$reason\n\nNetwork destination:\n$target"
            }
            ApprovalKind.FILE_CHANGE -> "$scope$reason\n\nReview the proposed diff before choosing. ${request.proposedChanges.size} file(s)."
            ApprovalKind.PERMISSIONS -> "$scope$reason\n\nRequested permissions:\n" +
                GsonBuilder().setPrettyPrinting().create().toJson(request.params.get("permissions"))
        }
    }

    private fun showDiff(request: ApprovalRequest) {
        if (request.proposedChanges.isEmpty()) {
            notify("Codex requested file approval without a proposed diff; review carefully before allowing.", NotificationType.WARNING)
            return
        }
        val root = project.basePath?.let(Paths::get)?.toAbsolutePath()?.normalize() ?: return
        val contentFactory = DiffContentFactory.getInstance()
        val requests = request.proposedChanges.mapNotNull { change ->
            val target = root.resolve(change.path).normalize()
            if (!target.startsWith(root)) return@mapNotNull null
            val before = if (Files.isRegularFile(target)) Files.readString(target, StandardCharsets.UTF_8) else ""
            val after = UnifiedDiffApplier.apply(before, change.diff) ?: change.diff
            SimpleDiffRequest(
                "Codex proposed ${change.kind}: ${change.path}",
                contentFactory.create(project, before),
                contentFactory.create(project, after),
                "Current",
                "Proposed",
            )
        }
        if (requests.isNotEmpty()) {
            DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.MODAL)
        }
    }

    private fun requestApiKey() {
        val value = Messages.showPasswordDialog(
            project,
            "Enter an OpenAI API key. The plugin sends it only to the local Codex app-server and does not store it.",
            "Codex API Key",
            Messages.getQuestionIcon(),
        )
        if (!value.isNullOrBlank()) service.loginWithApiKey(value.toCharArray())
    }

    private fun append(text: String, color: Color, bold: Boolean = false, italic: Boolean = false) {
        val attributes = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, color)
            StyleConstants.setBold(this, bold)
            StyleConstants.setItalic(this, italic)
        }
        transcript.styledDocument.insertString(transcript.styledDocument.length, text, attributes)
    }

    private fun appendWithReferences(text: String, color: Color) {
        var cursor = 0
        OutputReferenceNavigator.pattern.findAll(text).forEach { match ->
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first), color)
            val reference = OutputReferenceNavigator.parse(match.value)
            if (reference == null) append(match.value, color)
            else {
                val attributes = SimpleAttributeSet().apply {
                    StyleConstants.setForeground(this, JBColor.BLUE)
                    StyleConstants.setUnderline(this, true)
                    addAttribute(FILE_REFERENCE_ATTRIBUTE, reference)
                }
                transcript.styledDocument.insertString(transcript.styledDocument.length, match.value, attributes)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor), color)
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Codex")
            .createNotification(message, type)
            .notify(project)
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block()
        else ApplicationManager.getApplication().invokeLater(block)
    }

    override fun dispose() {
        service.removeListener(this)
    }

    companion object {
        private const val FILE_REFERENCE_ATTRIBUTE = "codex.file.reference"
    }
}
