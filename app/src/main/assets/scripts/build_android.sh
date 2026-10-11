#!/bin/sh
# ==============================================================================
# TaiXu (LinuxAIRuntime) - Android Project One-Key Build Engine
# Usage: build_android.sh <project_path> [task]
# ------------------------------------------------------------------------------
# Execution-only script: all environment setup is performed by the Android & Mobile Full-Stack Development Suite plugin.
# This script only loads the environment and invokes Gradle. It does not repair, download, or self-heal anything.
# ==============================================================================
set -e

PROJECT_PATH="${1:-.}"
TASK="${2:-assembleDebug}"
GRADLE_VER="8.14.2"

echo "==> [TaiXu Build Engine] Starting Android project build..."
echo "==> [TaiXu Build] Project path: $PROJECT_PATH"
echo "==> [TaiXu Build] Build task: $TASK"

# Builds take a shared lock for their entire lifetime. Android toolchain setup
# takes the exclusive side of the same lock, so immutable views cannot change
# between preflight and a later AGP worker launch.
TOOLCHAIN_LOCK_FILE="/opt/taixu/locks/android-toolchain.lock"
mkdir -p /opt/taixu/locks
command -v flock >/dev/null 2>&1 || {
    echo "==> [TaiXu Build] ❌ flock is missing; refusing to build without a toolchain lock"
    exit 1
}
exec 9>"$TOOLCHAIN_LOCK_FILE"
flock -s -w 1800 9 || {
    echo "==> [TaiXu Build] ❌ Android toolchain setup timed out while waiting for the lock"
    exit 1
}

# 1. Load environment variables persisted during plugin setup
workshop_java_home="${JAVA_HOME:-}"
workshop_android_home="${ANDROID_HOME:-}"
workshop_gradle_home="${GRADLE_HOME:-}"
workshop_ndk_path="${TAIXU_NDK_PATH:-}"
workshop_android_ndk_home="${ANDROID_NDK_HOME:-}"
workshop_aapt2_path="${TAIXU_AAPT2_PATH:-}"
workshop_cmake_home="${TAIXU_CMAKE_HOME:-}"
workshop_ninja_home="${TAIXU_NINJA_HOME:-}"
workshop_gradle_user_home="${GRADLE_USER_HOME:-}"
workshop_tool_dir="${TAIXU_TOOL_DIR:-}"
if [ -f /etc/profile.d/taixu-android.sh ]; then . /etc/profile.d/taixu-android.sh; fi
[ -z "$workshop_java_home" ] || JAVA_HOME="$workshop_java_home"
[ -z "$workshop_android_home" ] || ANDROID_HOME="$workshop_android_home"
[ -z "$workshop_gradle_home" ] || GRADLE_HOME="$workshop_gradle_home"
[ -z "$workshop_ndk_path" ] || TAIXU_NDK_PATH="$workshop_ndk_path"
[ -z "$workshop_android_ndk_home" ] || ANDROID_NDK_HOME="$workshop_android_ndk_home"
[ -z "$workshop_aapt2_path" ] || TAIXU_AAPT2_PATH="$workshop_aapt2_path"
[ -z "$workshop_cmake_home" ] || TAIXU_CMAKE_HOME="$workshop_cmake_home"
[ -z "$workshop_ninja_home" ] || TAIXU_NINJA_HOME="$workshop_ninja_home"
[ -z "$workshop_gradle_user_home" ] || GRADLE_USER_HOME="$workshop_gradle_user_home"
[ -z "$workshop_tool_dir" ] || TAIXU_TOOL_DIR="$workshop_tool_dir"
# /etc/environment is written during plugin setup as a fallback for non-login shells in PRoot
if [ -f /etc/environment ]; then
    while IFS= read -r line; do
        case "$line" in
            *=*) key="${line%%=*}"
                 val="${line#*=}"
                 eval "current=\${$key}"
                 [ -z "$current" ] && export "$key=$val"
                 ;;
        esac
    done < /etc/environment
fi
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle-$GRADLE_VER}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export TAIXU_TOOL_DIR="${TAIXU_TOOL_DIR:-/opt/taixu/tools}"

# The local offline suite is authoritative when present. A distro package may
# leave an x86_64 Java earlier on PATH, which is unusable in the ARM64 PRoot.
if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/taixu/toolchains/android/jdk/bin/java ]; then
    JAVA_HOME=/opt/taixu/toolchains/android/jdk
fi

# AGP's default Maven artifact is a Linux x86_64 executable.  When the
# android-core plugin installed the ARM64 tool bundle, pass the real aapt2
# executable explicitly so AGP never tries to start the incompatible daemon.
AAPT2_OVERRIDE="${TAIXU_AAPT2_PATH:-$ANDROID_HOME/build-tools/35.0.0/aapt2}"
NDK_PATH="${TAIXU_NDK_PATH:-${ANDROID_NDK_HOME:-/opt/taixu/toolchains/android/ndk}}"

# A non-login shell may not inherit the profile written by the plugin; if variables are empty, resolve them from the standard JDK
# directories and PATH to avoid build failures caused by environment-variable drift.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    JAVA_HOME=""
    for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-aarch64 /usr/lib/jvm/default-java; do
        if [ -x "$candidate/bin/java" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_BIN_FALLBACK=$(command -v java 2>/dev/null || true)
    if [ -n "$JAVA_BIN_FALLBACK" ]; then
        JAVA_REAL_FALLBACK=$(readlink -f "$JAVA_BIN_FALLBACK" 2>/dev/null || echo "$JAVA_BIN_FALLBACK")
        JAVA_HOME=$(dirname "$(dirname "$JAVA_REAL_FALLBACK")")
    fi
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# Pin the Java executable for this build to avoid an old PATH symlink pointing to a different JDK.
JAVA_EXEC=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_EXEC="$JAVA_HOME/bin/java"
else
    JAVA_EXEC=$(command -v java 2>/dev/null || true)
    if [ -n "$JAVA_EXEC" ] && command -v readlink >/dev/null 2>&1; then
        JAVA_REAL=$(readlink -f "$JAVA_EXEC" 2>/dev/null || true)
        [ -n "$JAVA_REAL" ] && JAVA_EXEC="$JAVA_REAL"
    fi
fi
if [ -z "$JAVA_EXEC" ] || [ ! -x "$JAVA_EXEC" ]; then
    echo "==> [TaiXu Build] ❌ No executable Java found (JAVA_HOME=$JAVA_HOME)"
    exit 127
fi
# The Java launcher must resolve to a real ELF. If a wrapper script and its exec target form a loop
# (script → symlink → script), each exec goes through PRoot ptrace translation,
# causing a JVM hang with no output and sustained full CPU usage. Reject this before starting the JVM,
# rather than letting it run until the 30-minute timeout.
JAVA_REAL=$(readlink -f "$JAVA_EXEC" 2>/dev/null || echo "$JAVA_EXEC")
JAVA_MAGIC=$(od -An -t x1 -N 4 "$JAVA_REAL" 2>/dev/null | tr -d '[:space:]')
if [ "$JAVA_MAGIC" != "7f454c46" ]; then
    echo "==> [TaiXu Build] ❌ Java launcher is not an ELF binary (possible wrapper script or symlink loop): $JAVA_REAL"
    echo "==> [TaiXu Build] Reinstall the Android Full-Stack Development Suite from Plugin Center to repair the JDK"
    exit 126
fi
JAVA_LAUNCHER_MACHINE=$(od -An -t x1 -j 18 -N 2 "$JAVA_REAL" 2>/dev/null | tr -d '[:space:]')
if [ "$JAVA_LAUNCHER_MACHINE" != "b700" ]; then
    echo "==> [TaiXu Build] ❌ Java launcher is not an AArch64 ELF (machine=$JAVA_LAUNCHER_MACHINE): $JAVA_REAL"
    exit 126
fi
echo "==> [TaiXu Build] Java executable: $JAVA_EXEC"

echo "==> [TaiXu Build] JAVA_HOME: $JAVA_HOME"
echo "==> [TaiXu Build] ANDROID_HOME: $ANDROID_HOME"

# AGP 8.11.1 requires SDK Build Tools 35.0.0. Fail before Gradle starts slow
# remote SDK probes, which hide the actionable error in restricted networks.
REQUIRED_BUILD_TOOLS="35.0.0"
if [ ! -f "$ANDROID_HOME/build-tools/$REQUIRED_BUILD_TOOLS/source.properties" ] || \
   [ ! -f "$ANDROID_HOME/build-tools/$REQUIRED_BUILD_TOOLS/lib/d8.jar" ]; then
    echo "==> [TaiXu Build] ❌ Android Build Tools $REQUIRED_BUILD_TOOLS are missing"
    echo "==> [TaiXu Build] Reinstall the Android Core Environment from Plugin Center before building"
    exit 2
fi
case "$AAPT2_OVERRIDE" in
    /opt/android-sdk/build-tools/35.0.0/aapt2|/opt/taixu/toolchains/android/sdk-tools/artifacts/*/build-tools/aapt2) ;;
    *)
        echo "==> [TaiXu Build] ❌ AAPT2 does not point to the immutable ARM64 artifact directory"
        exit 2
        ;;
esac
AAPT2_MACHINE=$(od -An -t x1 -j 18 -N 2 "$AAPT2_OVERRIDE" 2>/dev/null | tr -d '[:space:]')
if [ "$AAPT2_MACHINE" != "b700" ] || [ ! -x "$AAPT2_OVERRIDE" ] || \
   ! "$AAPT2_OVERRIDE" version >/dev/null 2>&1; then
    echo "==> [TaiXu Build] ❌ ARM64 AAPT2 is not installed"
    echo "==> [TaiXu Build] Reinstall the Android Core Environment from Plugin Center before building"
    exit 2
fi

NDK_STRIP=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name llvm-strip -print -quit 2>/dev/null)
NDK_CLANG=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name clang -print -quit 2>/dev/null)
if [ -z "$NDK_PATH" ] || [ ! -f "$NDK_PATH/source.properties" ] || \
   [ ! -x "$NDK_STRIP" ] || [ ! -x "$NDK_CLANG" ]; then
    echo "==> [TaiXu Build] ❌ Pinned ARM64 NDK is missing; reinstall the Android Core Environment"
    exit 2
fi
STRIP_MACHINE=$(od -An -t x1 -j 18 -N 2 "$NDK_STRIP" 2>/dev/null | tr -d '[:space:]')
CLANG_MACHINE=$(od -An -t x1 -j 18 -N 2 "$NDK_CLANG" 2>/dev/null | tr -d '[:space:]')
if [ "$STRIP_MACHINE" != "b700" ] || [ "$CLANG_MACHINE" != "b700" ] || \
   ! "$NDK_STRIP" --version >/dev/null 2>&1 || \
   ! "$NDK_CLANG" --version >/dev/null 2>&1; then
    echo "==> [TaiXu Build] ❌ NDK host tool is not an executable Linux AArch64 binary"
    exit 126
fi
echo "==> [TaiXu Build] Pinned ARM64 NDK: $NDK_PATH"
if ! grep -Fqx 'android.builder.sdkDownload=false' "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null; then
    echo "==> [TaiXu Build] ❌ Gradle SDK auto-download is enabled; refusing to build to prevent official x86_64 tools from overwriting the ARM64 tools"
    exit 2
fi
if [ ! -f "$GRADLE_USER_HOME/init.d/taixu-android-ndk.gradle" ] || \
   ! grep -Fq 'androidExtension.ndkPath = taixuNdkPath' "$GRADLE_USER_HOME/init.d/taixu-android-ndk.gradle"; then
    echo "==> [TaiXu Build] ❌ Pinned NDK path injection is missing"
    exit 2
fi
# 2. Bind the SDK to the current project while preserving other user-defined local.properties keys.
# The NDK path is injected exclusively through android.ndkPath by taixu-android-ndk.gradle.
# Remove the legacy ndk.dir entry without writing it back, because AGP 8.11.1 rejects having both locators.
LOCAL_PROPERTIES="$PROJECT_PATH/local.properties"
LOCAL_PROPERTIES_TMP="${LOCAL_PROPERTIES}.taixu.tmp"
if [ -f "$LOCAL_PROPERTIES" ]; then
    sed -e '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' \
        -e '/^[[:space:]]*ndk\.dir[[:space:]]*=/d' \
        "$LOCAL_PROPERTIES" > "$LOCAL_PROPERTIES_TMP"
else
    : > "$LOCAL_PROPERTIES_TMP"
fi
printf 'sdk.dir=%s\n' "$ANDROID_HOME" >> "$LOCAL_PROPERTIES_TMP"
mv -f "$LOCAL_PROPERTIES_TMP" "$LOCAL_PROPERTIES"
echo "==> [TaiXu Build] Binding ANDROID_HOME: $ANDROID_HOME"

# 3. SSL 信任库参数
SSL_OPTS=""
if [ -s "$JAVA_HOME/lib/security/cacerts" ]; then
    CACERTS_PATH="$JAVA_HOME/lib/security/cacerts"
elif [ -s /etc/ssl/certs/java/cacerts ]; then
    CACERTS_PATH="/etc/ssl/certs/java/cacerts"
fi
if [ -n "$CACERTS_PATH" ]; then
    # The bundled cacerts uses PKCS12; declare the format explicitly so slim OpenJDK does not try to parse it as JKS.
    SSL_OPTS="-Djavax.net.ssl.trustStore=$CACERTS_PATH -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"
fi

export PATH="$GRADLE_HOME/bin:${TAIXU_CMAKE_HOME:-/opt/taixu/tools/android-suite-offline/cmake}/bin:${TAIXU_NINJA_HOME:-/opt/taixu/tools/android-suite-offline/bin}:${TAIXU_TOOL_DIR:-/opt/taixu/tools}/bin:$PATH"

cd "$PROJECT_PATH"

# 4. 调度 Gradle 构建
EXTRA_ARGS="--console=plain --info --stacktrace --no-daemon --max-workers=2 -Dorg.gradle.native=false -Dorg.gradle.internal.http.connectionTimeout=30000 -Dorg.gradle.internal.http.socketTimeout=60000 -Pandroid.builder.sdkDownload=false"
if [ "${TAIXU_OFFLINE:-0}" = "1" ]; then
    EXTRA_ARGS="$EXTRA_ARGS --offline"
    echo "==> [TaiXu Build] Offline mode: block Gradle network requests and use only local caches"
fi
if [ "$AAPT2_MACHINE" = "b700" ] && [ -x "$AAPT2_OVERRIDE" ] && \
   "$AAPT2_OVERRIDE" version >/dev/null 2>&1; then
    EXTRA_ARGS="$EXTRA_ARGS -Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE"
    echo "==> [TaiXu Build] ARM64 AAPT2: $AAPT2_OVERRIDE"
else
    echo "==> [TaiXu Build] ❌ Pinned ARM64 AAPT2 became unavailable before the build started"
    exit 2
fi
JAVA_RUNTIME_OPTS="-Djava.security.egd=file:/dev/urandom"
[ -n "$SSL_OPTS" ] && JAVA_RUNTIME_OPTS="$JAVA_RUNTIME_OPTS $SSL_OPTS"
export GRADLE_OPTS="${GRADLE_OPTS:-} $JAVA_RUNTIME_OPTS"
GRADLE_JVM_MEMORY_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8"

if [ -d "$GRADLE_HOME/lib" ]; then
    echo "==> [TaiXu Build] Invoking Gradle: $GRADLE_HOME"
    echo "==> [TaiXu Build] Dependency resolution and task execution are starting; downloads, repository URLs, and task stages will be written to the build log in real time..."
    exec "$JAVA_EXEC" $GRADLE_JVM_MEMORY_OPTS \
        -Dorg.gradle.appname=gradle \
        -Dorg.gradle.installation.dir="$GRADLE_HOME" \
        $JAVA_RUNTIME_OPTS \
        -classpath "$GRADLE_HOME/lib/*" \
        org.gradle.launcher.GradleMain $TASK $EXTRA_ARGS
elif [ -d /opt/gradle-8.7/lib ]; then
    echo "==> [TaiXu Build] Invoking the standalone official Gradle 8.7 distribution..."
    exec "$JAVA_EXEC" $GRADLE_JVM_MEMORY_OPTS \
        -Dorg.gradle.appname=gradle \
        -Dorg.gradle.installation.dir=/opt/gradle-8.7 \
        $JAVA_RUNTIME_OPTS \
        -classpath "/opt/gradle-8.7/lib/*" \
        org.gradle.launcher.GradleMain $TASK $EXTRA_ARGS
elif [ -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "==> [TaiXu Build] Invoking $GRADLE_HOME/bin/gradle..."
    exec "$GRADLE_HOME/bin/gradle" $TASK $EXTRA_ARGS
elif [ -f ./gradlew ] && [ -f ./gradle/wrapper/gradle-wrapper.jar ]; then
    echo "==> [TaiXu Build] Invoking the project's local Gradle Wrapper..."
    chmod +x ./gradlew
    exec ./gradlew $TASK $EXTRA_ARGS
elif command -v gradle >/dev/null 2>&1; then
    echo "==> [TaiXu Build] Invoking system Gradle..."
    exec gradle $TASK $EXTRA_ARGS
else
    echo "==> [TaiXu Build] ❌ No valid Gradle environment found. Install the Android & Mobile Full-Stack Development Suite from Plugin Center."
    exit 127
fi
