# TaiXu — Credentials and Security Surface

This document summarizes permission boundaries, credential ownership, and exposure points. Update it whenever the implementation changes.

> **Important:** PRoot is a compatibility layer based on ptrace syscall virtualization, not a security boundary against malicious code. Processes inside the guest effectively run with the Android app UID. The actual boundaries are Android's permission model, ApprovalPolicyEngine, and explicit user authorization. Do not describe PRoot as a secure sandbox.

## 1. Permission domains

| Domain | Effective identity | Entry point | Capabilities |
|---|---|---|---|
| App process | Android app UID | Native app | Owns app data, encrypted credential storage, Room, settings, and workspace |
| PRoot guest | Appears as root, actually app UID | PRoot virtualization, including change-id mapping | Access to the guest RootFS and explicitly mounted host paths; shares the app's network namespace |
| Shizuku | Shell UID | PrivilegeManager in SHIZUKU mode | Host shell-level commands |
| Root | Root UID | PrivilegeManager in ROOT mode via su -c | Host-level commands with root privileges |
| Built-in wireless ADB | Shell UID via localhost ADB | EmbeddedAdbManager, with HostBridge fallback | Device shell, logcat, and APK installation |

PRoot mode rejects direct host-shell execution. See runtime/privilege/PrivilegeManager.kt for mode selection and shell execution.

## 2. Credential flow

### Provider credentials

- Provider keys are encrypted and persisted by core/security/SecretManager.kt using AndroidKeyStore. After retrieval, they exist in memory and are sent with the request through ProviderTransport.
- Provider keys are not placed in the standard process environment. RuntimePathManager.hostProcessEnvironment constructs runtime path variables such as PROOT_L2S_DIR, PROOT_LOADER, TMPDIR, and LD_LIBRARY_PATH.
- Host-side child processes clear their inherited environment before adding an allowlist. This prevents the app environment from spreading into PRoot and helper processes.
- Known low-risk exception: the Root channel in PrivilegeManager and ShizukuHostUserService create processes without clearing the inherited standard Android environment. User keys are not stored in that environment. If sensitive values are ever added to the app environment, clear it in both locations first.

### Environment variables inside PRoot

- The base environment contains HOME, LANG, TMPDIR, PATH, TAIXU_BRIDGE_URL, TAIXU_BRIDGE_PORT, Android tool paths, and TERM; these are not credentials by default.
- Distro manifests, per-command environment values, provider environment values, and MCP STDIO server configuration can add variables. ProotCommandBuilder validates variable names and shell-quotes values before exporting them.
- **Values explicitly supplied by the user in MCP or environment configuration may be placed in plaintext in the sandbox process environment**, including third-party API keys. Output redaction is a mitigation, not a guarantee that secrets never enter process memory.

## 3. Logs and tool output

- Sensitive output must be redacted before it is shown or persisted. Avoid shell tracing such as set -x because it can print tokens, proxy settings, and environment values.
- Redaction is not anonymization. User prompts, ordinary file content, and other non-secret data may still appear in logs or diagnostics.
- Do not claim that a failed installation transaction restores files outside the directory it snapshots. Global paths written by plugins must be staged, validated, and cleaned up by the plugin itself.

## 4. Network policy

- Remote provider APIs must use HTTPS.
- HTTP is permitted only for exact loopback addresses where local services are expected: localhost, 127.0.0.1, and ::1.
- URL parsing must reject prefix lookalikes such as localhost.evil.
- Downloads should reject insecure schemes and unsafe cross-host redirects, validate response ranges and size limits, and verify pinned SHA-256 digests before activation.

## 5. Plugin and runtime trust

- Plugin permissions in a manifest are currently validation/display metadata, not an OS-enforced filesystem sandbox.
- Local plugins can run scripts with the current Linux user's permissions inside PRoot. Do not import or install untrusted plugins without review.
- Pin versions and hashes for upstream install scripts or use versioned release archives. Upstream scripts can change independently of TaiXu.
- PRoot is not a VM and does not provide an independent kernel, user namespace, or security boundary comparable to a hardened container/VM.
- RootFS updates should use staging, health checks, and rollback. Persist user data outside the replaceable RootFS.

## 6. User-facing wording

Prefer “Linux runtime,” “PRoot environment,” or “app-isolated userspace.” Avoid claims that PRoot prevents malicious code from accessing all host resources. Clearly describe when an operation needs Shizuku, root, or explicit approval.
