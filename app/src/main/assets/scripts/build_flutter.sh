#!/bin/sh
# ==============================================================================
# TaiXu (LinuxAIRuntime) - Flutter Project One-Key Build Engine
# Usage: build_flutter.sh <project_path> [target]
# ==============================================================================
set -e

PROJECT_PATH="${1:-.}"
TARGET="${2:-apk --debug}"
GRADLE_VER="8.14.2"

echo "==> [TaiXu Build Engine] Starting Flutter project build..."
echo "==> [TaiXu Build] Project path: $PROJECT_PATH"

TOOLCHAIN_LOCK_FILE="/opt/taixu/locks/android-toolchain.lock"
mkdir -p /opt/taixu/locks
command -v flock >/dev/null 2>&1 || {
    echo "==> [TaiXu Build] ❌ flock is missing; refusing to build without a toolchain lock"
    exit 1
}
exec 9>"$TOOLCHAIN_LOCK_FILE"
flock -s -w 1800 9 || {
    echo "==> [TaiXu Build] ❌ Timed out waiting for Android/Flutter toolchain setup"
    exit 1
}

# 1. Inject Flutter and Gradle into PATH (prefer environment variables persisted during plugin setup)
workshop_java_home="${JAVA_HOME:-}"
workshop_android_home="${ANDROID_HOME:-}"
workshop_gradle_home="${GRADLE_HOME:-}"
workshop_flutter_home="${FLUTTER_HOME:-}"
workshop_ndk_path="${TAIXU_NDK_PATH:-}"
workshop_android_ndk_home="${ANDROID_NDK_HOME:-}"
workshop_aapt2_path="${TAIXU_AAPT2_PATH:-}"
workshop_cmake_home="${TAIXU_CMAKE_HOME:-}"
workshop_ninja_home="${TAIXU_NINJA_HOME:-}"
workshop_gradle_user_home="${GRADLE_USER_HOME:-}"
workshop_pub_cache="${PUB_CACHE:-}"
workshop_tool_dir="${TAIXU_TOOL_DIR:-}"
if [ -f /etc/profile.d/taixu-android.sh ]; then . /etc/profile.d/taixu-android.sh; fi
[ -z "$workshop_java_home" ] || JAVA_HOME="$workshop_java_home"
[ -z "$workshop_android_home" ] || ANDROID_HOME="$workshop_android_home"
[ -z "$workshop_gradle_home" ] || GRADLE_HOME="$workshop_gradle_home"
[ -z "$workshop_flutter_home" ] || FLUTTER_HOME="$workshop_flutter_home"
[ -z "$workshop_ndk_path" ] || TAIXU_NDK_PATH="$workshop_ndk_path"
[ -z "$workshop_android_ndk_home" ] || ANDROID_NDK_HOME="$workshop_android_ndk_home"
[ -z "$workshop_aapt2_path" ] || TAIXU_AAPT2_PATH="$workshop_aapt2_path"
[ -z "$workshop_cmake_home" ] || TAIXU_CMAKE_HOME="$workshop_cmake_home"
[ -z "$workshop_ninja_home" ] || TAIXU_NINJA_HOME="$workshop_ninja_home"
[ -z "$workshop_gradle_user_home" ] || GRADLE_USER_HOME="$workshop_gradle_user_home"
[ -z "$workshop_pub_cache" ] || PUB_CACHE="$workshop_pub_cache"
[ -z "$workshop_tool_dir" ] || TAIXU_TOOL_DIR="$workshop_tool_dir"
export FLUTTER_HOME="${FLUTTER_HOME:-/opt/flutter}"
export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle-$GRADLE_VER}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export TAIXU_TOOL_DIR="${TAIXU_TOOL_DIR:-/opt/taixu/tools}"
export PATH="/opt/taixu/bin:$FLUTTER_HOME/bin:$GRADLE_HOME/bin:${TAIXU_CMAKE_HOME:-/opt/taixu/tools/android-suite-offline/cmake}/bin:${TAIXU_NINJA_HOME:-/opt/taixu/tools/android-suite-offline/bin}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PUB_HOSTED_URL="https://pub.flutter-io.cn"
export FLUTTER_STORAGE_BASE_URL="https://storage.flutter-io.cn"
export PUB_CACHE="${PUB_CACHE:-/opt/taixu/cache/flutter-pub}"
NDK_PATH="${TAIXU_NDK_PATH:-${ANDROID_NDK_HOME:-/opt/taixu/toolchains/android/ndk}}"
LLVM_STRIP=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name llvm-strip -print -quit 2>/dev/null)
NDK_CLANG=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name clang -print -quit 2>/dev/null)
if [ -z "$NDK_PATH" ] || [ ! -f "$NDK_PATH/source.properties" ] || \
   [ ! -x "$LLVM_STRIP" ] || [ ! -x "$NDK_CLANG" ]; then
    echo "==> [TaiXu Build] ❌ Pinned ARM64 NDK is missing; reinstall the Android Core Environment"
    exit 126
fi
STRIP_MACHINE=$(od -An -t x1 -j 18 -N 2 "$LLVM_STRIP" 2>/dev/null | tr -d '[:space:]')
CLANG_MACHINE=$(od -An -t x1 -j 18 -N 2 "$NDK_CLANG" 2>/dev/null | tr -d '[:space:]')
if [ "$STRIP_MACHINE" != "b700" ] || [ "$CLANG_MACHINE" != "b700" ] || \
   ! "$LLVM_STRIP" --version >/dev/null 2>&1 || \
   ! "$NDK_CLANG" --version >/dev/null 2>&1; then
    echo "==> [TaiXu Build] ❌ NDK host tool is not an executable Linux AArch64 binary"
    exit 126
fi
export ANDROID_NDK_HOME="$NDK_PATH"
export ANDROID_NDK_ROOT="$NDK_PATH"
export TAIXU_LLVM_STRIP_PATH="$LLVM_STRIP"
echo "==> [TaiXu Build] Pinned ARM64 NDK: $NDK_PATH"

# Java launcher loop guard (same rule as build_android.sh): Flutter's Gradle
# host build ultimately starts the JVM through JAVA_HOME. A wrapper-script loop under PRoot produces no output and
# a CPU-saturating infinite loop, so reject it before starting the JVM.
JAVA_GUARD="${JAVA_HOME:-/opt/taixu/toolchains/android/jdk}/bin/java"
if [ -e "$JAVA_GUARD" ]; then
    JAVA_GUARD_REAL=$(readlink -f "$JAVA_GUARD" 2>/dev/null || echo "$JAVA_GUARD")
    JAVA_GUARD_MAGIC=$(od -An -t x1 -N 4 "$JAVA_GUARD_REAL" 2>/dev/null | tr -d '[:space:]')
    if [ "$JAVA_GUARD_MAGIC" != "7f454c46" ]; then
        echo "==> [TaiXu Build] ❌ Java launcher is not an ELF binary (possible wrapper script or symlink loop): $JAVA_GUARD_REAL"
        echo "==> [TaiXu Build] Reinstall the Android Full-Stack Development Suite from Plugin Center to repair the JDK"
        exit 126
    fi
fi

# 2. Repair symlinks and map the native gen_snapshot binary
if [ -d "$FLUTTER_HOME/bin" ] && [ ! -f /usr/local/bin/flutter ]; then
    ln -sf "$FLUTTER_HOME/bin/flutter" /usr/local/bin/flutter 2>/dev/null || true
    ln -sf "$FLUTTER_HOME/bin/dart" /usr/local/bin/dart 2>/dev/null || true
fi

# When building Android release/profile APKs on a Linux ARM64 host, the Flutter engine
# looks for android-arm64-{release,profile}/linux-arm64/gen_snapshot via artifacts.dart.
# If missing, create a symlink from the native linux-arm64/gen_snapshot (AArch64 ELF) into the expected directory.
ENGINE_DIR="$FLUTTER_HOME/bin/cache/artifacts/engine"
if [ -x "$ENGINE_DIR/linux-arm64/gen_snapshot" ]; then
    for mode in release profile; do
        target_dir="$ENGINE_DIR/android-arm64-$mode/linux-arm64"
        mkdir -p "$target_dir" 2>/dev/null || true
        if [ ! -e "$target_dir/gen_snapshot" ]; then
            ln -sf "$ENGINE_DIR/linux-arm64/gen_snapshot" "$target_dir/gen_snapshot" 2>/dev/null || true
            echo "==> [TaiXu Build] Repaired ARM64 gen_snapshot link -> $target_dir/gen_snapshot"
        fi
    done
fi

cd "$PROJECT_PATH"

# ==============================================================================
# Temporarily align build-tool versions (automatically restore after the build; do not modify the toolchains themselves)
#
# 1. NDK: the Flutter Gradle plugin injects a default ndkVersion (e.g. 27.0.12077973), which may differ from
#    the NDK installed by TaiXu (e.g. 29.0.14206865), causing AGP error CXX1100.
# 2. CMake: Flutter hardcodes CMake 3.22.1, while TaiXu may have a newer version installed,
#    causing AGP error CXX1300.
#
# Approach: temporarily update android/app/build.gradle to inject the installed versions, then
# restore the original file through trap EXIT. The NDK/CMake installations themselves are not modified.
# ==============================================================================
TAIXU_GRADLE_ALIGN_BAK=""
taixu_align_build_versions() {
    app_gradle="android/app/build.gradle"
    [ -f "$app_gradle" ] || return 0
    modified=0
    backup_once() {
        if [ $modified -eq 0 ]; then
            TAIXU_GRADLE_ALIGN_BAK="${app_gradle}.taixu-align.bak"
            cp "$app_gradle" "$TAIXU_GRADLE_ALIGN_BAK"
            modified=1
        fi
    }

    # --- NDK version alignment ---
    ndk_home="${ANDROID_NDK_HOME:-/opt/taixu/toolchains/android/ndk}"
    if [ -f "$ndk_home/source.properties" ]; then
        actual_ndk=$(sed -n 's/^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$ndk_home/source.properties" | head -n1 | tr -d '[:space:]')
        if [ -n "$actual_ndk" ]; then
            declared_ndk=$(grep -oE 'ndkVersion[[:space:]]*["'"'"'][^"'"'"']*["'"'"']' "$app_gradle" | head -1 | grep -oE '["'"'"'][^"'"'"']*["'"'"']' | tr -d "\"'")
            if [ -z "$declared_ndk" ] || [ "$declared_ndk" != "$actual_ndk" ]; then
                echo "==> [TaiXu Build] NDK version alignment：project declares ${declared_ndk:-<Flutter default>}, installed=$actual_ndk"
                backup_once
                sed -i "s/^\([[:space:]]*android[[:space:]]*{\)/\1\n    ndkVersion \"$actual_ndk\"/" "$app_gradle"
                if grep -q "ndkVersion \"$actual_ndk\"" "$app_gradle"; then
                    echo "==> [TaiXu Build] ✅ Injected ndkVersion=$actual_ndk"
                else
                    echo "==> [TaiXu Build] ⚠️ Failed to inject ndkVersion"
                fi
            fi
        else
            echo "==> [TaiXu Build] ⚠️ Could not read NDK version; skipping NDK alignment"
        fi
    fi

    # --- CMake version alignment ---
    # Flutter/AGP default to CMake 3.22.1, while TaiXu may have a newer version installed.
    # Set externalNativeBuild.cmake.version inside the android {} configuration block;
    # setting it during afterEvaluate is rejected by AGP ("It is too late to set version").
    if command -v cmake >/dev/null 2>&1; then
        actual_cmake=$(cmake --version 2>/dev/null | head -n1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n1)
        flutter_cmake="3.22.1"
        if [ -n "$actual_cmake" ] && [ "$actual_cmake" != "$flutter_cmake" ]; then
            echo "==> [TaiXu Build] CMake version alignment：Flutter requires=$flutter_cmake, installed=$actual_cmake"
            backup_once
            # Inject externalNativeBuild.cmake.version inside the android { block
            sed -i "s/^\([[:space:]]*android[[:space:]]*{\)/\1\n    externalNativeBuild {\n        cmake {\n            version \"$actual_cmake\"\n        }\n    }/" "$app_gradle"
            if grep -q "version \"$actual_cmake\"" "$app_gradle"; then
                echo "==> [TaiXu Build] ✅ Injected externalNativeBuild.cmake.version=$actual_cmake"
            else
                echo "==> [TaiXu Build] ⚠️ Failed to inject the CMake version"
            fi
        fi
    fi
}
taixu_restore_gradle() {
    if [ -n "$TAIXU_GRADLE_ALIGN_BAK" ] && [ -f "$TAIXU_GRADLE_ALIGN_BAK" ]; then
        mv "$TAIXU_GRADLE_ALIGN_BAK" "android/app/build.gradle"
        echo "==> [TaiXu Build] Restored android/app/build.gradle (temporary version-alignment changes reverted)"
    fi
}
trap taixu_restore_gradle EXIT
taixu_align_build_versions

if ! command -v flutter >/dev/null 2>&1; then
    echo "==> [TaiXu Build] ❌ Flutter SDK not found; install the Flutter development suite"
    exit 127
fi

# The Flutter toolchain uses unzip to extract engine caches (bin/cache/downloads/*.zip).
# The minimal rootfs has no system unzip; the temporary installation shim exists only under TOOL_DIR/bin,
# which is not on the build PATH. Before launching Flutter, create a persistent wrapper using the JDK's jar
# at /opt/taixu/bin/unzip (first in PATH) to prevent "Missing unzip tool" failures.
if ! command -v unzip >/dev/null 2>&1; then
    JAR_BIN=""
    for candidate in "${JAVA_HOME:-/opt/taixu/toolchains/android/jdk}/bin/jar" /opt/taixu/toolchains/android/jdk/bin/jar /usr/bin/jar /usr/lib/jvm/default-java/bin/jar; do
        if [ -x "$candidate" ]; then JAR_BIN="$candidate"; break; fi
    done
    if [ -n "$JAR_BIN" ]; then
        mkdir -p /opt/taixu/bin
        printf '%s\n' \
            '#!/bin/sh' \
            'archive=' \
            'dest=.' \
            'while [ "$#" -gt 0 ]; do' \
            '  case "$1" in' \
            '    -q|-qq|-o) shift ;;' \
            '    -d) dest="$2"; shift 2 ;;' \
            '    -*) shift ;;' \
            '    *) archive="$1"; shift ;;' \
            '  esac' \
            'done' \
            '[ -n "$archive" ] || exit 2' \
            'mkdir -p "$dest"' \
            "(cd \"\$dest\" && '$JAR_BIN' xf \"\$archive\")" \
            > /opt/taixu/bin/unzip
        chmod 755 /opt/taixu/bin/unzip
        echo "==> [TaiXu Build] Installed JDK-JAR-based unzip compatibility wrapper: /opt/taixu/bin/unzip"
    else
        echo "==> [TaiXu Build] ❌ unzip is missing and no JDK jar is available to extract Flutter engine caches"
        echo "==> [TaiXu Build] Reinstall the Android Full-Stack Development Suite"
        exit 127
    fi
fi

if [ ! -x "$FLUTTER_HOME/bin/flutter" ] || [ ! -x "$FLUTTER_HOME/bin/cache/dart-sdk/bin/dart" ]; then
    echo "==> [TaiXu Build] ❌ Flutter SDK is not a usable Linux ARM64 build; reinstall the Flutter suite in Workshop"
    exit 126
fi
# Flutter's locateAndroidSdk requires $ANDROID_HOME/platform-tools/adb to exist
# to recognize the SDK (older suite versions installed only build-tools and platform, with adb in TOOL_DIR/bin).
# Without it, Flutter reports "No Android SDK found. Try setting the ANDROID_HOME"; repair the layout here
# and licenses so existing sandboxes do not need the plugin reinstalled.
if [ ! -e "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB_CANDIDATE=""
    for candidate in "${TAIXU_TOOL_DIR:-/opt/taixu/tools/android-suite-offline}/bin/adb" /opt/taixu/bin/adb; do
        if [ -x "$candidate" ]; then ADB_CANDIDATE="$candidate"; break; fi
    done
    mkdir -p "$ANDROID_HOME/platform-tools"
    if [ -n "$ADB_CANDIDATE" ]; then
        ln -sfn "$ADB_CANDIDATE" "$ANDROID_HOME/platform-tools/adb"
        echo "==> [TaiXu Build] Completed Flutter SDK layout: $ANDROID_HOME/platform-tools/adb -> $ADB_CANDIDATE"
    fi
fi
mkdir -p "$ANDROID_HOME/licenses"
if [ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]; then
    printf '\x89\x50\x41\x59\x0d\x0a\x1a\x0a\xd0\x4a\x87\x95\x6d\x7d\x3c\xcf\x9d\nd56f5187d9450ff8409f4ab7c8ab84e9\n' \
        > "$ANDROID_HOME/licenses/android-sdk-license"
fi

if [ ! -f "${ANDROID_HOME}/platforms/android-34/android.jar" ]; then
    echo "==> [TaiXu Build] ❌ Android SDK Platform 34 is missing; install the Android Core Environment as well"
    exit 126
fi
if [ ! -f "${ANDROID_HOME}/build-tools/35.0.0/lib/d8.jar" ]; then
    echo "==> [TaiXu Build] ❌ Android Build Tools 35.0.0 are missing; reinstall the Android Core Environment"
    exit 126
fi

# Migrate projects generated by the previous TaiXu Flutter template. That
# template disabled stripping for every .so to bypass the x86_64 NDK tool,
# which could inflate a debug APK beyond 1 GB. Only remove the exact managed
# block; user-defined, selective keepDebugSymbols rules are left untouched.
FLUTTER_APP_GRADLE="android/app/build.gradle"
if [ -f "$FLUTTER_APP_GRADLE" ] && grep -Fq 'keepDebugSymbols += "**/*.so"' "$FLUTTER_APP_GRADLE"; then
    python3 - "$FLUTTER_APP_GRADLE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
managed_block = '''    // NDK 只提供 linux-x86_64 的 llvm-strip，在 ARM64 PRoot 里无法启动。
    // 保留 native 调试符号，避免 AGP 调用它导致构建失败。
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

'''
if managed_block in text:
    path.write_text(text.replace(managed_block, "", 1), encoding="utf-8")
    print("==> [TaiXu Build] Migrated legacy Flutter template: enabled native symbol stripping")
PY
    if grep -Fq 'keepDebugSymbols += "**/*.so"' "$FLUTTER_APP_GRADLE"; then
        echo "==> [TaiXu Build] ❌ A full keepDebugSymbols configuration was detected; remove it and try again"
        exit 126
    fi
fi

mkdir -p "$PUB_CACHE" android
if [ -f "$FLUTTER_HOME/bin/flutter" ]; then
    LOCAL_PROPERTIES=android/local.properties
    LOCAL_PROPERTIES_TMP="${LOCAL_PROPERTIES}.taixu.tmp"
    if [ -f "$LOCAL_PROPERTIES" ]; then
        sed -e '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' \
            -e '/^[[:space:]]*ndk\.dir[[:space:]]*=/d' \
            -e '/^[[:space:]]*flutter\.sdk[[:space:]]*=/d' \
            "$LOCAL_PROPERTIES" > "$LOCAL_PROPERTIES_TMP"
    else
        : > "$LOCAL_PROPERTIES_TMP"
    fi
    printf 'sdk.dir=%s\nflutter.sdk=%s\n' "$ANDROID_HOME" "$FLUTTER_HOME" >> "$LOCAL_PROPERTIES_TMP"
    mv -f "$LOCAL_PROPERTIES_TMP" "$LOCAL_PROPERTIES"
    echo "==> [TaiXu Build] Binding ANDROID_HOME/Flutter SDK: $ANDROID_HOME / $FLUTTER_HOME"
fi
AAPT2_PATH="${TAIXU_AAPT2_PATH:-$ANDROID_HOME/build-tools/35.0.0/aapt2}"
case "$AAPT2_PATH" in
    /opt/android-sdk/build-tools/35.0.0/aapt2|/opt/taixu/toolchains/android/sdk-tools/artifacts/*/build-tools/aapt2) ;;
    *)
        echo "==> [TaiXu Build] ❌ AAPT2 does not point to the immutable ARM64 artifact directory"
        exit 126
        ;;
esac
AAPT2_MACHINE=$(od -An -t x1 -j 18 -N 2 "$AAPT2_PATH" 2>/dev/null | tr -d '[:space:]')
if [ "$AAPT2_MACHINE" = "b700" ] && [ -x "$AAPT2_PATH" ] && \
   "$AAPT2_PATH" version >/dev/null 2>&1; then
    export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="$AAPT2_PATH"
    echo "==> [TaiXu Build] Using native ARM64 AAPT2: $AAPT2_PATH"
else
    echo "==> [TaiXu Build] ❌ Pinned ARM64 AAPT2 became unavailable before the build started"
    exit 126
fi

if ! grep -Fqx 'android.builder.sdkDownload=false' "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null; then
    echo "==> [TaiXu Build] ❌ Gradle SDK auto-download is enabled; refusing to build to prevent official x86_64 tools from overwriting ARM64 tools"
    exit 126
fi
if [ ! -f "$GRADLE_USER_HOME/init.d/taixu-android-ndk.gradle" ] || \
   ! grep -Fq 'androidExtension.ndkPath = taixuNdkPath' "$GRADLE_USER_HOME/init.d/taixu-android-ndk.gradle"; then
    echo "==> [TaiXu Build] ❌ Pinned NDK path injection is missing"
    exit 126
fi

# TaiXu: Fallback: inject HTTP connect/read timeouts into global Gradle configuration to avoid
# silent, indefinite dependency-resolution stalls when mirrors are slow or reset (SocketException: connection abort). Append idempotently, adding only missing lines.
GRADLE_PROPS="$GRADLE_USER_HOME/gradle.properties"
if [ -f "$GRADLE_PROPS" ]; then
    for k in \
        'systemProp.org.gradle.internal.http.connectionTimeout=30000' \
        'systemProp.org.gradle.internal.http.socketTimeout=60000'; do
        if ! grep -Fqx "$k" "$GRADLE_PROPS" 2>/dev/null; then
            printf '%s\n' "$k" >> "$GRADLE_PROPS"
        fi
    done
fi

export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.jvmargs=-Xmx1024m"

if [ "${TAIXU_OFFLINE:-0}" = "1" ]; then
    echo "==> [TaiXu Build] Offline mode: using the local Flutter Pub cache"
    flutter pub get --offline --verbose
else
    echo "==> [TaiXu Build] Fetching Flutter dependencies (flutter pub get)..."
    flutter pub get --verbose
fi

# 3. Ensure the Android host uses local Gradle 8.14.2 and configured mirrors, preventing the Wrapper from downloading from services.gradle.org
#    (connections may reset on some networks, causing SocketException: connection abort).
mkdir -p android/gradle/wrapper
cat > android/gradle/wrapper/gradle-wrapper.properties <<'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.2-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF
cat > android/gradlew <<'EOF'
#!/bin/sh
# Prefer the local Gradle 8.14.2 installed by the plugin to avoid another Wrapper distribution download.
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    exec "$GRADLE_HOME/bin/gradle" "$@"
elif [ -x /opt/gradle-8.7/bin/gradle ]; then
    exec /opt/gradle-8.7/bin/gradle "$@"
elif [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
elif [ -x /usr/local/bin/gradle ]; then
    exec /usr/local/bin/gradle "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    exec /usr/bin/gradle "$@"
fi
EOF
chmod +x android/gradlew

# 4. Force the configured mirror globally: Flutter rewrites project files, so add a fallback global init script
#    and pin GRADLE_USER_HOME to /root/.gradle so dependency resolution uses the configured mirror.
mkdir -p "$GRADLE_USER_HOME"
cat > "$GRADLE_USER_HOME/init.gradle" <<'EOF'
// TaiXu: 全局强制阿里云镜像。
gradle.beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://storage.flutter-io.cn/download.flutter.io' }
        google()
        mavenCentral()
    }
}
EOF

echo "==> [TaiXu Build] Running Flutter build (flutter build $TARGET)..."
# 注意：不能用 exec，否则 trap EXIT 不会触发，NDK version alignment的临时修改无法恢复。
if [ "${TAIXU_OFFLINE:-0}" = "1" ]; then
    flutter build $TARGET --offline --verbose
else
    flutter build $TARGET --verbose
fi
build_exit=$?
exit $build_exit
