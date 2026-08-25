# Codex for JetBrains

Codex for JetBrains is a small launcher for the real interactive Codex CLI. Click the Codex toolbar icon and the plugin opens or focuses a project-scoped `Codex` tab in JetBrains' built-in Terminal, starts it at the current project root, and submits the plain `codex` command.

Authentication, model and reasoning selection, permissions, configuration, session state, and every interactive prompt remain inside the Codex CLI. The plugin has no chat tool window, executable-path setting, login UI, credential handling, approval UI, or app-server process.

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

Clicking the action again focuses the live project tab instead of launching another Codex process. If Codex has returned to the shell prompt, a later click starts `codex` again in the same tab; closing the tab makes the next click create a fresh one.

If the Terminal plugin is unavailable, the action asks you to enable it. If the shell cannot resolve `codex`, install or configure the CLI in that same JetBrains Terminal environment and retry there.

## Why the CLI launches inside Terminal

Version 0.1 launched a configured absolute Codex script from the IDE JVM and started `codex app-server`. That made the plugin a second chat client and caused an environment-specific startup failure:

- The initiating trigger was clicking the old Codex tool-window integration, which made the JVM launch the configured absolute script.
- JetBrains terminal shells load the user's shell/NVM environment, while a GUI IDE/JVM may not have NVM's `node` directory on `PATH`.
- The visible symptom was exit 127 with `env: node: No such file or directory` during validation or app-server startup.
- The same absolute script was already shown to fail without NVM's bin directory and succeed when that directory was on `PATH`.
- The corrected path submits exactly `codex` to the JetBrains Terminal shell. The CLI now owns environment resolution and authentication.

`CodexTerminalControllerTest` preserves this boundary through a terminal-only test seam: it verifies the project root, `Codex` tab name, exact command, live-session reuse, and relaunch behavior without exposing a JVM executable or process-launch API.

## Build, test, and run

Versions are centralized in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Use JDK 21 and the checked-in wrapper:

```bash
./gradlew test buildPlugin verifyPlugin
./gradlew runIde
```

For a sandbox check, open a project in `runIde`, click the toolbar action, and confirm that one interactive `Codex` tab opens at the project root and runs `codex` once. Exit Codex and click again to confirm reuse; close the terminal tab and click again to confirm recreation.

## Architecture and clean-room policy

- `actions/OpenCodexTerminalAction.kt` owns the always-visible toolbar/menu action and the concise missing-Terminal notification.
- `terminal/CodexTerminalController.kt` owns project-scoped reuse and the exact command boundary without depending on Terminal APIs.
- `terminal/JetBrainsCodexTerminalLauncher.kt` is loaded only through `plugin-terminal.xml` and uses the user's configured JetBrains shell.

The captain's screenshots and the installed reference plugin's public manifest were used only to confirm observable toolbar-to-Terminal behavior. No Anthropic code, text, icons, identifiers, bytecode, branding, or assets are included or copied. The Codex icon and all implementation code in this repository are original.
