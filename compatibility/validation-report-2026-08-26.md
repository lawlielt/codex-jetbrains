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

## Build and package

The final command was:

```bash
./gradlew -Dorg.gradle.java.home=/Library/Java/JavaVirtualMachines/jdk-21.0.2.jdk/Contents/Home \
  test buildPlugin verifyPlugin
```

It passed on JDK 21.0.2. The test report contains **45 tests** across **11 test
suites**. Plugin Verifier 1.409 reported **Compatible** against
`IC-242.26775.15`; the HTML report is
`build/reports/pluginVerifier/IC-242.26775.15/report.html`.

| Field | Value |
| --- | --- |
| Artifact | `build/distributions/codex-jetbrains-0.4.0.zip` |
| Plugin ID | `io.github.lawlielt.codex.jetbrains` |
| Name | `Codex CLI Companion` |
| Version | `0.4.0` |
| SHA-256 | `b032ba410d721bcb62f709790f0f49757c74d0b4255f6221a52b2f67d5fb3f2a` |
