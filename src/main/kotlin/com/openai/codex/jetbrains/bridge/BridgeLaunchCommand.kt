package com.openai.codex.jetbrains.bridge

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path

internal data class BridgeLaunchSpec(
    val relayEndpoint: String,
    val appServerEndpoint: String,
    val relayTokenFile: Path,
    val appServerTokenFile: Path,
    val relayFailureMarker: Path,
    val stateDirectory: Path,
)

/**
 * Produces a command for the user's configured Terminal shell. The command
 * capability-probes the installed CLI and runs literal `codex` on any failure.
 * Tokens are read from private files, never interpolated into the command.
 */
internal object BridgeLaunchCommand {
    fun create(spec: BridgeLaunchSpec, windows: Boolean = SystemInfo.isWindows): String =
        if (windows) powerShell(spec) else posix(spec)

    internal fun posix(spec: BridgeLaunchSpec): String {
        val relay = quote(spec.relayEndpoint)
        val appServer = quote(spec.appServerEndpoint)
        val relayToken = quote(spec.relayTokenFile.toString())
        val appToken = quote(spec.appServerTokenFile.toString())
        val relayFailure = quote(spec.relayFailureMarker.toString())
        val state = quote(spec.stateDirectory.toString())
        val ready = quote(spec.appServerEndpoint.replaceFirst("ws://", "http://") + "/readyz")
        return """set -u
bridge_dir=$state
server_pid=""
cleanup() {
  if [ -n "${'$'}server_pid" ]; then kill "${'$'}server_pid" 2>/dev/null || true; wait "${'$'}server_pid" 2>/dev/null || true; fi
  rm -f $relayToken $appToken 2>/dev/null || true
  rm -f "${'$'}bridge_dir/app-server.log" 2>/dev/null || true
  rmdir "${'$'}bridge_dir" 2>/dev/null || true
}
fallback() {
  printf '%s\n' 'Codex native edit approvals are unavailable for this CLI; continuing with normal Codex.'
  cleanup
  trap - EXIT INT TERM HUP
  exec codex
}
trap cleanup EXIT INT TERM HUP
if ! codex app-server --help 2>/dev/null | grep -q -- '--listen' || \\
   ! codex app-server --help 2>/dev/null | grep -q -- '--ws-auth' || \\
   ! codex app-server --help 2>/dev/null | grep -q -- '--ws-token-file' || \\
   ! codex --help 2>/dev/null | grep -q -- '--remote-auth-token-env'; then
  fallback
fi
codex app-server --listen $appServer --ws-auth capability-token --ws-token-file $appToken \\
  >"${'$'}bridge_dir/app-server.log" 2>&1 &
server_pid="${'$'}!"
ready=false
attempt=0
while [ "${'$'}attempt" -lt 100 ]; do
  if curl --fail --silent --show-error --max-time 1 $ready >/dev/null 2>/dev/null; then ready=true; break; fi
  if ! kill -0 "${'$'}server_pid" 2>/dev/null; then fallback; fi
  attempt=$((attempt + 1))
  sleep 0.1
done
[ "${'$'}ready" = true ] || fallback
export CODEX_JETBRAINS_RELAY_TOKEN="$(cat $relayToken)"
codex --remote $relay --remote-auth-token-env CODEX_JETBRAINS_RELAY_TOKEN
status="${'$'}?"
unset CODEX_JETBRAINS_RELAY_TOKEN
if [ -f $relayFailure ]; then fallback; fi
exit "${'$'}status"""
    }

    internal fun powerShell(spec: BridgeLaunchSpec): String {
        fun ps(value: String) = "'${value.replace("'", "''")}'"
        val relay = ps(spec.relayEndpoint)
        val appServer = ps(spec.appServerEndpoint)
        val relayToken = ps(spec.relayTokenFile.toString())
        val appToken = ps(spec.appServerTokenFile.toString())
        val relayFailure = ps(spec.relayFailureMarker.toString())
        val state = ps(spec.stateDirectory.toString())
        val ready = ps(spec.appServerEndpoint.replaceFirst("ws://", "http://") + "/readyz")
        return """${'$'}ErrorActionPreference = 'Stop'
${'$'}bridgeDir = $state; ${'$'}server = ${'$'}null
function Fallback {
  Write-Host 'Codex native edit approvals are unavailable for this CLI; continuing with normal Codex.'
  & codex
  exit ${'$'}LASTEXITCODE
}
try {
  if (-not ((codex app-server --help) -match '--listen') -or
      -not ((codex app-server --help) -match '--ws-auth') -or
      -not ((codex app-server --help) -match '--ws-token-file') -or
      -not ((codex --help) -match '--remote-auth-token-env')) { Fallback }
  ${'$'}server = Start-Process -FilePath codex -ArgumentList @('app-server', '--listen', $appServer, '--ws-auth', 'capability-token', '--ws-token-file', $appToken) -PassThru -RedirectStandardOutput "${'$'}bridgeDir\\app-server.log" -RedirectStandardError "${'$'}bridgeDir\\app-server.err"
  ${'$'}ready = ${'$'}false
  1..100 | ForEach-Object {
    if (-not ${'$'}ready) { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 1 $ready | Out-Null; ${'$'}ready = ${'$'}true } catch { Start-Sleep -Milliseconds 100 } }
  }
  if (-not ${'$'}ready -or ${'$'}server.HasExited) { Fallback }
  ${'$'}env:CODEX_JETBRAINS_RELAY_TOKEN = Get-Content -LiteralPath $relayToken -Raw
  & codex --remote $relay --remote-auth-token-env CODEX_JETBRAINS_RELAY_TOKEN
  ${'$'}remoteStatus = ${'$'}LASTEXITCODE
  if (Test-Path -LiteralPath $relayFailure) { Fallback }
  exit ${'$'}remoteStatus
} finally {
  Remove-Item Env:CODEX_JETBRAINS_RELAY_TOKEN -ErrorAction SilentlyContinue
  if (${'$'}server -and -not ${'$'}server.HasExited) { Stop-Process -Id ${'$'}server.Id -Force; ${'$'}server.WaitForExit() }
  Remove-Item -LiteralPath $relayToken, $appToken -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath ${'$'}bridgeDir -Recurse -Force -ErrorAction SilentlyContinue
}"""
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"
}
