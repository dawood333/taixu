# RTK Terminal Command Optimizer — Offline Plugin Package

This directory contains the source layout for the `rtk-suite-offline` plugin, which is packaged as a local `.txplugin` that can be imported directly into TaiXu.

## Purpose

On ARM64 (`arm64-v8a`) Android, it compresses command output and filters/rewrites it in real time (`rtk rewrite`) for commands executed by Agents. This can reduce context-token usage by 60%–90%.

- Official project: [rtk-ai/rtk](https://github.com/rtk-ai/rtk)
- Version: `v0.47.0`
- Architecture: Linux `aarch64-unknown-linux-gnu`
- License: Apache-2.0

## Package contents

```text
manifest.json
payload/
  checksums/SHA256SUMS
  scripts/install-rtk.sh
  scripts/uninstall-rtk.sh
  scripts/verify-rtk.sh
  bin/rtk
  lib/rtk-aarch64-unknown-linux-gnu.tar.gz
```

## Source URL and checksums

- Archive: `https://github.com/rtk-ai/rtk/releases/download/v0.47.0/rtk-aarch64-unknown-linux-gnu.tar.gz`
- Archive SHA-256: `960ceb5f1f5f0b0939b32b5b1d41dec6d9a7113137b0703c68dca0d169a260fc`
- Binary `bin/rtk` SHA-256: `e440fc61077925d98fdea5c6bf817df2c3c85e6b96aea5d02659c2a6f42d93ce`

## Package as `.txplugin`

Run from the project root:

```bash
python tools/package-rtk-plugin.py
```

The artifact is written to `dist/plugins/taixu-plugin-rtk-v1.0.0-arm64.txplugin`.
