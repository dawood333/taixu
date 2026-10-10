# OpenClaw Tool

OpenClaw is managed by the TaiXu tool center. Its adapter isolates installation, verification, updates, and uninstall. Upstream scripts may change independently, so use versioned sources and verify script or archive integrity before release.

Keep configuration and user data separate from executable files, under the tool's persistent data directory. Do not expose API keys or tokens in source, logs, manifests, or screenshots. Validate the complete install → launch → health-check flow on an ARM64 device.
