# TaiXu Architecture Decision — Sandbox Execution Backend

**Status:** Decided — do not implement an alternative backend at this time. PRoot remains the only sandbox backend. A higher-performance backend may be reconsidered as a long-term option, but is not scheduled.

**Decision date:** 2026-09-16  
**Affected areas:** runtime/proot, runtime/shell, runtime/privilege, runtime/pty, runtime/doctor, and StorageManager.

## 1. Context

PRoot intercepts and rewrites system calls through ptrace. For syscall-heavy workloads, this can add roughly 2–10x overhead. TaiXu runs several such workloads inside the sandbox:

- Gradle/AGP Android builds.
- dpkg/apt package extraction.
- npm/pnpm dependency installation.

A native chroot backend was considered because it avoids ptrace overhead. However, chroot requires CAP_SYS_CHROOT. Mounting /proc, /dev, or bind mounts also requires CAP_SYS_ADMIN. Having a privileged-looking shell is not sufficient; the actual Linux capabilities matter.

## 2. Execution modes

- **PROOT:** runs as the Android app UID and does not have the required capabilities.
- **SHIZUKU:** normally runs as shell UID 2000. Standard ADB-level access does not provide CAP_SYS_CHROOT or CAP_SYS_ADMIN.
- **ROOT:** may have the required capabilities, depending on the device and root implementation, but cannot be assumed on every installation.

Because TaiXu's core promise is no-root operation, making chroot the default would break the standard runtime. A conditional backend would also multiply the mount, process, PTY, and troubleshooting paths that must be tested.

## 3. Decision

Keep PRoot as the only supported backend for now. Do not add a chroot/namespace backend solely to improve performance. Consider alternatives only if a future design can preserve no-root operation, isolate privilege-dependent behavior, and provide a measurable benefit that justifies the additional maintenance and device-specific testing.

## 4. Consequences

- PRoot syscall overhead remains a known performance tradeoff, especially for builds and package installation.
- Runtime behavior remains consistent across unrooted devices.
- There is one primary path for RootFS setup, host mounts, shell execution, process management, PTY, and diagnostics.
- Any future alternative requires a separate architecture decision, capability detection, security review, migration plan, and physical-device acceptance tests.
