# TaiXu — Known Issues and Acceptance Checklist

This document summarizes the implementation constraints that still require verification. Treat these as engineering caveats, not as claims that every feature has passed physical-device acceptance.

## Linux runtime and RootFS

- The Linux RootFS is not bundled into the APK. First-run setup downloads the ARM64 image through the `proot-distro 5.9.0` OCI Registry flow.
- The installer selects the `linux/arm64` manifest, verifies each layer's SHA-256 digest, and merges OCI whiteouts into a staging directory before atomic activation.
- Layer caches live in app-private storage and support gzip and zstd OCI layers. The old release-tarball installation path is not supported.
- Non-ARM64 devices stop during architecture detection; the imported PRoot binary is also checked by running `--version`.
- PRoot native components should use pinned SHA-256 values. RootFS integrity is verified against each layer digest in the OCI manifest.
- RootFS updates use staging, health checks, and a two-phase commit. If activation fails or the process is interrupted, the previous RootFS should be restored. User data such as `/root` and `/opt/taixu` is persisted separately.
- Third-party tools may write into other distribution system directories. More ARM64 device testing is needed to validate migration across RootFS updates.

## Terminal and PTY

- The JNI `forkpty` backend is available with a fallback to Debian `script` + `ProcessBuilder`. The fallback supports UTF-8 output, Ctrl+C, resize, ANSI/VT100 handling, and scrollback, but complex TUI applications still need broader device testing.
- The terminal's transparent input layer can intercept LazyColumn touch scrolling. Composed input from some Chinese IMEs is not fully passed through.
- Session metadata is persisted, but process restart does not restore terminal output or the original shell process; it can recreate the session shell/metadata entry.
- Native build configuration depends on a compatible Android NDK. If native compilation is disabled or unavailable, the fallback backend should remain usable.

## Managed tools and installers

- Codex, OpenClaw, and Hermes adapters isolate install, verify, update, and uninstall paths. Upstream scripts are downloaded over HTTPS to a temporary Linux path, made owner-executable, run, and cleaned up by a trap.
- Upstream install scripts change over time. Before a stable release, pin versions and script hashes or use versioned release archives, review licenses, and validate each tool on ARM64 devices.
- Tools are installed under `/opt/taixu/tools/<toolId>`; stable command entry points live under `/opt/taixu/bin`. Per-tool configuration is stored under `/opt/taixu/data/<toolId>`. Uninstall preserves data unless the user explicitly chooses to remove it.
- `RemoteScriptRunner` blocks cross-host HTTPS redirects and supports fixed SHA-256 verification. Official script URLs still need stable versioned targets and pinned hashes before release.
- Shared Runtime reference counting and an explicit `apt purge` cleanup path are available. Tool removal does not automatically delete shared runtimes.
- Registry manifests validate schema version, ARM64/HTTPS requirements, service paths, enabled state, and version metadata. A project-owned signed Registry URL is not preconfigured; the APK manifest remains the default.
- Tool download and install scripts must validate large assets, use staging, and clean up after failures. A framework rollback only restores the tool directory it snapshots; it does not imply that every global path has been restored.

## Networking and credentials

- Provider base URLs are parsed as URIs. HTTPS is allowed for remote APIs; HTTP is limited to exact loopback addresses `localhost`, `127.0.0.1`, and `::1`. Prefix lookalikes such as `localhost.evil` are rejected.
- Model API keys use encrypted storage. Do not place credentials in source code, logs, plugin manifests, or build artifacts.
- User-supplied environment variables, including possible third-party API keys, may be passed to sandbox processes. Log output must be redacted, and scripts should avoid tracing environment values.
- OAuth/device-login URLs are detected and presented for explicit user action; ordinary links should not be opened automatically.

## Android toolchain setup

- The Android core toolchain is prepared during plugin/tool setup rather than downloaded repeatedly by each build script.
- Setup scripts should be idempotent, verify pinned digests, and install immutable toolchain artifacts under managed paths.
- The Android/Flutter build scripts should use the configured `android.ndkPath` policy and remove stale `ndk.dir` values rather than writing a second NDK locator.
- Toolchain readiness probes check actual components such as Java, AAPT, `android.jar`, and the Gradle launcher JAR. After upgrading the app, an older sandbox may need the setup step rerun; already-downloaded components should be reused.

## Testing and release acceptance

- Full physical-device acceptance of Codex, OpenClaw, and Hermes install → launch → health-check workflows remains incomplete.
- UI-level automated-test coverage is still lighter than runtime, Harness, and data-layer coverage.
- Confirm terminal interaction, install rollback, RootFS migration, and backup/restore on target ARM64 devices.
- Run unit tests, architecture checks, and a Debug APK build after source changes. A successful compile alone does not prove runtime compatibility.
