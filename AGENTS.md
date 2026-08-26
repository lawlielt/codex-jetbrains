# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Use JDK 21 and the checked-in wrapper. The main local gates are `./gradlew test buildPlugin verifyPlugin`; dependency and target-IDE versions live in `gradle/libs.versions.toml`.
- The terminal-first boundary is preserved: the plugin never discovers/starts the executable from the JVM, adds a path/login/config UI, or owns a chat. `BridgeLaunchCommand` runs inside the configured Terminal shell, capability-probes the CLI, and falls back to literal `codex`; compatible sessions use a private loopback app-server relay solely for native file-change decisions.
- `CodexTerminalController` is the project-scoped reuse/test seam for both launch and literal composer staging; `sendCommandToExecute` submits the shell-owned launch command, while editor references use TTY writes with no newline. `terminal/JetBrainsCodexTerminalLauncher.kt` is loaded only by `META-INF/plugin-terminal.xml` so actions can handle a missing Terminal plugin without linking Terminal classes.
- The supported baseline is IntelliJ Platform build 242. Prefer stable public Platform APIs, keep Terminal optional, and preserve focused tests for exact command submission and duplicate-session prevention.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
