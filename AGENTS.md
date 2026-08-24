# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Use JDK 21 and the checked-in wrapper. The main local gates are `./gradlew test buildPlugin verifyPlugin`; dependency and target-IDE versions live in `gradle/libs.versions.toml`.
- App-server transport and protocol logic belongs under `src/main/kotlin/com/openai/codex/jetbrains/protocol`; keep it independent of tool-window UI and cover stable wire behavior with focused unit tests.
- `CodexProjectService` is the one-process-per-project lifecycle/persistence boundary. Never persist or log API keys, and keep approval responses scoped by request/thread/turn/item.
- The supported baseline is IntelliJ Platform build 242. Prefer stable public Platform APIs and keep the Terminal integration optional.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
