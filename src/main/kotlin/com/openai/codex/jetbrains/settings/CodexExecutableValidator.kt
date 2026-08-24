package com.openai.codex.jetbrains.settings

import com.openai.codex.jetbrains.protocol.SensitiveDataRedactor
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ExecutableValidation(val valid: Boolean, val message: String)

object CodexExecutableValidator {
    fun validate(executable: String, workingDirectory: Path): ExecutableValidation {
        val command = executable.trim().ifBlank { "codex" }
        return try {
            val process = ProcessBuilder(command, "app-server", "--help")
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ExecutableValidation(false, "Timed out while checking '$command app-server --help'.")
            } else if (process.exitValue() == 0) {
                ExecutableValidation(true, "Codex app-server is available.")
            } else {
                val output = SensitiveDataRedactor.redact(process.inputStream.bufferedReader().readText()).take(800)
                ExecutableValidation(false, "Codex app-server returned exit ${process.exitValue()}: $output")
            }
        } catch (error: Throwable) {
            ExecutableValidation(
                false,
                "Could not run '$command app-server'. Install the Codex CLI or enter its absolute path. " +
                    SensitiveDataRedactor.redact(error.message.orEmpty()),
            )
        }
    }
}
