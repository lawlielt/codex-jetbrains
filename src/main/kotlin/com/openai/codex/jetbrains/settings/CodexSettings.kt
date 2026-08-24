package com.openai.codex.jetbrains.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "CodexSettings", storages = [Storage("codex.xml")])
class CodexSettings : PersistentStateComponent<CodexSettings.State> {
    data class State(var executablePath: String = "codex")

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var executablePath: String
        get() = state.executablePath.ifBlank { "codex" }
        set(value) {
            state.executablePath = value.trim().ifBlank { "codex" }
        }

    companion object {
        fun getInstance(): CodexSettings = service()
    }
}
