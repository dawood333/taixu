# QEMU x86_64 Compatibility Plugin Resources

This directory defines the plugin contract and resource manifest. It does not present an incomplete QEMU file as a working environment. An offline plugin must contain:

```text
payload/
  compat/x86_64/qemu-x86_64       # ARM64 ELF, executable natively on the host
  compat/x86_64/rootfs/bin/sh     # x86_64 ELF
  compat/x86_64/rootfs/lib64/ld-linux-x86-64.so.2
  compat/x86_64/rootfs/usr/...    # User-space libraries such as glibc, libstdc++, and zlib
```

Recommended base package for the x86_64 RootFS (this is the emulated user space, not an Android APK ABI):

- URL: https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-amd64.tar.gz
- SHA-256: `6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9`

Obtain the ARM64 QEMU binary from the project's ARM64 build artifacts and verify ELF machine=183 during installation. Do not include an x86 or x86_64 QEMU executable; it cannot start on ARM64 Android.

QEMU cannot replace the RootFS. If the plugin has QEMU but lacks the x86_64 dynamic linker and user-space libraries, it must remain marked as “not ready”; do not append `-q` in PRoot.
