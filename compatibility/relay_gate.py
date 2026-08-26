#!/usr/bin/env python3
"""Disposable end-to-end approval-relay compatibility gate for Codex CLI.

This is intentionally not product code.  It runs a local `codex app-server`,
places a minimal authenticated WebSocket relay in front of it, and drives one
remote TUI through two file-change approvals: decline followed by accept.

The script retains only correlation/status evidence and the terminal transcript
under compatibility/runtime/.  It never logs capability tokens or diffs and it
never writes either proposed result itself.
"""

from __future__ import annotations

import argparse
import base64
import fcntl
import hashlib
import json
import os
import pty
import re
import secrets
import select
import shutil
import socket
import struct
import subprocess
import sys
import termios
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable


REQUEST_APPROVAL = "item/fileChange/requestApproval"
ITEM_STARTED = "item/started"
ITEM_COMPLETED = "item/completed"


def websocket_accept(key: str) -> str:
    source = (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
    return base64.b64encode(hashlib.sha1(source).digest()).decode()


def encode_frame(payload: bytes, *, opcode: int = 1, mask: bool = False) -> bytes:
    header = bytearray([0x80 | opcode])
    mask_bit = 0x80 if mask else 0
    length = len(payload)
    if length < 126:
        header.append(mask_bit | length)
    elif length <= 0xFFFF:
        header.append(mask_bit | 126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(mask_bit | 127)
        header.extend(struct.pack("!Q", length))
    if not mask:
        return bytes(header) + payload
    key = secrets.token_bytes(4)
    masked = bytes(value ^ key[index % 4] for index, value in enumerate(payload))
    return bytes(header) + key + masked


def decode_frames(buffer: bytearray) -> Iterable[tuple[int, bytes]]:
    while len(buffer) >= 2:
        first, second = buffer[0], buffer[1]
        if not first & 0x80:
            raise RuntimeError("fragmented WebSocket frames are unsupported by the gate")
        opcode = first & 0x0F
        masked = bool(second & 0x80)
        length = second & 0x7F
        offset = 2
        if length == 126:
            if len(buffer) < offset + 2:
                return
            length = struct.unpack("!H", buffer[offset : offset + 2])[0]
            offset += 2
        elif length == 127:
            if len(buffer) < offset + 8:
                return
            length = struct.unpack("!Q", buffer[offset : offset + 8])[0]
            offset += 8
        if masked:
            if len(buffer) < offset + 4:
                return
            mask_key = bytes(buffer[offset : offset + 4])
            offset += 4
        else:
            mask_key = None
        if len(buffer) < offset + length:
            return
        payload = bytes(buffer[offset : offset + length])
        del buffer[: offset + length]
        if mask_key:
            payload = bytes(value ^ mask_key[index % 4] for index, value in enumerate(payload))
        yield opcode, payload


def receive_headers(connection: socket.socket) -> tuple[str, dict[str, str], bytes]:
    data = bytearray()
    while b"\r\n\r\n" not in data:
        chunk = connection.recv(4096)
        if not chunk:
            raise RuntimeError("closed during WebSocket handshake")
        data.extend(chunk)
        if len(data) > 32_768:
            raise RuntimeError("oversized WebSocket handshake")
    raw_headers, trailing = bytes(data).split(b"\r\n\r\n", 1)
    lines = raw_headers.decode("iso-8859-1").split("\r\n")
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.lower()] = value.strip()
    return lines[0], headers, trailing


def accept_client(connection: socket.socket, token: str) -> bytes:
    request_line, headers, trailing = receive_headers(connection)
    if not request_line.startswith("GET "):
        raise RuntimeError(f"remote TUI did not initiate a WebSocket request: {request_line!r}")
    if headers.get("authorization") != f"Bearer {token}":
        raise RuntimeError("remote TUI did not present the relay capability token")
    key = headers.get("sec-websocket-key")
    if not key or headers.get("upgrade", "").lower() != "websocket":
        raise RuntimeError("invalid remote TUI WebSocket handshake")
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {websocket_accept(key)}\r\n\r\n"
    )
    connection.sendall(response.encode("ascii"))
    return trailing


def connect_upstream(port: int, token: str) -> tuple[socket.socket, bytes]:
    deadline = time.monotonic() + 10
    while True:
        try:
            connection = socket.create_connection(("127.0.0.1", port), timeout=1)
            break
        except OSError as error:
            if time.monotonic() >= deadline:
                raise RuntimeError("app-server did not become ready for the relay") from error
            time.sleep(0.1)
    key = base64.b64encode(secrets.token_bytes(16)).decode()
    request = (
        "GET / HTTP/1.1\r\n"
        f"Host: 127.0.0.1:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"Authorization: Bearer {token}\r\n\r\n"
    )
    connection.sendall(request.encode("ascii"))
    status_line, headers, trailing = receive_headers(connection)
    if not status_line.startswith("HTTP/1.1 101 "):
        raise RuntimeError(f"app-server rejected the relay WebSocket handshake: {status_line!r}")
    if headers.get("sec-websocket-accept") != websocket_accept(key):
        raise RuntimeError("app-server did not complete the WebSocket handshake")
    return connection, trailing


@dataclass
class Evidence:
    cli_version: str
    items: dict[str, dict[str, object]] = field(default_factory=dict)
    reject_request_id: object | None = None
    apply_request_id: object | None = None
    reject_thread_id: object | None = None
    apply_thread_id: object | None = None
    reject_completed: bool = False
    apply_completed: bool = False
    apply_preimage_unchanged_at_accept: bool = False
    apply_written_after_completion: bool = False
    terminal_mentions_decline: bool = False
    terminal_mentions_rejected_patch: bool = False
    reject_preimage_unchanged_after_decline: bool = False
    remote_initialized: bool = False
    initial_thread_started: bool = False
    user_turn_started: bool = False
    enter_sequence: str = "cr"
    apps_disabled_for_harness: bool = False
    client_methods: list[str] = field(default_factory=list)

    def to_json(self) -> str:
        return json.dumps(
            {
                "cli_version": self.cli_version,
                "reject_request_id": self.reject_request_id,
                "apply_request_id": self.apply_request_id,
                "same_thread_after_decline": self.reject_thread_id == self.apply_thread_id,
                "reject_completed": self.reject_completed,
                "apply_completed": self.apply_completed,
                "apply_preimage_unchanged_at_accept": self.apply_preimage_unchanged_at_accept,
                "apply_written_after_completion": self.apply_written_after_completion,
                "terminal_mentions_decline": self.terminal_mentions_decline,
                "terminal_mentions_rejected_patch": self.terminal_mentions_rejected_patch,
                "reject_preimage_unchanged_after_decline": self.reject_preimage_unchanged_after_decline,
                "remote_initialized": self.remote_initialized,
                "initial_thread_started": self.initial_thread_started,
                "user_turn_started": self.user_turn_started,
                "enter_sequence": self.enter_sequence,
                "apps_disabled_for_harness": self.apps_disabled_for_harness,
                "client_methods": self.client_methods,
                "item_count": len(self.items),
            },
            indent=2,
            sort_keys=True,
        )


class RelayGate:
    def __init__(
        self,
        worktree: Path,
        output: Path,
        enter_sequence: str,
        disable_apps: bool,
        timeout_seconds: int,
        mode: str,
    ) -> None:
        self.worktree = worktree
        self.output = output
        self.project = output / "project"
        self.terminal_path = output / "terminal.txt"
        self.evidence_path = output / "evidence.json"
        self.token = secrets.token_urlsafe(32)
        self.app_server_token = secrets.token_urlsafe(32)
        self.evidence = Evidence(
            cli_version=self.codex_version(),
            enter_sequence=enter_sequence,
            apps_disabled_for_harness=disable_apps,
        )
        self.client_buffer = bytearray()
        self.server_buffer = bytearray()
        self.phase = "awaiting-reject" if mode in ("reject", "both") else "awaiting-apply"
        self.reject_item_id: str | None = None
        self.apply_item_id: str | None = None
        self.apply_file = self.project / "gate-apply.txt"
        self.reject_file = self.project / "gate-reject.txt"
        self.codex_home = self.output / "codex-home"
        self.initialize_request_id: object | None = None
        self.remote_ready_at: float | None = None
        self.thread_start_request_id: object | None = None
        self.thread_ready_at: float | None = None
        self.thread_start_count = 0
        self.enter_bytes = {
            "cr": b"\r",
            "csi-u": b"\x1b[13u",
            "csi-u-modifier": b"\x1b[13;1u",
        }[enter_sequence]
        self.disable_apps = disable_apps
        self.timeout_seconds = timeout_seconds
        self.mode = mode
        self.initial_prompt = (
            "Change gate-reject.txt from before-reject to after-reject using apply_patch. "
            "Do not use a shell command. This is a deliberate edit requiring approval."
            if mode in ("reject", "both")
            else "Change gate-apply.txt from before-apply to after-apply using apply_patch. "
            "Do not use a shell command. This is a deliberate edit requiring approval."
        )

    @staticmethod
    def codex_version() -> str:
        return subprocess.check_output(["codex", "--version"], text=True).strip()

    def run(self) -> None:
        self.output.mkdir(parents=True, exist_ok=True)
        self.project.mkdir(parents=True, exist_ok=True)
        self.reject_file.write_text("before-reject\n", encoding="utf-8")
        self.apply_file.write_text("before-apply\n", encoding="utf-8")
        env = self.isolated_codex_environment()
        server_listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_listener.bind(("127.0.0.1", 0))
        server_listener.listen(1)
        relay_port = server_listener.getsockname()[1]
        app_port = self.free_port()
        app_token_file = self.output / "app-server.token"
        app_token_file.write_text(self.app_server_token, encoding="utf-8")
        os.chmod(app_token_file, 0o600)
        app_server = subprocess.Popen(
            self.codex_command(
                "app-server",
                "--listen",
                f"ws://127.0.0.1:{app_port}",
                "--ws-auth",
                "capability-token",
                "--ws-token-file",
                str(app_token_file),
            ),
            cwd=self.project,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        master_fd, slave_fd = pty.openpty()
        fcntl.ioctl(slave_fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 140, 0, 0))
        env["CODEX_JETBRAINS_RELAY_TOKEN"] = self.token
        tui = subprocess.Popen(
            self.codex_command(
                "--remote",
                f"ws://127.0.0.1:{relay_port}",
                "--remote-auth-token-env",
                "CODEX_JETBRAINS_RELAY_TOKEN",
                "--no-alt-screen",
                "--sandbox",
                "read-only",
                self.initial_prompt,
            ),
            cwd=self.project,
            env=env,
            stdin=slave_fd,
            stdout=slave_fd,
            stderr=slave_fd,
            close_fds=True,
        )
        os.close(slave_fd)
        client: socket.socket | None = None
        upstream: socket.socket | None = None
        deadline = time.monotonic() + self.timeout_seconds
        reject_prompt_sent = True
        apply_prompt_sent = False
        prompt_sent_at = time.monotonic()
        terminal_bytes = bytearray()
        try:
            while time.monotonic() < deadline:
                if client is None:
                    ready, _, _ = select.select([server_listener, master_fd], [], [], 0.25)
                    if server_listener in ready:
                        client, _ = server_listener.accept()
                        client_trailing = accept_client(client, self.token)
                        upstream, upstream_trailing = connect_upstream(app_port, self.app_server_token)
                        self.client_buffer.extend(client_trailing)
                        self.server_buffer.extend(upstream_trailing)
                        # Local relay sockets stay blocking for the short-lived gate.  This avoids
                        # treating a transient kernel back-pressure signal as a protocol failure.
                    if master_fd in ready:
                        terminal_bytes.extend(self.read_terminal(master_fd))
                    continue

                if self.mode == "both" and self.phase == "awaiting-apply-prompt" and not apply_prompt_sent:
                    if time.monotonic() - prompt_sent_at > 2:
                        self.write_terminal(
                            master_fd,
                            "Change gate-apply.txt from before-apply to after-apply using apply_patch. "
                            "Do not use a shell command. This is a deliberate edit requiring approval.",
                            self.enter_bytes,
                        )
                        apply_prompt_sent = True
                        self.phase = "awaiting-apply"

                assert client is not None and upstream is not None
                watched = [client, upstream, master_fd]
                ready, _, _ = select.select(watched, [], [], 0.25)
                if master_fd in ready:
                    terminal_bytes.extend(self.read_terminal(master_fd))
                if client in ready:
                    data = client.recv(65536)
                    if not data:
                        raise RuntimeError("remote TUI disconnected before gate completion")
                    self.client_buffer.extend(data)
                    for opcode, payload in decode_frames(self.client_buffer):
                        self.forward_client_frame(upstream, client, opcode, payload)
                if upstream in ready:
                    data = upstream.recv(65536)
                    if not data:
                        raise RuntimeError("app-server disconnected before gate completion")
                    self.server_buffer.extend(data)
                    for opcode, payload in decode_frames(self.server_buffer):
                        self.handle_server_frame(upstream, client, opcode, payload)
                if self.phase == "complete":
                    # Preserve a short post-completion interval for the same remote TUI to render.
                    time.sleep(3)
                    terminal_bytes.extend(self.drain_terminal(master_fd))
                    break
            else:
                raise RuntimeError(f"timed out during phase {self.phase}")
        finally:
            self.terminal_path.write_bytes(terminal_bytes)
            terminal = self.normalized_terminal(terminal_bytes)
            self.evidence.terminal_mentions_decline = "declin" in terminal.lower()
            self.evidence.terminal_mentions_rejected_patch = (
                "Edited gate-reject.txt (+1 -1)" in terminal
                and "-before-reject" in terminal
                and "+after-reject" in terminal
            )
            self.evidence_path.write_text(self.evidence.to_json() + "\n", encoding="utf-8")
            for connection in (client, upstream, server_listener):
                if connection is not None:
                    connection.close()
            for process in (tui, app_server):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
            os.close(master_fd)
            shutil.rmtree(self.codex_home, ignore_errors=True)
        self.assert_success()

    def isolated_codex_environment(self) -> dict[str, str]:
        source_home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
        source_auth = source_home / "auth.json"
        if not source_auth.is_file():
            raise RuntimeError("an authenticated Codex home is required for the real approval gate")
        self.codex_home.mkdir(mode=0o700, exist_ok=True)
        shutil.copy2(source_auth, self.codex_home / "auth.json")
        os.chmod(self.codex_home, 0o700)
        env = os.environ.copy()
        env["CODEX_HOME"] = str(self.codex_home)
        return env

    def codex_command(self, *arguments: str) -> list[str]:
        return ["codex", *( ["--disable", "apps"] if self.disable_apps else [] ), *arguments]

    @staticmethod
    def free_port() -> int:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("127.0.0.1", 0))
            return probe.getsockname()[1]

    @staticmethod
    def read_terminal(master_fd: int) -> bytes:
        try:
            return os.read(master_fd, 65536)
        except OSError:
            return b""

    @staticmethod
    def drain_terminal(master_fd: int) -> bytes:
        captured = bytearray()
        while True:
            ready, _, _ = select.select([master_fd], [], [], 0)
            if master_fd not in ready:
                return bytes(captured)
            captured.extend(RelayGate.read_terminal(master_fd))

    @staticmethod
    def write_terminal(master_fd: int, text: str, enter: bytes) -> None:
        os.write(master_fd, text.encode("utf-8") + enter)

    @staticmethod
    def normalized_terminal(raw: bytes) -> str:
        text = raw.decode("utf-8", errors="replace").replace("\r", "\n")
        return re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)

    def forward_client_frame(
        self,
        upstream: socket.socket,
        client: socket.socket,
        opcode: int,
        payload: bytes,
    ) -> None:
        if opcode == 9:
            client.sendall(encode_frame(payload, opcode=10))
        elif opcode == 8:
            upstream.sendall(encode_frame(payload, opcode=8, mask=True))
        else:
            if opcode == 1:
                self.record_client_message(payload)
            upstream.sendall(encode_frame(payload, opcode=opcode, mask=True))

    def handle_server_frame(
        self,
        upstream: socket.socket,
        client: socket.socket,
        opcode: int,
        payload: bytes,
    ) -> None:
        if opcode == 9:
            upstream.sendall(encode_frame(payload, opcode=10, mask=True))
            return
        if opcode == 8:
            client.sendall(encode_frame(payload, opcode=8))
            return
        if opcode != 1:
            client.sendall(encode_frame(payload, opcode=opcode))
            return
        try:
            message = json.loads(payload)
        except json.JSONDecodeError:
            client.sendall(encode_frame(payload))
            return
        if self.initialize_request_id is not None and message.get("id") == self.initialize_request_id and "result" in message:
            self.evidence.remote_initialized = True
            self.remote_ready_at = time.monotonic()
        if self.thread_start_request_id is not None and message.get("id") == self.thread_start_request_id and "result" in message:
            self.evidence.initial_thread_started = True
            self.thread_ready_at = time.monotonic()
        if message.get("method") == ITEM_STARTED:
            self.record_item(message.get("params", {}))
        if message.get("method") == REQUEST_APPROVAL and "id" in message:
            self.answer_approval(upstream, message)
            return
        if message.get("method") == ITEM_COMPLETED:
            self.record_completion(message.get("params", {}))
        client.sendall(encode_frame(payload))

    def record_client_message(self, payload: bytes) -> None:
        try:
            message = json.loads(payload)
        except json.JSONDecodeError:
            return
        method = message.get("method")
        if not isinstance(method, str):
            return
        if method not in self.evidence.client_methods:
            self.evidence.client_methods.append(method)
        if method == "turn/start":
            self.evidence.user_turn_started = True
        if method == "initialize" and "id" in message:
            self.initialize_request_id = message["id"]
        if method == "thread/start" and "id" in message:
            self.thread_start_count += 1
            if self.thread_start_request_id is None:
                self.thread_start_request_id = message["id"]

    def record_item(self, params: object) -> None:
        if not isinstance(params, dict):
            return
        item = params.get("item")
        if not isinstance(item, dict) or item.get("type") != "fileChange":
            return
        item_id = item.get("id")
        if not isinstance(item_id, str):
            return
        self.evidence.items[item_id] = {
            "threadId": params.get("threadId"),
            "turnId": params.get("turnId"),
            "change_count": len(item.get("changes", [])) if isinstance(item.get("changes"), list) else -1,
        }

    def answer_approval(self, upstream: socket.socket, message: dict[str, object]) -> None:
        params = message.get("params")
        if not isinstance(params, dict):
            raise RuntimeError("malformed file-change approval params")
        item_id = params.get("itemId")
        if not isinstance(item_id, str) or item_id not in self.evidence.items:
            raise RuntimeError("approval was not correlated to a preceding fileChange item")
        item = self.evidence.items[item_id]
        if item["threadId"] != params.get("threadId") or item["turnId"] != params.get("turnId"):
            raise RuntimeError("approval correlation thread or turn mismatch")
        if self.phase == "awaiting-reject":
            decision = "decline"
            self.reject_item_id = item_id
            self.evidence.reject_request_id = message["id"]
            self.evidence.reject_thread_id = params.get("threadId")
            self.phase = "awaiting-reject-completion"
        elif self.phase == "awaiting-apply":
            decision = "accept"
            self.apply_item_id = item_id
            self.evidence.apply_request_id = message["id"]
            self.evidence.apply_thread_id = params.get("threadId")
            self.evidence.apply_preimage_unchanged_at_accept = self.apply_file.read_text(encoding="utf-8") == "before-apply\n"
            self.phase = "awaiting-apply-completion"
        else:
            raise RuntimeError(f"unexpected additional file-change approval in phase {self.phase}")
        response = json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": {"decision": decision}})
        upstream.sendall(encode_frame(response.encode("utf-8"), mask=True))

    def record_completion(self, params: object) -> None:
        if not isinstance(params, dict):
            return
        item = params.get("item")
        if not isinstance(item, dict):
            return
        item_id = item.get("id")
        if item_id == self.reject_item_id and item.get("status") == "declined":
            self.evidence.reject_completed = True
            self.evidence.reject_preimage_unchanged_after_decline = (
                self.reject_file.read_text(encoding="utf-8") == "before-reject\n"
            )
            if self.mode == "reject":
                self.phase = "complete"
            elif self.mode == "both":
                self.phase = "awaiting-apply-prompt"
        if item_id == self.apply_item_id and item.get("status") == "completed":
            self.evidence.apply_completed = True
            self.evidence.apply_written_after_completion = self.apply_file.read_text(encoding="utf-8") == "after-apply\n"
            if self.mode == "accept" or (self.evidence.reject_completed and self.evidence.apply_written_after_completion):
                self.phase = "complete"

    def assert_success(self) -> None:
        failures = []
        if self.mode in ("reject", "both") and not self.evidence.reject_completed:
            failures.append("the declined file-change item did not complete as declined")
        if self.mode in ("reject", "both") and not self.evidence.reject_preimage_unchanged_after_decline:
            failures.append("the rejected proposal changed the file before or after decline")
        if self.mode in ("accept", "both") and not self.evidence.apply_completed:
            failures.append("the accepted file-change item did not complete")
        if self.mode in ("accept", "both") and not self.evidence.apply_preimage_unchanged_at_accept:
            failures.append("the apply proposal was already written before the relay sent accept")
        if self.mode in ("accept", "both") and not self.evidence.apply_written_after_completion:
            failures.append("Codex did not write the accepted proposal after completion")
        if self.mode == "both" and self.evidence.reject_thread_id != self.evidence.apply_thread_id:
            failures.append("the follow-up approval was not on the same thread")
        if self.mode in ("reject", "both") and not self.evidence.terminal_mentions_rejected_patch:
            failures.append("the same remote TUI transcript did not show the rejected patch")
        if not self.evidence.user_turn_started:
            failures.append("the remote TUI did not issue turn/start for the supplied user prompt")
        if failures:
            raise RuntimeError("; ".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("compatibility/runtime/relay-gate"),
        help="ignored runtime evidence directory",
    )
    parser.add_argument(
        "--disable-apps",
        action="store_true",
        help="harness-only: avoid a host-injected codex_apps MCP startup while testing input encoding",
    )
    parser.add_argument(
        "--timeout-seconds",
        type=int,
        default=180,
        help="bounded harness timeout; production behavior is not inferred from this value",
    )
    parser.add_argument(
        "--mode",
        choices=("reject", "accept", "both"),
        default="both",
        help="record one independent native-decision path, or both when harness keyboard input is available",
    )
    parser.add_argument(
        "--enter-sequence",
        choices=("cr", "csi-u", "csi-u-modifier"),
        default="cr",
        help="harness-only terminal Enter encoding for a TUI that enabled Kitty keyboard protocol",
    )
    args = parser.parse_args()
    try:
        RelayGate(
            Path.cwd(),
            args.output.resolve(),
            args.enter_sequence,
            args.disable_apps,
            args.timeout_seconds,
            args.mode,
        ).run()
    except Exception as error:  # noqa: BLE001 - evidence is written in finally when possible.
        print(f"relay gate failed: {error}", file=sys.stderr)
        return 1
    print(f"relay gate passed; evidence: {args.output.resolve() / 'evidence.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
