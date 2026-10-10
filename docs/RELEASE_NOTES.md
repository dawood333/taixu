# TaiXu Release Notes — v0.19.1

Release date: 2026-09-28  
Version: `0.19.1` (`appVersionCode = 28`)

> This file records the release notes currently maintained in the repository. Confirm the exact published APK and release assets before distributing them.

## Highlights

- Continued modularization of the Android app and separation of runtime, tool-management, and Agent responsibilities.
- Improved Linux runtime initialization and RootFS lifecycle handling, including staged updates, health checks, and recovery of the previous working RootFS if activation fails.
- Expanded managed tool installation with progress reporting, verification, update/uninstall transactions, shared-runtime reference counting, and cleanup of incomplete installs.
- Improved the native PTY and terminal-session lifecycle, including persistent session metadata and fallback behavior when the native backend is unavailable.
- Expanded Agent Harness model/protocol support, streaming output, tool execution, approvals, and persistent conversation state.
- Improved model-profile import/export, credential handling, and validation.
- Added and refined workspace, backup/restore, and diagnostics functionality.

## Runtime and toolchain notes

- Linux RootFS images are fetched on demand; they are not bundled into the APK.
- The runtime targets ARM64 Linux userspace under PRoot. Matching ARM64 architecture does not guarantee that Android/Bionic binaries are compatible with Linux/glibc.
- Android/Flutter toolchains inside the sandbox are managed separately from the toolchain used to build TaiXu itself.
- Installers should pin SHA-256 digests for large assets and validate downloaded content before activation.
- Plugin permissions are currently manifest metadata and UI information, not a sandbox that restricts install-script filesystem access.

## Known limitations

- More physical-device acceptance is required for full Codex, OpenClaw, and Hermes install/launch/health-check workflows.
- Complex terminal TUI behavior, touch scrolling, and some IME composition scenarios still require additional device testing.
- UI automated-test coverage remains less extensive than runtime, Harness, and data-layer coverage.
- Remote Registry configuration and third-party tool versions require continued validation before a stable release.

See [Known Issues](KNOWN_ISSUES.md), [Roadmap](ROADMAP.md), and [Security Surface](SECURITY_SURFACE.md) for current implementation caveats.
