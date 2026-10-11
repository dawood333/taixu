#!/bin/sh
# ==============================================================================
# TaiXu (LinuxAIRuntime) - Node.js Package Managers (pnpm / yarn) Setup
# ==============================================================================
set -e

echo "==> [TaiXu] Configuring modern package managers (pnpm / yarn)..."

npm install -g pnpm yarn --registry=https://registry.npmmirror.com 2>/dev/null || \
npm install -g pnpm yarn 2>/dev/null || true

echo "==> [TaiXu] ✅ pnpm and yarn configuration completed!"