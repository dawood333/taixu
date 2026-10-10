# Codex Tool

Codex is installed and managed as a tool inside the TaiXu Linux runtime. Installation, verification, update, and uninstall are handled by the tool adapter. Upstream installation scripts can change, so pin versions and verify source integrity before release.

Configuration and user data should remain under the tool's persistent data directory. Do not place API keys in source code, manifests, logs, or shared screenshots. Validate installation and health checks on an ARM64 device before treating the tool as release-ready.
