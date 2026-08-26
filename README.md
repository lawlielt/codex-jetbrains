# Codex CLI Companion for JetBrains

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Codex CLI Companion for JetBrains is an unofficial, community-maintained companion for the real interactive Codex CLI. Click the Codex toolbar icon and the plugin opens or focuses one project-scoped `Codex` tab in JetBrains' built-in Terminal at the project root. Login and normal interaction remain in that terminal. From an editor, **Send to Codex** stages the current file or selected lines in the running CLI composer without submitting the turn.

This project is not affiliated with or endorsed by OpenAI or JetBrains.

Author/vendor: **lawlielt** · [lowlielt.liu@gmail.com](mailto:lowlielt.liu@gmail.com)

Authentication, model and reasoning selection, permissions, configuration, session state, command approvals, network approvals, MCP approvals, and every normal interactive prompt remain inside the Codex CLI. The plugin has no chat tool window, executable-path setting, login UI, credential store, or manual hook/MCP setup.

On compatible CLI/protocol builds, the same terminal tab transparently runs a shell-supervised local app-server connected through an authenticated loopback relay. Only `item/fileChange/requestApproval` is intercepted: Codex file proposals open a read-only JetBrains diff with **Apply** and **Reject**. Apply returns `accept` to the originating app-server request; Reject or closing the diff returns `decline`. Codex, not the plugin, writes accepted files and renders the authoritative result in the same TUI. All other approval types remain unchanged in the TUI.

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

## Why the CLI launches inside Terminal

Version 0.1 launched a configured absolute Codex script from the IDE JVM and started `codex app-server`. That made the plugin a second chat client and caused an environment-specific startup failure:

- The initiating trigger was clicking the old Codex tool-window integration, which made the JVM launch the configured absolute script.
- JetBrains terminal shells load the user's shell/NVM environment, while a GUI IDE/JVM may not have NVM's `node` directory on `PATH`.
- The visible symptom was exit 127 with `env: node: No such file or directory` during validation or app-server startup.
- The same absolute script was already shown to fail without NVM's bin directory and succeed when that directory was on `PATH`.
- The corrected path keeps executable resolution and authentication in the JetBrains Terminal shell. The compatible native-diff launch command capability-probes and starts its app-server there too, preserving NVM/Homebrew/user-shell behavior; it has no JVM `ProcessBuilder` path.

`CodexTerminalControllerTest` preserves this boundary through a terminal-only test seam: it verifies the project root, `Codex` tab name, live-session reuse, relaunch behavior, and literal composer staging without exposing a JVM executable or process-launch API.

## Native approval safety

The relay binds only to loopback and authenticates both its remote-TUI and app-server WebSocket connections with short-lived capability tokens held in private temporary files. Secrets are not placed in command arguments or logs. The plugin correlates each approval with its `threadId`, `turnId`, `itemId`, and upstream request ID, then validates every unified-patch hunk against the current disk preimage before opening a diff.

Paths must remain inside the project root. Unknown, malformed, stale, dirty-editor, conflicting, duplicate, late, or multi-file-partial requests fail closed with `decline`; a multi-file item is one atomic decision. The native preview reconstructs content only for read-only display and never writes a proposed file. Pending approvals decline on disconnect or project/plugin disposal; the shell trap reaps the app-server when the remote TUI exits.

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
- `bridge/BridgeSessionBundle.kt` owns one short-lived relay/session bundle; `WebSocketRelay.kt` forwards the remote JSON-RPC stream except the correlated file-change approval.
- `bridge/FileChangeApprovalCoordinator.kt`, `FileChangeApproval.kt`, and `JetBrainsNativeDiffPresenter.kt` validate and display read-only proposals, then return only Codex's `accept`/`decline` decision.

The captain's screenshots and the installed reference plugin were used only to confirm observable editor-to-Terminal behavior and applicable Platform APIs. No Anthropic code, text, icons, identifiers, bytecode, branding, or assets are included or copied. All implementation code in this repository is original.

The Codex mark is the exact SVG path from the `--startup-logo-mask` embedded at `/webview/index.html` in the locally installed OpenAI Codex application (`/Applications/ChatGPT.app`, bundle identifier `com.openai.codex`, version `26.818.41705`; source `app.asar` SHA-256 `7ab7808f570fac3839943c0c324eb46b3ed34bee2647c75fd2155b39509b361e`). The packaged SVGs change only canvas dimensions and neutral light/dark theme colors; the official path geometry is unchanged.

## Contributing

Issues and pull requests are welcome. Keep changes within the terminal-first product boundary and run `./gradlew test buildPlugin verifyPlugin` with JDK 21 before submitting a pull request. Compatibility evidence and the locally executable Gate 1/Gate 2 probes are in [`compatibility/README.md`](compatibility/README.md).

## License

Copyright 2026 lawlielt.

Licensed under the [Apache License 2.0](LICENSE).
