# Terminal-first bundle B validation report — 2026-09-03

## Baseline

Work started from clean local `main` at `11b3f23f7504d8afd9c89275367a6e49729359ae`, plugin 0.4.5, in an isolated disposable worktree. Before source edits, JDK 21.0.2 ran `./gradlew test buildPlugin verifyPlugin`; the build passed and Plugin Verifier 1.410 reported **Compatible** with `IC-242.26775.15`.

The accepted CJ labels below retain their strict meanings: **implemented**, **partially implemented**, **not a plugin responsibility**, and **blocked by an external/platform gate**.

## Current CLI and keyboard gates

- **CJ-01 — blocked by an external/platform gate.** With `codex-cli 0.153.0` idle in a direct PTY, `/plan` entered Plan mode and the normal Shift+Tab byte sequence (`CSI Z`) returned to Default mode. This establishes the system-terminal counterfactual and disconfirms a general CLI idle-state failure. A real build-242 JetBrains Terminal key-transport check remains manual. No key handler or Esc/Alt+Enter/BackTab remapping was added.
- **CJ-15 — runtime protocol implemented; JetBrains visual gate blocked by an external/platform gate.** Schema generation produced 416 files and retained `dynamicTools`, `experimentalApi`, and `item/tool/call`; the rejected IDE-context/diagnostics strings remained absent. The opt-in no-model production-relay smoke kept a real remote TUI connected for five seconds.
- The captain-authorized single paid model turn used the existing disposable dynamic gate with apps disabled and a read-only sandbox. On one continuing thread/turn it issued exactly two strict `openDiff` calls: Reject returned failure and wrote nothing; Apply returned success after reviewer content was written; the same TUI rendered rejection and the turn completed. The controlled shell write did not mutate its fixture. The harness retained only sanitized counts/booleans plus the terminal transcript, removed its copied Codex home, and now also removes its app-server token file. No capability token or raw private protocol body was retained. Actual built-in Diff editor-tab rendering in a real JetBrains UI remains unproven.

## Implemented product changes

- **CJ-05 — implemented for the supported scoped surface.** Build 242's public `JBTerminalWidget.addMessageFilter(Filter)` is reachable through `JBTerminalWidget.asJediTermWidget(TerminalWidget)`. The plugin attaches its filter only to the newly created Codex widget. It recognizes conservative colon and `#L` line/column/range forms, resolves only existing regular files, canonicalizes before enforcing the project boundary, rejects traversal and symlink escape, and selects line ranges on navigation. Build 242's block Terminal exposes no supported per-widget filter, so that variant is an explicit no-op rather than a project-wide `consoleFilterProvider` registration.
- **CJ-06 — implemented.** `OpenTerminal` has default `Ctrl+Alt+Shift+K`; JetBrains' macOS parent-keymap conversion produces Option+Command+Shift+K. A scan of all bundled build-242 XML descriptors found no use of that exact default chord. JetBrains Keymap remains authoritative.
- **CJ-09 — implemented.** The existing `SendEditorReference` action is also registered in the confirmed build-242 `Floating.CodeToolbar` group. It reuses the existing live-session gate and literal no-newline staging transport.

## Evidence-led non-changes

- **CJ-08 — partially implemented.** Build-242 bytecode/API inspection shows restored tabs persist only `myTabName`, `myShellCommand`, `myIsUserDefinedTabTitle`, `myWorkingDirectory`, and `myCommandHistoryFileName`. None is a plugin ownership marker, and the public widget set cannot distinguish this integration from an unrelated Codex tab with the same generic attributes. No startup scan, token persistence, terminal scraping, or speculative reclaim was added.
- **CJ-13 — partially implemented.** The supported Windows-hosted topology is a local Windows project plus a generated `powershell.exe` launcher and a Windows Codex CLI resolvable by that process. The recursive schema probe now uses `Get-ChildItem -File -Recurse` plus `Select-String -LiteralPath`, avoiding the unsupported `Select-String -Recurse` shape. Tests cover spaces and single quotes in deterministic Windows path/script construction. A WSL shell profile does not select a WSL-native Codex binary or map Windows paths; no live Windows/WSL environment was available, so no broader claim or mapping was added.

## Final package

JDK 21.0.2 ran `./gradlew test buildPlugin verifyPlugin --rerun-tasks` successfully. The JUnit XML reports 77 tests across 18 suites: 76 executed, the opt-in real-CLI smoke skipped in the ordinary hermetic run, and zero failures/errors. The separate opt-in no-model smoke and single paid dynamic gate passed as recorded above.

Plugin Verifier 1.410 reported **Compatible** with `IC-242.26775.15`. Direct inspection of the built ZIP and its nested plugin JAR confirmed the packaged filter class and these descriptor values:

| Field | Value |
| --- | --- |
| Plugin ID | `io.github.lawlielt.codex.jetbrains` |
| Name | `Codex CLI Companion` |
| Version | `0.4.6` |
| Since build | `242` |
| Artifact | `build/distributions/codex-jetbrains-0.4.6.zip` |
| SHA-256 | `66443e475e81fa4bf61af1d01547bcae310c49819d8f33e1251cb4c699a25561` |
