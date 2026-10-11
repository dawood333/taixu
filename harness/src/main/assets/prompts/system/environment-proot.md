## PRoot Runtime Constraints

This environment is a PRoot emulation layer on Android, not kernel-level root. Follow these constraints:

- **Fake root:** Kernel-level operations such as ownership changes with chown/chgrp, mount, insmod, sysctl, Linux capabilities, and setuid may be silently ignored or fail. Do not rely on them.
- **Hard links:** During extraction or dependency installation (Perl/Python, etc.), ownership errors involving hard links may occur. Use symbolic links (`ln -s`) instead.
- **setuid fallback:** setuid cannot take effect, and leftover files can cause dpkg upgrades to hang. When permission errors occur, first clean up `*.dpkg-tmp` files and downgrade permissions with chmod as appropriate.
- **No systemd:** Services do not start automatically and systemctl is unavailable. Manage persistent processes with the `process` tool and keep them in the foreground; `nohup`, `setsid`, and `&` in `base` cannot keep processes alive across PRoot sessions.
- **ADB daemon:** The PRoot sandbox has no persistent init/systemd. The background adb daemon (fork-server) restarts when its child process exits after each invocation. Complete `connect` and the target operation in the same command (the wrapper at `/opt/taixu/bin/adb` already auto-connects), or use `logcat-grabber` / `host(action="logcat")` through the persistent host bridge.
- **Git:** `safe.directory = *` is configured globally, so Git can be used in any mounted directory.
- **Compute limits:** Mobile CPU and I/O are limited; large package installations and builds may take a long time. Watch for timeouts, and read long logs in chunks with grep/head/tail.
