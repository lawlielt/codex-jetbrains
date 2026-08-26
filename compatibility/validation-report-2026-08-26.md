# Native approval bridge validation report — 2026-08-26

## Compatibility gates

- Gate 1 passed against local `codex-cli 0.149.1`. The authenticated
  same-connection relay declined an upstream file-change request ID `0` while
  the same remote TUI rendered the authoritative patch and completed the item
  as `declined`; an independent run accepted request ID `0`, after which Codex
  (not the harness) wrote the proposed file. See `compatibility/README.md` for
  the exact commands and metadata-only evidence paths.
- Gate 2 passed on this macOS npm/NVM installation. The file-backed login-shell
  probe resolved `/Users/lawlielt/.nvm/versions/node/v24.12.0/bin/codex`, used
  a scratch unauthenticated `CODEX_HOME`, authenticated app-server transport
  with a mode-`0600` token file, waited for explicit HTTP `/readyz`, observed a
  live remote TUI, and reaped both children. POSIX and PowerShell launcher and
  cleanup shapes have deterministic tests; Windows live execution remains a
  manual verification item because this machine is macOS.

## 0.4.1 launcher regression

- The initiating trigger was clicking the Codex action, which submitted the
  generated bridge launch text through `sendCommandToExecute`.
- The earliest divergence from the passing Gate 2 launcher was that production
  pasted a multiline program into the user's interactive shell. Its raw Kotlin
  string emitted two backslashes at continuation points and omitted the final
  quote in `exit "$status"`; `set -u` also changed the user's zsh options.
- The visible result was zsh executing `\\` as a command, Oh My Zsh prompt
  failures, and unfinished `if>` / `dquote>` parser states instead of Codex.
- Version 0.4.1 writes the program to a mode-`0700` private file and submits one
  quoted `/bin/sh <file>` command. The regression test executes that exact
  command with deterministic fake `codex` and `curl` programs, proves the
  remote path runs, proves cleanup removes the state directory, and would fail
  on the original continuation and closing-quote defects.

## 0.4.2 action-thread and relay regressions

### Terminal command state

- The initiating triggers were expanding the editor context menu and invoking
  Send to Codex. Both action paths synchronously reached
  `ShellTerminalWidget.hasRunningCommands()`.
- GoLand 2026.2.1.1 recorded two independent requirements at that boundary:
  the probe may not run on EDT and may not run inside an IntelliJ read action.
  Declaring only a background action-update thread would therefore leave the
  action-performed path and the read-action requirement unresolved.
- The visible symptoms were IDE error reports from both
  `SendToCodexAction.update` and `SendToCodexAction.actionPerformed`; the menu
  could also disappear because the caught assertion was converted to UNKNOWN.
- Version 0.4.2 performs the Terminal probe on the application scheduled
  executor and exposes only an atomic cached state to open, update, and staging
  actions. Command submission marks the cache RUNNING immediately; probe
  failure and terminal termination become UNKNOWN, so stale state never stages
  editor text into an idle shell. Cache tests cover action-path reads, failed
  probes, launch transition, idle transition, and termination; the existing
  controller tests cover running, idle/relaunch, unknown, and closed sessions.

### WebSocket relay

- The initiating trigger was a real Codex edit turn. The terminal reported
  `Connection reset without closing handshake` at the file-change boundary.
  The IDE log's only relay exception was a later `InterruptedException` from
  `WebSocketRelay.close`: the connection-owner worker called `shutdownNow()`
  and then awaited its own executor.
- The earliest production divergence from the passing compatibility gate was
  the frame reader's `FIN=0` branch. A legal first fragment returned `null`,
  indistinguishable from EOF, so the relay skipped a close frame, reset both
  sockets, and then produced the misleading self-interruption. Unfragmented
  file approvals had already passed, which is the disconfirming control for a
  general approval-correlation or response-shape failure.
- The 0.4.1 relay did not retain the first frame header or failure cause, so the
  historical user run cannot prove from captured wire data that its exact frame
  was fragmented. The deterministic counterfactual reproduces the causal
  boundary: a fragmented `item/started` plus fragmented approval request closes
  the old relay before any response, while 0.4.2 reassembles the messages,
  answers on the upstream connection, forwards later traffic, and completes a
  two-sided close handshake.
- Version 0.4.2 distinguishes EOF/protocol faults from clean close frames,
  records the first exception class in the IDE log without protocol bodies or
  tokens, writes the existing generic fallback marker on runtime faults, and
  never shuts down/awaits the executor from its own worker.
- An opt-in no-model smoke used the installed `codex-cli 0.149.1`, an
  unauthenticated scratch `CODEX_HOME`, authenticated app-server, production
  relay, and real remote TUI. The TUI stayed alive through the five-second
  observation window with no relay failure or marker. This validates transport
  startup/liveness only; it does not claim another paid-model native approval
  run.

## Build and package

The final command was:

```bash
./gradlew -Dorg.gradle.java.home=/Library/Java/JavaVirtualMachines/jdk-21.0.2.jdk/Contents/Home \
  test buildPlugin verifyPlugin
```

It passed on JDK 21.0.2. The test report contains **52 tests** across **13 test
suites**: 51 executed with zero failures/errors, and the opt-in real-CLI smoke
was skipped in the ordinary hermetic run. That smoke passed separately with
`CODEX_REAL_TRANSPORT_SMOKE=1` and did not send a model prompt. Plugin Verifier
1.409 reported **Compatible** against
`IC-242.26775.15`; the HTML report is
`build/reports/pluginVerifier/IC-242.26775.15/report.html`.

| Field | Value |
| --- | --- |
| Artifact | `build/distributions/codex-jetbrains-0.4.2.zip` |
| Plugin ID | `io.github.lawlielt.codex.jetbrains` |
| Name | `Codex CLI Companion` |
| Version | `0.4.2` |
| SHA-256 | `766dd37b534928e855a30c20a083f5e8aa2489e3b68c496fb290c09be66f92a1` |
