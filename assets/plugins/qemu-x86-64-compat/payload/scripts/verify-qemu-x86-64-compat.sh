#!/bin/sh
set -eu

COMPAT_ROOT="/opt/taixu/compat/x86_64"
elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }
is_x86_64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "3e00"; }
fail() { echo "QEMU x86_64 user-mode plugin verification failed: $*" >&2; exit 7; }

test -x "$COMPAT_ROOT/qemu-x86_64" || fail "qemu-x86_64 is missing"
is_aarch64_elf "$COMPAT_ROOT/qemu-x86_64" || fail "qemu-x86_64 is not an ARM64 ELF binary"
test -f "$COMPAT_ROOT/rootfs/bin/sh" || fail "x86_64 rootfs/bin/sh is missing"
is_x86_64_elf "$COMPAT_ROOT/rootfs/bin/sh" || fail "rootfs/bin/sh is not an x86_64 ELF binary"
test -f "$COMPAT_ROOT/rootfs/lib64/ld-linux-x86-64.so.2" ||
    test -f "$COMPAT_ROOT/rootfs/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" ||
    fail "x86_64 dynamic linker is missing"
test -x /opt/taixu/bin/qemu-x86_64 || fail "qemu-x86_64 command entry point is missing"
"$COMPAT_ROOT/qemu-x86_64" --version >/dev/null 2>&1 || fail "qemu-x86_64 could not be executed"
echo "QEMU x86_64 user-mode plugin is ready"
