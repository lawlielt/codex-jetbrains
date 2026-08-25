package com.openai.codex.jetbrains

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDescriptorTest {
    private val pluginXml = resource("/META-INF/plugin.xml")
    private val terminalXml = resource("/META-INF/plugin-terminal.xml")

    @Test
    fun `registers the Codex launcher in toolbar and menu`() {
        assertTrue(pluginXml.contains("com.openai.codex.jetbrains.actions.OpenCodexTerminalAction"))
        assertTrue(pluginXml.contains("group-id=\"MainToolbarRight\""))
        assertTrue(pluginXml.contains("group-id=\"ToolsMenu\""))
        assertTrue(pluginXml.contains("icon=\"/icons/codex.svg\""))
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
