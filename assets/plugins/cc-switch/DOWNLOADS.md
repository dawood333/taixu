# CC-Switch Agent Hub — Offline Local Plugin Package

This directory contains the source layout for the local `cc-switch` plugin, which is packaged as a local `.txplugin` that can be imported directly into TaiXu.

## Purpose

Runs inside the ARM64 (`arm64-v8a`) Android sandbox and centrally manages and reverse-proxies Claude Code, OpenClaw, Hermes Agent, and Codex. It provides a unified local provider switcher for multiple models, streaming protocol conversion/reverse proxying, and token accounting.

- Official projects: [farion1231/cc-switch](https://github.com/farion1231/cc-switch) and [Laliet/cc-switch-web](https://github.com/Laliet/cc-switch-web)
- Version: `v0.21.0`
- Architectures: Linux `aarch64-unknown-linux-musl` / `gnu`
- Service port: `19870`

## Package contents

```text
manifest.json
payload/
  checksums/SHA256SUMS
  scripts/install.sh
  scripts/uninstall.sh
  scripts/verify.sh
  bin/cc-switch-daemon
  lib/cc-switch-server
```

## Source URL and checksum

- Server binary: `https://github.com/Laliet/cc-switch-web/releases/download/v0.21.0/cc-switch-server-linux-aarch64`
- Binary SHA-256: `36fb9d71a370032d1545566364e775c72d984dc7c559402a55d9f77a09448450`

## Package as `.txplugin`

Run from the project root:

```bash
python tools/package-cc-switch-plugin.py
```

The artifact is written to `dist/plugins/taixu-plugin-cc-switch-v1.0.0-arm64.txplugin`.

In TaiXu, open **Plugin Center → Import Local Plugin Package** and select this file to import and install it.
