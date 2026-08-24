package com.openai.codex.jetbrains.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class CodexSettingsConfigurable : Configurable {
    private var executableField: JBTextField? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Codex"

    override fun createComponent(): JComponent {
        val field = JBTextField(CodexSettings.getInstance().executablePath)
        executableField = field
        val validate = JButton("Validate")
        validate.addActionListener {
            validate.isEnabled = false
            CompletableFuture.supplyAsync {
                CodexExecutableValidator.validate(field.text, Paths.get(System.getProperty("user.dir")))
            }.whenComplete { result, error ->
                SwingUtilities.invokeLater {
                    validate.isEnabled = true
                    val message = result?.message ?: error?.message ?: "Validation failed."
                    if (result?.valid == true) Messages.showInfoMessage(message, "Codex")
                    else Messages.showErrorDialog(message, "Codex Setup")
                }
            }
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Codex executable:"), field, 1, false)
            .addComponent(validate)
            .addComponent(JBLabel("Default: codex. An absolute path is recommended when the IDE PATH differs from your shell."))
            .addComponentFillVertically(JPanel(), 0)
            .panel.also { panel = it }
    }

    override fun isModified(): Boolean =
        executableField?.text?.trim().orEmpty().ifBlank { "codex" } != CodexSettings.getInstance().executablePath

    override fun apply() {
        CodexSettings.getInstance().executablePath = executableField?.text.orEmpty()
    }

    override fun reset() {
        executableField?.text = CodexSettings.getInstance().executablePath
    }

    override fun disposeUIResources() {
        executableField = null
        panel = null
    }
}
