package com.openai.codex.jetbrains

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.impl.MacOSDefaultKeymap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent

class PluginDescriptorTest {
    private val pluginXml = resource("/META-INF/plugin.xml")
    private val terminalXml = resource("/META-INF/plugin-terminal.xml")

    @Test
    fun `uses a unique Marketplace name and publisher-owned plugin ID`() {
        assertTrue(pluginXml.contains("<name>Codex CLI Companion</name>"))
        assertTrue(pluginXml.contains("<id>io.github.lawlielt.codex.jetbrains</id>"))
        assertFalse(pluginXml.contains("<id>com.openai.codex.jetbrains</id>"))
    }

    @Test
    fun `registers the Codex launcher in toolbar and menu`() {
        assertTrue(pluginXml.contains("com.openai.codex.jetbrains.actions.OpenCodexTerminalAction"))
        assertTrue(pluginXml.contains("group-id=\"MainToolbarRight\""))
        assertTrue(pluginXml.contains("group-id=\"ToolsMenu\""))
        assertTrue(pluginXml.contains("icon=\"/icons/codex.svg\""))
        assertTrue(pluginXml.contains("keymap=\"\$default\" first-keystroke=\"shift alt ctrl K\""))
    }

    @Test
    fun `registers Send to Codex in the editor popup with icon and shortcut`() {
        assertTrue(pluginXml.contains("com.openai.codex.jetbrains.actions.SendToCodexAction"))
        assertTrue(pluginXml.contains("id=\"com.openai.codex.jetbrains.SendEditorReference\""))
        assertTrue(pluginXml.contains("text=\"Send to Codex\""))
        assertTrue(pluginXml.contains("group-id=\"EditorPopupMenu\""))
        assertTrue(pluginXml.contains("group-id=\"Floating.CodeToolbar\""))
        assertTrue(pluginXml.contains("icon=\"/icons/codex.svg\""))
        assertTrue(pluginXml.contains("keymap=\"\$default\" first-keystroke=\"alt ctrl K\""))
    }

    @Test
    fun `default shortcut becomes Option Command K on the macOS keymap`() {
        val defaultKeyStroke = requireNotNull(KeymapUtil.getKeyStroke("alt ctrl K"))
        assertTrue(defaultKeyStroke.modifiers and InputEvent.ALT_DOWN_MASK != 0)
        assertTrue(defaultKeyStroke.modifiers and InputEvent.CTRL_DOWN_MASK != 0)
        assertFalse(defaultKeyStroke.modifiers and InputEvent.META_DOWN_MASK != 0)

        val macShortcut = MacOSDefaultKeymap.convertShortcutFromParent(
            KeyboardShortcut(defaultKeyStroke, null),
        ) as KeyboardShortcut
        val macKeyStroke = macShortcut.firstKeyStroke
        assertTrue(macKeyStroke.modifiers and InputEvent.ALT_DOWN_MASK != 0)
        assertTrue(macKeyStroke.modifiers and InputEvent.META_DOWN_MASK != 0)
        assertFalse(macKeyStroke.modifiers and InputEvent.CTRL_DOWN_MASK != 0)
    }

    @Test
    fun `open shortcut keeps the extra shift modifier on the macOS keymap`() {
        val defaultKeyStroke = requireNotNull(KeymapUtil.getKeyStroke("shift alt ctrl K"))
        val macShortcut = MacOSDefaultKeymap.convertShortcutFromParent(
            KeyboardShortcut(defaultKeyStroke, null),
        ) as KeyboardShortcut
        val macKeyStroke = macShortcut.firstKeyStroke

        assertTrue(macKeyStroke.modifiers and InputEvent.SHIFT_DOWN_MASK != 0)
        assertTrue(macKeyStroke.modifiers and InputEvent.ALT_DOWN_MASK != 0)
        assertTrue(macKeyStroke.modifiers and InputEvent.META_DOWN_MASK != 0)
        assertFalse(macKeyStroke.modifiers and InputEvent.CTRL_DOWN_MASK != 0)
    }

    @Test
    fun `uses official Codex icon and captain vendor metadata`() {
        assertTrue(pluginXml.contains("icon=\"/icons/codex.svg\""))
        assertTrue(pluginXml.contains("<vendor email=\"lowlielt.liu@gmail.com\">lawlielt</vendor>"))
        assertFalse(Regex("""<vendor\b[^>]*>\s*OpenAI\s*</vendor>""").containsMatchIn(pluginXml))

        val icon = resource("/icons/codex.svg")
        val darkIcon = resource("/icons/codex_dark.svg")
        assertTrue(icon.contains(OFFICIAL_CODEX_PATH_PREFIX))
        assertTrue(darkIcon.contains(OFFICIAL_CODEX_PATH_PREFIX))
        assertFalse(icon.contains("#6B57FF"))
        assertFalse(darkIcon.contains("#6B57FF"))
    }

    @Test
    fun `contains no discarded app-server product registrations`() {
        assertFalse(pluginXml.contains("<toolWindow"))
        assertFalse(pluginXml.contains("<applicationConfigurable"))
        assertFalse(pluginXml.contains("SendEditorContext"))
        assertFalse(pluginXml.contains("CodexToolWindowFactory"))
        assertFalse(pluginXml.contains("CodexSettingsConfigurable"))
        assertFalse(pluginXml.contains("consoleFilterProvider"))
    }

    @Test
    fun `loads the JetBrains terminal adapter only with the optional Terminal plugin`() {
        assertTrue(pluginXml.contains("optional=\"true\" config-file=\"plugin-terminal.xml\""))
        assertTrue(terminalXml.contains("JetBrainsCodexTerminalLauncher"))
        assertTrue(terminalXml.contains("CodexTerminalLauncher"))
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource(path)) { "Missing test resource $path" }.readText()

    companion object {
        private const val OFFICIAL_CODEX_PATH_PREFIX =
            "M11.6475 18.3409C11.0975 18.3409 10.575 18.2364 10.08 18.0274"
    }
}
