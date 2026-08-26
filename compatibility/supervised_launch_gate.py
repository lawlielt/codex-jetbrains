#!/usr/bin/env python3
"""Gate 2: a disposable, file-backed shell launch and cleanup probe."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import pty
import select
import secrets
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import termios
import time
from pathlib import Path


def posix_bootstrap(endpoint: str) -> str:
    return f'''set -u
server_pid=""; tui_pid=""
cleanup() {{
  [ -n "$tui_pid" ] && kill "$tui_pid" 2>/dev/null || true
  [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null || true
  [ -n "$tui_pid" ] && wait "$tui_pid" 2>/dev/null || true
  [ -n "$server_pid" ] && wait "$server_pid" 2>/dev/null || true
}}
trap cleanup EXIT INT TERM
codex app-server --listen {endpoint} & server_pid=$!
'''


def powershell_bootstrap(endpoint: str) -> str:
    return f'''$ErrorActionPreference = "Stop"
$server = $null; $tui = $null
try {{
  $server = Start-Process -FilePath codex -ArgumentList @("app-server", "--listen", "{endpoint}") -PassThru
  $tui = Start-Process -FilePath codex -ArgumentList @("--remote", "{endpoint}") -PassThru
}} finally {{
  if ($tui -and -not $tui.HasExited) {{ Stop-Process -Id $tui.Id -Force }}
  if ($server -and -not $server.HasExited) {{ Stop-Process -Id $server.Id -Force }}
}}
'''


def test_command_shapes() -> None:
    posix = posix_bootstrap("ws://127.0.0.1:4500")
    assert "trap cleanup EXIT INT TERM" in posix
    assert 'kill "$server_pid"' in posix and 'wait "$server_pid"' in posix
    powershell = powershell_bootstrap("ws://127.0.0.1:4500")
    assert "Start-Process -FilePath codex" in powershell
    assert "finally" in powershell and "Stop-Process -Id $server.Id -Force" in powershell


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def launcher_script(output: Path, endpoint: str, app_token_file: Path) -> str:
    logs = output / "logs"
    ready_endpoint = endpoint.replace("ws://", "http://", 1)
    token_path = shlex.quote(str(app_token_file))
    return f'''set -u
log_dir={shlex.quote(str(logs))}
app_token_file={token_path}
mkdir -p "$log_dir"
server_pid=""
cleanup() {{
  server_status="not-started"
  if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null; server_status="$?"; fi
  printf '%s\n' "$server_status" > "$log_dir/cleanup-status"
}}
trap cleanup EXIT INT TERM
command -v codex > "$log_dir/codex-path"
codex app-server --listen {endpoint} --ws-auth capability-token --ws-token-file "$app_token_file" >"$log_dir/app-server.stdout" 2>"$log_dir/app-server.stderr" &
server_pid="$!"; printf '%s\n' "$server_pid" > "$log_dir/app-server.pid"
ready="false"
attempt=0
while [ "$attempt" -lt 100 ]; do
  if curl --fail --silent --show-error --max-time 1 {ready_endpoint}/readyz > /dev/null 2>>"$log_dir/readyz.stderr"; then ready="true"; break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then wait "$server_pid"; exit "$?"; fi
  attempt=$((attempt + 1))
  sleep 0.1
done
printf '%s\n' "$ready" > "$log_dir/ready"
[ "$ready" = "true" ] || exit 70
# A terminal application must be foreground, not an asynchronous shell job.
# The small exec wrapper publishes its PID before replacing itself with Codex;
# the parent test terminates that PID after observing it running.
export CODEX_JETBRAINS_GATE_APP_TOKEN="$(cat "$app_token_file")"
zsh -c 'printf "%s\\n" "$$" > "$1"; shift; exec "$@"' gate-tui "$log_dir/tui.pid" codex --remote {endpoint} --remote-auth-token-env CODEX_JETBRAINS_GATE_APP_TOKEN --no-alt-screen
unset CODEX_JETBRAINS_GATE_APP_TOKEN
printf '%s\n' "supervised-complete" > "$log_dir/result"
'''


def read(path: Path) -> str | None:
    return path.read_text().strip() if path.exists() else None


def attach_controlling_terminal(slave: int) -> None:
    """Make the allocated PTY a real controlling terminal for the login shell."""
    os.setsid()
    fcntl.ioctl(slave, termios.TIOCSCTTY, 0)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("compatibility/runtime/supervised-launch-gate"))
    args = parser.parse_args()
    test_command_shapes()
    output = args.output.resolve()
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True, mode=0o700)
    scratch_home = output / "scratch-codex-home"
    scratch_home.mkdir(mode=0o700)
    app_token_file = output / "app-server.token"
    app_token_file.write_text(secrets.token_urlsafe(32), encoding="utf-8")
    app_token_file.chmod(0o600)
    script = output / "launch.zsh"
    script.write_text(launcher_script(output, f"ws://127.0.0.1:{free_port()}", app_token_file), encoding="utf-8")
    script.chmod(0o700)
    env = os.environ.copy()
    env["CODEX_HOME"] = str(scratch_home)
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 140, 0, 0))
    shell = subprocess.Popen(
        ["zsh", "-lic", str(script)],
        cwd=Path.cwd(),
        env=env,
        stdin=slave,
        stdout=slave,
        stderr=slave,
        close_fds=True,
        preexec_fn=lambda: attach_controlling_terminal(slave),
    )
    os.close(slave)
    output_bytes = bytearray()
    deadline = time.monotonic() + 30
    tui_pid: int | None = None
    tui_running_before_cleanup = False
    tui_signal_sent = False
    tui_started_at: float | None = None
    try:
        while shell.poll() is None and time.monotonic() < deadline:
            ready, _, _ = select.select([master], [], [], 0.25)
            if master in ready:
                try:
                    output_bytes.extend(os.read(master, 65536))
                except OSError:
                    pass
            if tui_pid is None:
                pid_text = read(output / "logs" / "tui.pid")
                if pid_text is not None:
                    tui_pid = int(pid_text)
                    tui_started_at = time.monotonic()
            elif not tui_signal_sent and tui_started_at is not None and time.monotonic() - tui_started_at >= 4:
                tui_running_before_cleanup = alive(tui_pid)
                if tui_running_before_cleanup:
                    os.kill(tui_pid, 15)
                tui_signal_sent = True
        if shell.poll() is None:
            shell.terminate()
            shell.wait(timeout=5)
            raise RuntimeError("shell launcher did not finish within 30 seconds")
        ready, _, _ = select.select([master], [], [], 0)
        if master in ready:
            try:
                output_bytes.extend(os.read(master, 65536))
            except OSError:
                pass
    finally:
        os.close(master)
    (output / "shell.pty").write_bytes(output_bytes)
    logs = output / "logs"
    evidence: dict[str, object] = {
        "exit_code": shell.returncode,
        "shell_codex": read(logs / "codex-path"),
        "ready": read(logs / "ready"),
        "result": read(logs / "result"),
        "tui_running_before_cleanup": tui_running_before_cleanup,
        "cleanup": (logs / "cleanup-status").read_text().splitlines() if (logs / "cleanup-status").exists() else None,
        "scratch_has_auth": (scratch_home / "auth.json").exists(),
        "app_server_token_mode": oct(app_token_file.stat().st_mode & 0o777),
    }
    for name in ("app-server", "tui"):
        pid = read(logs / f"{name}.pid")
        if pid is not None:
            evidence[f"{name}_alive_after_exit"] = alive(int(pid))
    (output / "evidence.json").write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if shell.returncode != 0:
        print(f"supervised launch gate failed; evidence: {output / 'evidence.json'}", file=sys.stderr)
        return shell.returncode or 1
    expected = {"ready": "true", "result": "supervised-complete", "tui_running_before_cleanup": True, "scratch_has_auth": False, "app_server_token_mode": "0o600", "app-server_alive_after_exit": False, "tui_alive_after_exit": False}
    if any(evidence.get(key) != value for key, value in expected.items()):
        print(f"supervised launch gate failed assertions; evidence: {output / 'evidence.json'}", file=sys.stderr)
        return 1
    print(f"supervised launch gate passed; evidence: {output / 'evidence.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
