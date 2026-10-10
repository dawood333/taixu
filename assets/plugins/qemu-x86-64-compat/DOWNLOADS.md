# QEMU x86_64 Compatibility Environment — Offline Plugin Package

This directory contains the source layout for the `qemu-x86-64-compat` plugin, packaged as a local `.txplugin` for direct import.

## Purpose

On ARM64 (`arm64-v8a`) Android, PRoot and the QEMU user-mode emulator (`qemu-x86_64`) provide an isolated x86_64 Linux build environment for third-party compatibility builds (APK compilation only).

- Host QEMU binary: `usr/bin/qemu-x86_64-static` (ARM64 ELF)
- Isolated rootfs: `ubuntu-base-24.04.3-base-amd64` (x86_64)
- JDK, Gradle, Android SDK, and Flutter are not included; those toolchains are supplied by other plugins.

## Package contents

```text
manifest.json
payload/
  checksums/SHA256SUMS
  scripts/install-qemu-x86-64-compat.sh
  scripts/uninstall-qemu-x86-64-compat.sh
  scripts/verify-qemu-x86-64-compat.sh
  archives/<2 offline archives>
```

## Archive manifest (SHA-256)

| Filename | Size | SHA-256 |
|---|---:|---|
| `qemu-x86_64-static.tar.gz` | See manifest | See `payload/checksums/SHA256SUMS` |
| `ubuntu-base-24.04.3-base-amd64.tar.gz` | See manifest | See `payload/checksums/SHA256SUMS` |

## Source URLs

See the archive URLs recorded in the plugin manifest and packaging configuration.

## Verification

The `payload/checksums/SHA256SUMS` file is verified with `sha256sum -c checksums/SHA256SUMS` from the `payload/` directory (entries use paths relative to `payload/`, such as `archives/<filename>`).

## Package as `.txplugin`

There is currently no dedicated packaging script for this plugin in the repository. Do not assume a packager exists; add and validate one before automating package creation.

The package must contain only:
- Root `manifest.json`
- Root `payload/` directory, including `scripts/`, `checksums/`, and `archives/`

## Notes

- All scripts use POSIX `sh`. Installation does not access the network; it extracts local archives and verifies their SHA-256 checksums.
- The installation target is fixed at `/opt/taixu/compat/x86_64` (host `taixuRoot/compat/x86_64`), matching `QemuCompatibilityLayout` in the `runtime` module.
- Command links are written only to `$TAIXU_TOOL_DIR/bin` and `/opt/taixu/bin`: `qemu-x86_64`.
