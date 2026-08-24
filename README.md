# Codex for JetBrains

Codex for JetBrains is a clean-room IntelliJ Platform plugin that embeds a native coding-agent workflow in a `Codex` tool window. It launches the official local `codex app-server` and communicates over its stable stdio JSONL protocol; it does not proxy prompts through a separate service.

## Requirements

- IntelliJ Platform 2024.2 or later (the build targets IntelliJ IDEA Community 2024.2.6 / build 242).
- JDK 21 for building and running the Gradle tasks.
- A current [Codex CLI](https://learn.chatgpt.com/docs/developers) installation whose `codex app-server` command is available.
- A ChatGPT account supported by Codex, or an OpenAI API key. Providers configured directly in Codex can report that OpenAI authentication is not required.

The executable defaults to `codex`. If the IDE inherits a different `PATH` than the shell, set an absolute path under **Settings | Tools | Codex** and use **Validate**.

## Build, test, and run

Versions are centralized in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The project uses IntelliJ Platform Gradle Plugin 2.18.1, Gradle 9.2, Java 21, and Kotlin 1.9 language/API compatibility for the 2024.2 platform line.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew test
./gradlew buildPlugin
./gradlew verifyPlugin
./gradlew runIde
```

`runIde` opens a sandbox IDE. Open a project, show **View | Tool Windows | Codex**, and confirm the status becomes **Connected**. Model and reasoning controls are populated by `model/list`; the plugin contains no current model-id list.

## Using the tool window

- Enter a task and select **Send**. A project-scoped app-server thread is started on the first turn, and its thread id is stored in the IDE workspace file so it can be resumed.
- Use **Interrupt** to request `turn/interrupt` for the active thread and turn.
- Choose an approval policy and sandbox. Safe defaults are **Ask for untrusted commands** and **Workspace write**, with network disabled.
- Select **Add editor context**, or use **Add Selection to Codex** from the editor popup/floating toolbar. The default shortcut is `Ctrl+Alt+K` (`Cmd+Option+K` on macOS). Context uses a project-relative reference such as `@src/Main.kt#L10-L24`, selected text when present, and bounded editor diagnostics.
- Project references in streamed output are clickable when they use the same `@path#Lx-Ly` form and point to an existing file inside the project.
- **Tools | Open Codex CLI in Terminal** opens the regular CLI at the project root when the optional bundled Terminal plugin is enabled. If Terminal or the executable is unavailable, the action shows a command the user can run manually.

## Authentication

The account row reflects `account/read` and account notifications from app-server.

- **ChatGPT** starts the managed browser login and opens the returned authorization URL.
- **Device code** opens the verification page and displays the returned one-time code.
- **API key** prompts with a password field and sends the value directly to `account/login/start`.
- **Logout** calls `account/logout`.

The plugin never writes a raw API key to its settings, project state, transcript, notifications, or logs. Transient API-key data is handed to the local app-server, which owns the configured Codex credential lifecycle. Diagnostic text is redacted for API-key, bearer-token, and token-field patterns.

## Permissions and diff review

App-server command, managed-network, file-change, and permission requests are routed by the exact request id plus `threadId`, `turnId`, and `itemId`. The plugin fails closed for unsupported server-request methods and never auto-approves a request.

- Command and network prompts show their command or destination and offer allow-once, allow-for-session, decline, and cancel-turn choices.
- Permission prompts show the requested profile. A grant returns only the requested permissions and defaults to turn scope.
- File-change prompts open JetBrains Diff before the decision dialog. Multiple proposed files are presented as a diff chain. A path outside the project root is not loaded into the preview.
- Selecting **Never ask** or **Full access** requires a separate warning confirmation. These choices are intentionally not defaults.

## Architecture

- `protocol/` contains JSONL framing/correlation, handshake, process supervision, minimal protocol mapping, streaming reduction, approval routing, thread identity, and redaction. It does not depend on the tool-window UI and is covered by focused unit tests.
- `service/CodexProjectService.kt` is the one-per-project lifecycle and persistence boundary.
- `context/` collects and bounds only current-editor context.
- `ui/` and `actions/` contain IntelliJ Platform integrations, including Diff, navigation, tool-window, settings, and optional Terminal behavior.

Unknown notification methods and fields are ignored for forward compatibility. Malformed JSONL lines are reported safely while the reader continues; process exits trigger supervised restart attempts and always leave a manual Restart path.

## Known limitations

- The transcript is intentionally lightweight and does not reconstruct all historical turns after an IDE restart; the app-server thread identity is resumed and subsequent turns retain server-side history.
- Clickable output links currently recognize the project-relative `@path#Lx-Ly` format and reject paths escaping the project root.
- Editor diagnostics come from highlights attached to the active editor and are capped; no unrelated project scan or file content upload is performed.
- The optional Terminal action uses a small runtime compatibility bridge because the Terminal widget API differs across supported IDE releases. Core Codex features do not require Terminal.
- Experimental app-server APIs and the experimental WebSocket transport are deliberately not used.

## Clean-room reference policy

The product behavior described in the [JetBrains Codex announcement](https://blog.jetbrains.com/ai/2026/01/codex-in-jetbrains-ides/) informed the high-level experience. An installed Claude Code plugin distribution was inspected only for public manifest metadata, resource names, package/class names, and observable packaging behavior. No Anthropic bytecode was decompiled, and no Anthropic code, text, icons, branding, identifiers, or assets were copied. This implementation was written independently from stable JetBrains Platform APIs and the official [Codex app-server documentation](https://learn.chatgpt.com/docs/app-server).
