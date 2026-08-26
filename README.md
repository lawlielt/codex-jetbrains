# Codex CLI Companion for JetBrains

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Codex CLI Companion for JetBrains is an unofficial, community-maintained companion for the real interactive Codex CLI. Click the Codex toolbar icon and the plugin opens or focuses one project-scoped `Codex` tab in JetBrains' built-in Terminal at the project root. Login and normal interaction remain in that terminal. From an editor, **Send to Codex** stages the current file or selected lines in the running CLI composer without submitting the turn.

This project is not affiliated with or endorsed by OpenAI or JetBrains.

Author/vendor: **lawlielt** · [lowlielt.liu@gmail.com](mailto:lowlielt.liu@gmail.com)

Authentication, model and reasoning selection, permissions, configuration, session state, command approvals, network approvals, MCP approvals, and every normal interactive prompt remain inside the Codex CLI. The plugin has no chat tool window, executable-path setting, login UI, credential store, or manual hook/MCP setup.

On compatible CLI/protocol builds, the same terminal tab transparently runs a shell-supervised local app-server connected through an authenticated loopback relay. The relay injects one session-scoped `openDiff` dynamic tool and developer instructions while keeping the Codex workspace sandbox read-only. Source changes therefore open an editable native JetBrains diff: **Apply** commits exactly the reviewer-edited right-hand content through JetBrains write APIs and returns a successful dynamic-tool result; **Reject** or closing the diff writes nothing and returns a structured rejection to the same Codex turn. The earlier correlated `item/fileChange/requestApproval` bridge remains a compatibility fallback. Command, network, MCP, permission, and unrelated dynamic-tool approvals remain unchanged in the terminal.

## Requirements

- IntelliJ Platform 2024.2 or later (the build targets IntelliJ IDEA Community 2024.2.6 / build 242).
- The bundled JetBrains Terminal plugin enabled.
- A current [Codex CLI](https://developers.openai.com/codex/cli/) installation available as `codex` in the shell configured under **Settings | Tools | Terminal**.
- JDK 21 only when building the plugin from source.

## Install and use

1. Install the plugin ZIP from **Settings | Plugins | Install Plugin from Disk**.
2. Open a local project.
3. Click the Codex icon in the main toolbar. **Tools | Codex** is the menu fallback.
4. Complete first-run login, or continue an existing authenticated session, in the `Codex` Terminal tab.
5. In a project file, right-click and choose **Send to Codex**. The shortcut is **Option+Command+K** on macOS and **Ctrl+Alt+K** on Linux/Windows.

**Send to Codex** requires the project’s `Codex` Terminal tab to be open with the Codex CLI still running. The editor action is absent until that matching session is live. It never opens a terminal or launches another Codex process.

- With selected text, it stages `@path#Lstart-end` using one-based inclusive lines (or `@path#Lstart` for one line).
- With no selection, it stages `@path` for the current file.
- It supports only files inside the current project and uses forward slashes in project-relative paths.
- It selects and focuses the existing `Codex` tab, inserts one literal reference followed by a safe separating space, and does not press Enter or append a newline.

Clicking the action again focuses the live project tab instead of launching another Codex process. If Codex has returned to the shell prompt, a later click starts a new compatible session in the same tab; closing the tab makes the next click create a fresh one.

If the Terminal plugin is unavailable, the action asks you to enable it. If the shell cannot resolve `codex`, install or configure the CLI in that same JetBrains Terminal environment and retry there. If the installed CLI does not expose the required app-server/remote capability, or supervised startup fails, the terminal prints one short non-blocking explanation and cleanly continues with normal literal `codex`; it never falls back to terminal-output parsing.

The native bridge writes its launcher into the same private temporary directory as its capability-token files. The interactive Terminal receives only one quoted command that runs this isolated script, so shell options and multiline parsing never leak into the user's zsh/bash session. The script is removed together with its tokens and app-server logs when the session ends.

The relay keeps small WebSocket payloads in memory and spills larger payloads into short-lived files in that private directory. It scans only the top-level JSON-RPC method while streaming unrelated responses, so growing plugin-marketplace catalogs and dynamic-tool payloads do not require a fixed in-memory frame limit. Only the selected native approval or `openDiff` message is materialized for IDE handling; an oversized legacy file-change preview returns unchanged to the normal terminal approval UI instead of resetting the session.

## Why the CLI launches inside Terminal

Version 0.1 launched a configured absolute Codex script from the IDE JVM and started `codex app-server`. That made the plugin a second chat client and caused an environment-specific startup failure:

- The initiating trigger was clicking the old Codex tool-window integration, which made the JVM launch the configured absolute script.
- JetBrains terminal shells load the user's shell/NVM environment, while a GUI IDE/JVM may not have NVM's `node` directory on `PATH`.
- The visible symptom was exit 127 with `env: node: No such file or directory` during validation or app-server startup.
- The same absolute script was already shown to fail without NVM's bin directory and succeed when that directory was on `PATH`.
- The corrected path keeps executable resolution and authentication in the JetBrains Terminal shell. The compatible native-diff launch command capability-probes and starts its app-server there too, preserving NVM/Homebrew/user-shell behavior; it has no JVM `ProcessBuilder` path.

`CodexTerminalControllerTest` preserves this boundary through a terminal-only test seam: it verifies the project root, `Codex` tab name, live-session reuse, relaunch behavior, and literal composer staging without exposing a JVM executable or process-launch API.

## Native approval safety

The relay binds only to loopback and authenticates both its remote-TUI and app-server WebSocket connections with short-lived capability tokens held in private temporary files. Secrets are not placed in command arguments or logs. For `openDiff`, it strictly validates the operation, project-relative paths, full preimage, and full proposed content before opening a diff; Apply re-checks staleness and unsaved documents before committing. Update, add, delete, and move requests are supported. The legacy fallback still correlates `threadId`, `turnId`, `itemId`, and upstream request ID before validating its unified patch.

Paths must remain inside the project root and cannot escape through symlinks. Unknown, malformed, stale, dirty-editor, conflicting, duplicate, late, or disposed-project `openDiff` requests fail closed with a single structured rejection. Closing a native diff is Reject. Pending requests reject on disconnect or project/plugin disposal; the shell trap reaps the app-server when the remote TUI exits.

## Build, test, and run

Versions are centralized in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Use JDK 21 and the checked-in wrapper:

```bash
./gradlew test buildPlugin verifyPlugin
./gradlew runIde
```

`buildPlugin` writes the installable archive to `build/distributions/codex-jetbrains-<version>.zip`.

For a sandbox check, open a project in `runIde`, click the toolbar action, and confirm that one interactive `Codex` tab opens at the project root. Select editor lines and invoke **Send to Codex** to confirm the reference appears in the composer without submitting; clear the selection and repeat to confirm a file-only reference. On a compatible CLI, request an edit and verify that the native read-only diff's Apply/Reject decision resumes the same terminal turn; use Reject to confirm the terminal prints Codex's authoritative decline. Exit Codex and confirm **Send to Codex** disappears from the editor popup; click the toolbar action to confirm tab reuse and CLI relaunch, then close the terminal tab and click again to confirm recreation.

## Architecture and clean-room policy

- `actions/OpenCodexTerminalAction.kt` owns the always-visible toolbar/menu action and the concise missing-Terminal notification.
- `actions/SendToCodexAction.kt` and `actions/CodexEditorReference.kt` own editor-popup gating and project-relative reference formatting.
- `terminal/CodexTerminalController.kt` owns project-scoped reuse and the exact command boundary without depending on Terminal APIs.
- `terminal/JetBrainsCodexTerminalLauncher.kt` is loaded only through `plugin-terminal.xml` and uses the user's configured JetBrains shell.
- `bridge/BridgeSessionBundle.kt` owns one short-lived relay/session bundle; `WebSocketRelay.kt`, `OpenDiffInjection.kt`, and `RelayPayload.kt` inject and forward the remote JSON-RPC stream with disk-backed large-payload spooling.
- `bridge/OpenDiffCoordinator.kt` and `JetBrainsOpenDiffPresenter.kt` validate, display, and commit dynamic-tool source proposals; `FileChangeApprovalCoordinator.kt` remains the read-only `fileChange` fallback.

The captain's screenshots and the installed reference plugin were used only to confirm observable editor-to-Terminal behavior and applicable Platform APIs. No Anthropic code, text, icons, identifiers, bytecode, branding, or assets are included or copied. All implementation code in this repository is original.

The Codex mark is the exact SVG path from the `--startup-logo-mask` embedded at `/webview/index.html` in the locally installed OpenAI Codex application (`/Applications/ChatGPT.app`, bundle identifier `com.openai.codex`, version `26.818.41705`; source `app.asar` SHA-256 `7ab7808f570fac3839943c0c324eb46b3ed34bee2647c75fd2155b39509b361e`). The packaged SVGs change only canvas dimensions and neutral light/dark theme colors; the official path geometry is unchanged.

## Contributing

Issues and pull requests are welcome. Keep changes within the terminal-first product boundary and run `./gradlew test buildPlugin verifyPlugin` with JDK 21 before submitting a pull request. Compatibility evidence and the locally executable Gate 1/Gate 2 probes are in [`compatibility/README.md`](compatibility/README.md).

## License

Copyright 2026 lawlielt.

Licensed under the [Apache License 2.0](LICENSE).
