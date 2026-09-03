# Native approval relay compatibility spike

The current CLI 0.153.0 schema, no-model production-relay smoke, one-turn dynamic Reject/Apply gate, and terminal-first bundle evidence are recorded in [`validation-report-2026-09-03.md`](validation-report-2026-09-03.md). That report keeps the remaining JetBrains UI, restart, and live Windows/WSL gates explicitly unproven.

This directory contains the disposable compatibility gate required before a
native Codex edit-approval bridge can replace the current terminal-only path.
It is not shipped plugin code.

## Topology correction on 2026-08-26

An initial Gate 1 attempt stopped before a model edit could be attempted with
the locally installed `codex-cli 0.149.1`. That conclusion was subsequently
invalidated by focused topology counterfactuals.

The gate starts `codex app-server --listen ws://127.0.0.1:<port>`, exposes an
authenticated loopback relay, and launches the normal TUI with:

```text
codex --remote ws://127.0.0.1:<relay-port> \
  --remote-auth-token-env CODEX_JETBRAINS_RELAY_TOKEN --no-alt-screen
```

The relay is intentionally a WebSocket server on the remote TUI connection.
It requires a capability token in the remote TUI's `Authorization` header and
uses a separate mode-`0600` capability-token file for its authenticated
app-server connection; neither token nor JSON-RPC bodies are written to the
tracked evidence.

The original relay code used the same HTTP-header parser for both directions.
Both attempts failed when the relay's **outbound WebSocket client** received
the app-server's expected upgrade response:

1. `relay gate failed: expected an HTTP GET WebSocket handshake`
2. `relay gate failed: expected an HTTP GET WebSocket handshake, received 'HTTP/1.1 101 Switching Protocols'`

`codex --remote` correctly sends `GET / HTTP/1.1` to its endpoint. The relay
must reply with `101 Switching Protocols` to that TUI connection, then act as
a separate client to the app-server and itself expect `101 Switching Protocols`
there. The original implementation incorrectly expected `GET` in the latter
client-response path.

Three counterfactuals now establish the intended same-connection topology:

1. A direct `codex --remote → codex app-server --listen` TUI stayed connected
   with no connection error.
2. A minimal transparent proxy accepted the TUI's `GET`, returned `101`, sent
   its own `GET` to app-server, accepted app-server's `101`, and forwarded the
   normal remote TUI session without error.
3. The swapped-role observation reproduced the prior `101` exactly: it is the
   normal response received by the relay's upstream client, not a request from
   the remote TUI or an app-server protocol contradiction.

`relay_gate.py` now separates request and response parsing and preserves any
post-handshake bytes.

## Gate 1 passing record

The gate passed with `codex-cli 0.149.1` on 2026-08-26. It uses a temporary
authenticated `CODEX_HOME` containing only a copied `auth.json`, deletes that
directory during cleanup, and never logs the credential or the relay token.

The headless PTY does not emulate the terminal's Kitty keyboard-protocol
negotiation faithfully: CR, `CSI 13 u`, and `CSI 13 ; 1 u` all placed text in
the composer without emitting the remote TUI's `turn/start`. This is a
gate-driver input limitation, not a relay/product behavior. The final probes
therefore supply the disposable user prompt using Codex's normal positional
CLI prompt path, which emits `turn/start` from the same remote TUI connection.
JetBrains Terminal continues to own real user keystrokes in the product.

For repeatability, the harness disables the host-injected `codex_apps` MCP
only during the probe: it otherwise stayed booting and queued the prompt. This
does not alter user configuration and is not a proposed product launch flag.

```bash
# Same remote TUI connection: intercept a real file approval and decline it.
python3 -B compatibility/relay_gate.py --disable-apps --mode reject \
  --output compatibility/runtime/relay-gate-final-reject

# Independent real approval: accept, then verify Codex performs the write.
python3 -B compatibility/relay_gate.py --disable-apps --mode accept \
  --output compatibility/runtime/relay-gate-initial-accept
```

The final authenticated rerun (same CLI, separate reject and apply probes)
also passed with app-server `--ws-auth capability-token --ws-token-file` and
an upstream relay `Authorization: Bearer` header. The ignored metadata evidence
recorded:

- **Reject:** one remote-TUI `turn/start`, one correlated `fileChange` item,
  upstream request ID `0`, a `decline` response on that same connection,
  `item/completed` with `declined`, unchanged `gate-reject.txt`, and the same
  TUI's `Edited gate-reject.txt (+1 -1)` unified patch (`-before-reject`,
  `+after-reject`). The plugin/harness did not write the proposed file.
- **Apply:** one remote-TUI `turn/start`, one correlated file-change request,
  `accept` on request ID `0`, the original apply preimage still present at the
  moment of acceptance, and a completed item followed by Codex writing
  `after-apply`. The harness never writes that result.

This clears Gate 1.

## Gate 2 passing record

`supervised_launch_gate.py` is a file-backed launcher probe rather than an
inline shell experiment. It writes its launch script, app-server stdout/stderr,
terminal transcript, PID records, readiness record, cleanup status, and compact
metadata evidence beneath its ignored output directory. It waits for app-server
`http://127.0.0.1:<port>/readyz` before launching the remote TUI; it never
treats a PID file as readiness.

```bash
python3 -B compatibility/supervised_launch_gate.py \
  --output compatibility/runtime/supervised-launch-gate
```

On this macOS machine on 2026-08-26 the probe passed with the npm/NVM shell
resolution `/Users/lawlielt/.nvm/versions/node/v24.12.0/bin/codex`. It starts
the app-server with `--ws-auth capability-token --ws-token-file` using a
mode-`0600` private token file, and the remote TUI authenticates with a token
environment variable. Its fresh `CODEX_HOME` had no `auth.json`; the remote
TUI reached its normal login screen and was alive before the harness terminated
it. The server's recorded stderr confirmed the expected `readyz` endpoint, and
the TUI exit caused the shell trap to reap the app-server. The final evidence has exit code `0`,
`ready: true`, `tui_running_before_cleanup: true`, and both recorded child PIDs
not alive after launcher exit.

The probe also deterministically checks the portable launch-script shapes:
POSIX uses an `EXIT INT TERM` trap with `kill` plus `wait`; PowerShell uses
`Start-Process -FilePath codex` with argument arrays and a `finally` block
that stops both the TUI and app-server. Windows live execution was not
available on this machine, so that remains a manual verification item rather
than a claimed live-platform result.

This clears Gate 2 for the locally available npm/NVM installation and permits
the production implementation to proceed. The native path must still fall
back to literal `codex` if its own capability probe or supervised startup
fails.

## Dynamic `openDiff` gate passing record

The captain-approved dynamic-tool gate passed with `codex-cli 0.149.1` on
2026-08-26. It starts the normal remote TUI through the authenticated relay,
injects `initialize.capabilities.experimentalApi`, one strict `openDiff` tool,
and the source-edit instruction at the remote TUI's `thread/start` boundary.
It uses the same continuing thread and turn for the complete sequence.

```bash
python3 -B compatibility/relay_gate.py --disable-apps --mode dynamic \
  --output compatibility/runtime/dynamic-open-diff-gate
```

The ignored, metadata-only evidence records that two real `item/tool/call`
requests arrived with strict arguments; the first returned `success: false`,
wrote nothing, and was rendered as rejected by the same terminal UI. The
second callback committed reviewer-edited full content before returning
`success: true`, then the same turn completed. A controlled shell write under
the read-only sandbox did not mutate the disposable worktree. The driver does
not retain prompts, paths, tool arguments, protocol bodies, or credentials in
this tracked document.

## Reproduction

Run from the repository root with an authenticated local Codex home:

```bash
python3 -B compatibility/relay_gate.py --output compatibility/runtime/relay-gate
```

The ignored output directory retains the raw terminal transcript and a compact
metadata-only evidence JSON for a local investigation.  The script uses a
disposable project beneath that directory and terminates its TUI and app-server
children on every exit path.
