#!/bin/sh
set -eu

elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }

fail() {
    echo "Android suite verification failed: $*" >&2
    exit 7
}

require_executable() { test -x "$1" || fail "missing executable: $1"; }
require_file() { test -f "$1" || fail "missing file: $1"; }
require_directory() { test -d "$1" || fail "missing directory: $1"; }
require_aarch64() { is_aarch64_elf "$1" || fail "not an ARM64 ELF: $1"; }
require_command() {
    command_path="$1"
    shift
    "$command_path" "$@" >/dev/null 2>&1 || fail "command failed: $command_path $*"
}
require_property() {
    expected="$1"
    grep -Fqx "$expected" /root/.gradle/gradle.properties ||
        fail "missing Gradle property: $expected"
}

require_executable /opt/taixu/bin/java
require_executable /opt/taixu/bin/javac
require_executable /opt/taixu/bin/gradle
require_executable /opt/taixu/bin/cmake
require_executable /opt/taixu/bin/ninja
# Run static assertions before any -version command: if the java launcher is replaced by a wrapper script and symlinks
# form a loop, require_command can hang in an infinite exec cycle; detect this first using the ELF magic bytes.
# od follows symlinks; the resolved symlink chain must end at the JDK's AArch64 ELF binary.
require_aarch64 /opt/taixu/bin/java
require_aarch64 /opt/taixu/bin/javac
require_file /opt/android-sdk/platforms/android-34/android.jar
require_file /opt/android-sdk/build-tools/35.0.0/lib/d8.jar
require_executable /opt/android-sdk/build-tools/35.0.0/aapt2
require_aarch64 /opt/android-sdk/build-tools/35.0.0/aapt2
NDK_STRIP=$(find /opt/taixu/toolchains/android/ndk/toolchains/llvm/prebuilt \( -type f -o -type l \) -name llvm-strip -print -quit)
test -n "$NDK_STRIP" || fail "missing NDK llvm-strip"
require_executable "$NDK_STRIP"
require_aarch64 "$NDK_STRIP"
require_executable /opt/taixu/bin/adb
if is_aarch64_elf /opt/taixu/bin/adb; then
    :
else
    # /opt/taixu/bin/adb is a smart-connection wrapper; verify the underlying real ELF binary.
    REAL_ADB="${TAIXU_TOOL_DIR:-/opt/taixu/tools/android-suite-offline}/bin/adb"
    test -x "$REAL_ADB" || REAL_ADB="/opt/android-sdk/platform-tools/adb"
    require_executable "$REAL_ADB"
    require_aarch64 "$REAL_ADB"
fi
require_command /opt/taixu/bin/java -version
require_command /opt/taixu/bin/gradle --version
require_command /opt/taixu/bin/cmake --version
require_command /opt/taixu/bin/ninja --version
require_property 'org.gradle.daemon=false'
require_property 'org.gradle.parallel=false'
require_property 'org.gradle.workers.max=2'
grep -Fq 'org.gradle.jvmargs=-Xmx1024m' /root/.gradle/gradle.properties ||
    fail 'missing Gradle property: org.gradle.jvmargs=-Xmx1024m...'
require_executable /opt/taixu/bin/flutter
/opt/taixu/bin/flutter --version >/dev/null 2>&1 || true
# Android-only policy: web/desktop/iOS artifacts are intentionally not required.
require_directory /opt/flutter/bin/cache/artifacts/engine/android-arm64-release
require_directory /opt/flutter/bin/cache/artifacts/engine/android-arm64-profile
if [ -f /opt/flutter/bin/cache/artifacts/engine/linux-arm64/gen_snapshot ]; then
    require_executable /opt/flutter/bin/cache/artifacts/engine/linux-arm64/gen_snapshot
    require_aarch64 /opt/flutter/bin/cache/artifacts/engine/linux-arm64/gen_snapshot
fi
if [ -f /opt/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot ]; then
    require_executable /opt/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot
    require_aarch64 /opt/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot
fi

if [ -f /opt/taixu/bin/rg ]; then
    require_executable /opt/taixu/bin/rg
    require_aarch64 /opt/taixu/bin/rg
    require_command /opt/taixu/bin/rg --version
fi

if [ -f /opt/taixu/bin/jadx ]; then
    require_executable /opt/taixu/bin/jadx
    require_command /opt/taixu/bin/jadx --version
fi

if [ -f /opt/taixu/bin/apktool ]; then
    require_executable /opt/taixu/bin/apktool
    require_file /opt/taixu/tools/android-suite-offline/lib/apktool.jar
    require_command /opt/taixu/bin/apktool --version
fi

if [ -f /opt/taixu/bin/d2j-dex2jar ]; then
    require_executable /opt/taixu/bin/d2j-dex2jar
fi

if [ -f /opt/taixu/bin/rustc ]; then
    require_executable /opt/taixu/bin/rustc
    require_aarch64 /opt/taixu/bin/rustc
    require_command /opt/taixu/bin/rustc --version
    if [ -f /opt/taixu/bin/cargo ]; then
        require_executable /opt/taixu/bin/cargo
        require_command /opt/taixu/bin/cargo --version
    fi
fi

if [ -f /opt/taixu/bin/apksigner ]; then
    require_executable /opt/taixu/bin/apksigner
fi
if [ -f /opt/taixu/bin/zipalign ]; then
    require_executable /opt/taixu/bin/zipalign
    require_aarch64 /opt/taixu/bin/zipalign
fi

echo "TaiXu Android ARM64 offline suite is ready"
